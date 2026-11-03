"""Durable one-slot task protocol for a TRUSTED host.

No HTTP API, model planner, credential vault, device takeover or production server
is implemented here. A trusted adapter must supply owner, validated grants, tool
classification, independent result verification, and proof that a previous worker
has stopped. These methods must NOT be exposed as unrestricted model tools.
"""
from __future__ import annotations

import contextlib
import hashlib
import json
import re
import sqlite3
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterator

TERMINAL = {'SUCCEEDED', 'CANCELED'}
WAIT = {'WAITING_INPUT', 'WAITING_DEVICE', 'WAITING_AUTHORIZATION'}
IDENT = re.compile(r'[A-Za-z0-9_.:-]{1,160}\Z')
APPLICATION_ID = 1213743433
SCHEMA_VERSION = 2
SECRET_KEYS = {'api_key', 'apikey', 'authorization', 'access_token', 'refresh_token', 'password', 'cookie'}

class Conflict(RuntimeError): pass
class StaleLease(Conflict): pass
class UncertainEffect(Conflict): pass
class AuthorizationRequired(PermissionError): pass


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def identifier(value: str) -> str:
    if not isinstance(value, str) or not IDENT.fullmatch(value):
        raise ValueError('invalid_identifier')
    return value


def no_credentials(value: Any) -> None:
    # Not a DLP classifier: private text still belongs only in the private database.
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or key.lower() in SECRET_KEYS:
                raise ValueError('credentials_must_use_a_vault_reference')
            no_credentials(item)
    elif isinstance(value, list):
        for item in value:
            no_credentials(item)


@dataclass(frozen=True)
class ModelLock:
    provider_id: str
    model_id: str
    reasoning: str
    configuration_revision: str

    def encoded(self) -> str:
        for value in asdict(self).values():
            if not isinstance(value, str) or not value.strip() or len(value) > 200:
                raise ValueError('invalid_model_lock')
        return canonical(asdict(self))


@dataclass(frozen=True)
class Lease:
    task_id: str
    owner: str
    fence: int


@dataclass(frozen=True)
class Permit:
    lease: Lease
    call_id: str
    authorization_epoch: int
    arguments_sha256: str


@dataclass(frozen=True)
class Receipt:
    call_id: str
    result: dict
    verification: dict


def validate_plan(calls: list[str], acceptance: dict, require_evidence: bool) -> None:
    if not isinstance(calls, list) or len(calls) > 100 or len(set(calls)) != len(calls):
        raise ValueError('invalid_plan')
    for call in calls:
        identifier(call)
    if require_evidence and (not calls or not acceptance):
        raise ValueError('action_task_requires_steps_and_acceptance')
    if not isinstance(acceptance, dict) or not set(acceptance) <= set(calls):
        raise ValueError('acceptance_must_reference_planned_calls')
    for rule in acceptance.values():
        if not isinstance(rule, dict) or rule.get('kind') not in {'equals', 'one_of', 'present'}:
            raise ValueError('invalid_acceptance_rule')
        identifier(rule.get('field'))
        keys = {'kind', 'field'} | (set() if rule['kind'] == 'present' else {'value'})
        if set(rule) != keys:
            raise ValueError('invalid_acceptance_fields')
        if rule['kind'] == 'one_of' and (not isinstance(rule['value'], list) or not 1 <= len(rule['value']) <= 20):
            raise ValueError('invalid_acceptance_values')
    if len(canonical(acceptance).encode()) > 16000:
        raise ValueError('acceptance_too_large')


def accepts(result: dict, rule: dict) -> bool:
    if rule['field'] not in result:
        return False
    value = result[rule['field']]
    if rule['kind'] == 'present':
        return value is not None and value != ''
    # Canonical encodings distinguish false from 0 and avoid Python coercion.
    if rule['kind'] == 'equals':
        return canonical(value) == canonical(rule['value'])
    return canonical(value) in [canonical(item) for item in rule['value']]


SCHEMA = '''
CREATE TABLE IF NOT EXISTS tasks (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT,
 id TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, request_key TEXT NOT NULL,
 intent_hash TEXT NOT NULL, goal TEXT NOT NULL, model_json TEXT NOT NULL,
 plan_json TEXT NOT NULL, acceptance_json TEXT NOT NULL, require_evidence INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','VERIFYING','STOP_REQUESTED','WAITING_INPUT',
  'WAITING_DEVICE','WAITING_AUTHORIZATION','PAUSED_BY_USER','FAILED_BLOCKED',
  'SUCCEEDED','CANCELED','UNCERTAIN_SIDE_EFFECT')),
 revision INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0,
 queue_order INTEGER NOT NULL, fence INTEGER NOT NULL DEFAULT 0,
 manual_pause INTEGER NOT NULL DEFAULT 0, cancel_requested INTEGER NOT NULL DEFAULT 0,
 authorization TEXT NOT NULL CHECK(authorization IN ('ACTIVE','EXPIRED','REVOKED')),
 auth_epoch INTEGER NOT NULL DEFAULT 1, capabilities_json TEXT NOT NULL,
 device_confirmation INTEGER NOT NULL DEFAULT 0, blocked_on TEXT,
 reason TEXT, result_json TEXT, created REAL NOT NULL, updated REAL NOT NULL,
 UNIQUE(owner,request_key)
);
CREATE TABLE IF NOT EXISTS execution_slot (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), task_id TEXT REFERENCES tasks(id),
 fence INTEGER NOT NULL DEFAULT 0, queue_clock INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO execution_slot(singleton) VALUES (1);
CREATE TABLE IF NOT EXISTS calls (
 task_id TEXT NOT NULL REFERENCES tasks(id), call_id TEXT NOT NULL,
 tool TEXT NOT NULL, capability TEXT NOT NULL, effect TEXT NOT NULL,
 arguments_hash TEXT NOT NULL, arguments_json TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PREPARED','IN_FLIGHT','SUCCEEDED','UNKNOWN','NOT_DISPATCHED','FAILED_SAFE')),
 fence INTEGER NOT NULL, auth_epoch INTEGER NOT NULL,
 result_json TEXT, verification_json TEXT, reason TEXT,
 created REAL NOT NULL, updated REAL NOT NULL, PRIMARY KEY(task_id,call_id)
);
CREATE TABLE IF NOT EXISTS events (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES tasks(id),
 owner TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL,
 metadata_json TEXT NOT NULL, created REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS usage (
 task_id TEXT NOT NULL REFERENCES tasks(id), request_id TEXT NOT NULL,
 provider_id TEXT NOT NULL, model_id TEXT NOT NULL,
 provenance TEXT NOT NULL CHECK(provenance IN ('provider_reported','estimated','unknown')),
 input_tokens INTEGER, output_tokens INTEGER, cached_tokens INTEGER,
 created REAL NOT NULL, PRIMARY KEY(task_id,request_id)
);
CREATE INDEX IF NOT EXISTS task_queue ON tasks(state,priority,queue_order);
CREATE TRIGGER IF NOT EXISTS immutable_model BEFORE UPDATE OF model_json ON tasks
WHEN NEW.model_json != OLD.model_json BEGIN SELECT RAISE(ABORT,'model_lock_immutable'); END;
'''


class TaskStore:
    def __init__(self, path: Path):
        self.path = Path(path).absolute()
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.path.is_symlink():
            raise ValueError('database_symlink')
        with sqlite3.connect(self.path, timeout=15) as db:
            version = db.execute('PRAGMA user_version').fetchone()[0]
            application = db.execute('PRAGMA application_id').fetchone()[0]
            tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
            expected = {'tasks', 'execution_slot', 'calls', 'events', 'usage'}
            if version not in (0, 1, SCHEMA_VERSION):
                raise RuntimeError('unsupported_schema_do_not_reset')
            if version == 0 and (tables or application not in (0, APPLICATION_ID)):
                raise RuntimeError('unrelated_database_do_not_modify')
            if version > 0 and tables != expected:
                raise RuntimeError('unrecognized_task_schema_do_not_modify')
            if version == 1:
                columns = {r[1] for r in db.execute('PRAGMA table_info(tasks)')}
                if application not in (0, APPLICATION_ID) or not {'intent_hash','device_confirmation','acceptance_json','auth_epoch','model_json'} <= columns:
                    raise RuntimeError('unrecognized_legacy_task_schema')
            if version == SCHEMA_VERSION and application != APPLICATION_ID:
                raise RuntimeError('wrong_database_application_id')
            db.execute('PRAGMA journal_mode=WAL')
            if version == 1:
                migration = (Path(__file__).resolve().parent.parent / 'migrations/002_resume_blockers.sql').read_text()
                db.executescript('BEGIN IMMEDIATE;\n' + migration + '\nCOMMIT;')
            elif version == 0:
                db.executescript('BEGIN IMMEDIATE;\n' + SCHEMA +
                    f'\nPRAGMA application_id={APPLICATION_ID};\nPRAGMA user_version={SCHEMA_VERSION};\nCOMMIT;')
        self.path.chmod(0o600)

    @contextlib.contextmanager
    def transaction(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('PRAGMA synchronous=FULL')
        try:
            db.execute('BEGIN IMMEDIATE')
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def _owned(db, owner, task):
        row = db.execute('SELECT * FROM tasks WHERE id=? AND owner=?', (task, owner)).fetchone()
        if row is None:
            raise KeyError('task_not_found')
        return row

    @classmethod
    def _lease(cls, db, lease):
        row = cls._owned(db, lease.owner, lease.task_id)
        slot = db.execute('SELECT * FROM execution_slot WHERE singleton=1').fetchone()
        if slot['task_id'] != lease.task_id or slot['fence'] != lease.fence or row['fence'] != lease.fence:
            raise StaleLease('stale_execution_fence')
        return row

    @staticmethod
    def _event(db, row, kind, **meta):
        db.execute('INSERT INTO events(task_id,owner,kind,revision,metadata_json,created) VALUES (?,?,?,?,?,?)',
                   (row['id'], row['owner'], kind, row['revision'], canonical(meta), time.time()))

    @staticmethod
    def _order(db):
        db.execute('UPDATE execution_slot SET queue_clock=queue_clock+1 WHERE singleton=1')
        return db.execute('SELECT queue_clock FROM execution_slot WHERE singleton=1').fetchone()[0]

    @staticmethod
    def _unsafe(db, task):
        return db.execute("SELECT 1 FROM calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN')", (task,)).fetchone() is not None

    @staticmethod
    def _release(db, task):
        db.execute("UPDATE calls SET state='NOT_DISPATCHED',updated=? WHERE task_id=? AND state='PREPARED'", (time.time(), task))
        db.execute('UPDATE execution_slot SET task_id=NULL WHERE singleton=1 AND task_id=?', (task,))

    @staticmethod
    def _stop_state(row):
        if row['cancel_requested']: return 'CANCELED'
        if row['manual_pause']: return 'PAUSED_BY_USER'
        if row['authorization'] != 'ACTIVE': return 'WAITING_AUTHORIZATION'
        return row['blocked_on']

    def get(self, owner, task):
        with self.transaction() as db:
            return dict(self._owned(db, owner, task))

    def get_call(self, owner, task, call):
        with self.transaction() as db:
            self._owned(db, owner, task)
            row = db.execute('SELECT * FROM calls WHERE task_id=? AND call_id=?', (task, call)).fetchone()
            if row is None: raise KeyError('call_not_found')
            return dict(row)

    def events(self, owner, task):
        with self.transaction() as db:
            self._owned(db, owner, task)
            return [dict(r) for r in db.execute('SELECT * FROM events WHERE owner=? AND task_id=? ORDER BY sequence', (owner, task))]

    def enqueue(self, owner: str, key: str, goal: str, model: ModelLock,
                required_calls: list[str], capabilities: list[str], acceptance: dict,
                *, require_evidence: bool = True) -> str:
        identifier(owner); identifier(key)
        if not isinstance(goal, str) or not 0 < len(goal) <= 8000:
            raise ValueError('invalid_goal')
        validate_plan(required_calls, acceptance, require_evidence)
        caps = sorted(set(identifier(c) for c in capabilities))
        locked = model.encoded()
        intent = digest([goal, json.loads(locked), required_calls, acceptance, require_evidence])
        with self.transaction() as db:
            old = db.execute('SELECT * FROM tasks WHERE owner=? AND request_key=?', (owner, key)).fetchone()
            if old:
                if old['intent_hash'] != intent: raise Conflict('idempotency_intent_conflict')
                return old['id']
            task, now = str(uuid.uuid4()), time.time()
            db.execute('''INSERT INTO tasks(id,owner,request_key,intent_hash,goal,model_json,plan_json,
                          acceptance_json,require_evidence,state,queue_order,authorization,capabilities_json,created,updated)
                          VALUES (?,?,?,?,?,?,?,?,?,'QUEUED',?,'ACTIVE',?,?,?)''',
                       (task, owner, key, intent, goal, locked, canonical(required_calls), canonical(acceptance),
                        int(require_evidence), self._order(db), canonical(caps), now, now))
            self._event(db, self._owned(db, owner, task), 'enqueued', step_count=len(required_calls))
            return task

    def claim(self) -> Lease | None:
        with self.transaction() as db:
            slot = db.execute('SELECT * FROM execution_slot WHERE singleton=1').fetchone()
            if slot['task_id'] is not None: return None
            row = db.execute("""SELECT * FROM tasks WHERE state='QUEUED' AND authorization='ACTIVE'
                                AND manual_pause=0 AND cancel_requested=0 AND blocked_on IS NULL AND device_confirmation=0
                                ORDER BY priority DESC,queue_order,sequence LIMIT 1""").fetchone()
            if row is None: return None
            fence = slot['fence'] + 1
            db.execute('UPDATE execution_slot SET task_id=?,fence=? WHERE singleton=1', (row['id'], fence))
            db.execute("UPDATE tasks SET state='RUNNING',fence=?,revision=revision+1,updated=? WHERE id=?", (fence, time.time(), row['id']))
            self._event(db, self._owned(db, row['owner'], row['id']), 'claimed', fence=fence)
            return Lease(row['id'], row['owner'], fence)

    def prepare_call(self, lease: Lease, call: str, tool: str, arguments: dict,
                     capability: str, effect: str, observed_model: ModelLock) -> Permit | Receipt:
        identifier(call); identifier(tool); identifier(capability)
        if effect not in {'READ', 'VERSIONED_WRITE', 'EXTERNAL'}: raise ValueError('invalid_effect')
        if not isinstance(arguments, dict): raise ValueError('arguments_must_be_an_object')
        no_credentials(arguments)
        encoded = canonical(arguments)
        if len(encoded.encode()) > 65536: raise ValueError('arguments_too_large')
        sha = hashlib.sha256(encoded.encode()).hexdigest()
        with self.transaction() as db:
            row = self._lease(db, lease)
            if row['model_json'] != observed_model.encoded(): raise Conflict('locked_model_or_reasoning_changed')
            if row['state'] != 'RUNNING' or self._stop_state(row): raise AuthorizationRequired('task_not_dispatchable')
            if capability not in json.loads(row['capabilities_json']): raise AuthorizationRequired('capability_not_granted')
            plan = json.loads(row['plan_json'])
            if call not in plan: raise Conflict('call_not_in_committed_plan')
            old = db.execute('SELECT * FROM calls WHERE task_id=? AND call_id=?', (row['id'], call)).fetchone()
            if old and (old['tool'], old['arguments_hash'], old['capability'], old['effect']) != (tool, sha, capability, effect):
                raise Conflict('call_identity_conflict')
            if old and old['state'] == 'SUCCEEDED':
                return Receipt(call, json.loads(old['result_json']), json.loads(old['verification_json']))
            if self._unsafe(db, row['id']): raise UncertainEffect('an_operation_may_still_be_active')
            done = {r[0] for r in db.execute("SELECT call_id FROM calls WHERE task_id=? AND state='SUCCEEDED'", (row['id'],))}
            if any(previous not in done for previous in plan[:plan.index(call)]):
                raise Conflict('prior_plan_steps_not_verified')
            if old:
                db.execute("UPDATE calls SET state='PREPARED',fence=?,auth_epoch=?,reason=NULL,updated=? WHERE task_id=? AND call_id=?",
                           (lease.fence, row['auth_epoch'], time.time(), row['id'], call))
            else:
                db.execute('''INSERT INTO calls(task_id,call_id,tool,capability,effect,arguments_hash,
                              arguments_json,state,fence,auth_epoch,created,updated) VALUES (?,?,?,?,?,?,?,'PREPARED',?,?,?,?)''',
                           (row['id'], call, tool, capability, effect, sha, encoded, lease.fence, row['auth_epoch'], time.time(), time.time()))
            self._event(db, row, 'call_prepared', call_id=call, tool=tool)
            return Permit(lease, call, row['auth_epoch'], sha)

    def dispatch(self, permit: Permit) -> None:
        """Authorization linearization point. Invoke the adapter only AFTER commit."""
        with self.transaction() as db:
            row = self._lease(db, permit.lease)
            if row['state'] != 'RUNNING' or self._stop_state(row) or row['auth_epoch'] != permit.authorization_epoch:
                raise AuthorizationRequired('authorization_changed_before_dispatch')
            if self._unsafe(db, row['id']): raise UncertainEffect('another_operation_is_active')
            changed = db.execute("""UPDATE calls SET state='IN_FLIGHT',updated=? WHERE task_id=? AND call_id=?
                                    AND state='PREPARED' AND fence=? AND auth_epoch=? AND arguments_hash=?""",
                                 (time.time(), row['id'], permit.call_id, permit.lease.fence,
                                  permit.authorization_epoch, permit.arguments_sha256)).rowcount
            if changed != 1: raise Conflict('already_dispatched_or_stale_call')
            self._event(db, row, 'call_dispatched', call_id=permit.call_id)

    def begin_verification(self, permit):
        with self.transaction() as db:
            row = self._lease(db, permit.lease)
            call = db.execute("SELECT state FROM calls WHERE task_id=? AND call_id=? AND fence=?",
                              (row['id'], permit.call_id, permit.lease.fence)).fetchone()
            if call is None or call['state'] != 'IN_FLIGHT': raise Conflict('no_result_to_verify')
            if row['state'] == 'RUNNING':
                db.execute("UPDATE tasks SET state='VERIFYING',revision=revision+1,updated=? WHERE id=?", (time.time(), row['id']))
                self._event(db, self._owned(db, row['owner'], row['id']), 'verifying', call_id=permit.call_id)

    @staticmethod
    def _payload(result, proof):
        if not isinstance(result, dict) or not isinstance(proof, dict): raise ValueError('invalid_receipt')
        if proof.get('verified') is not True or not proof.get('method'): raise ValueError('verification_required')
        if proof.get('result_sha256') != digest(result): raise ValueError('verification_hash_mismatch')
        no_credentials(result)
        body, evidence = canonical(result), canonical(proof)
        if len(body.encode()) > 65536 or len(evidence.encode()) > 16000:
            raise ValueError('large_outputs_require_artifact_references')
        return body, evidence

    def commit_result(self, permit: Permit, result: dict, proof: dict):
        body, evidence = self._payload(result, proof)
        with self.transaction() as db:
            row = self._lease(db, permit.lease)
            changed = db.execute("""UPDATE calls SET state='SUCCEEDED',result_json=?,verification_json=?,updated=?
                                    WHERE task_id=? AND call_id=? AND state='IN_FLIGHT' AND fence=? AND arguments_hash=?""",
                                 (body, evidence, time.time(), row['id'], permit.call_id, permit.lease.fence, permit.arguments_sha256)).rowcount
            if changed != 1: raise Conflict('result_requires_current_in_flight_call')
            state = self._stop_state(row) or 'RUNNING'
            db.execute('UPDATE tasks SET state=?,revision=revision+1,updated=? WHERE id=?', (state, time.time(), row['id']))
            if state != 'RUNNING': self._release(db, row['id'])
            self._event(db, self._owned(db, row['owner'], row['id']), 'call_verified', call_id=permit.call_id)

    def fail_call(self, permit: Permit, *, safe_local_read: bool = False):
        with self.transaction() as db:
            row = self._lease(db, permit.lease)
            call = db.execute('SELECT * FROM calls WHERE task_id=? AND call_id=?', (row['id'], permit.call_id)).fetchone()
            if call is None or call['state'] != 'IN_FLIGHT' or call['fence'] != permit.lease.fence:
                raise Conflict('no_current_in_flight_call')
            if safe_local_read and call['effect'] != 'READ':
                raise Conflict('only_local_nonbillable_read_is_safe_to_repeat')
            call_state = 'FAILED_SAFE' if safe_local_read else 'UNKNOWN'
            task_state = (self._stop_state(row) or 'FAILED_BLOCKED') if safe_local_read else 'UNCERTAIN_SIDE_EFFECT'
            db.execute('UPDATE calls SET state=?,reason=?,updated=? WHERE task_id=? AND call_id=?',
                       (call_state, 'adapter_interrupted', time.time(), row['id'], permit.call_id))
            db.execute('UPDATE tasks SET state=?,reason=?,revision=revision+1,updated=? WHERE id=?',
                       (task_state, 'adapter_interrupted', time.time(), row['id']))
            if safe_local_read: self._release(db, row['id'])
            self._event(db, self._owned(db, row['owner'], row['id']), 'call_failed', state=call_state, call_id=permit.call_id)

    def _request_stop(self, owner, task, cancel):
        with self.transaction() as db:
            row = self._owned(db, owner, task)
            if row['state'] in TERMINAL: return
            field = 'cancel_requested' if cancel else 'manual_pause'
            db.execute(f'UPDATE tasks SET {field}=1,revision=revision+1,updated=? WHERE id=?', (time.time(), task))
            row = self._owned(db, owner, task)
            unsafe = self._unsafe(db, task)
            state = ('UNCERTAIN_SIDE_EFFECT' if row['state'] == 'UNCERTAIN_SIDE_EFFECT' else 'STOP_REQUESTED') if unsafe else self._stop_state(row)
            db.execute('UPDATE tasks SET state=? WHERE id=?', (state, task))
            if not unsafe: self._release(db, task)
            self._event(db, self._owned(db, owner, task), 'cancel_requested' if cancel else 'paused_by_user')

    def pause(self, owner, task): self._request_stop(owner, task, False)
    def cancel(self, owner, task): self._request_stop(owner, task, True)

    def change_authorization(self, owner, task, status):
        if status not in {'EXPIRED', 'REVOKED'}: raise ValueError('invalid_authorization_status')
        with self.transaction() as db:
            row = self._owned(db, owner, task)
            if row['state'] in TERMINAL: return
            if row['authorization'] == 'REVOKED' and status == 'EXPIRED': return
            db.execute('UPDATE tasks SET authorization=?,auth_epoch=auth_epoch+1,revision=revision+1,updated=? WHERE id=?', (status, time.time(), task))
            row = self._owned(db, owner, task)
            unsafe = self._unsafe(db, task)
            state = ('UNCERTAIN_SIDE_EFFECT' if row['state'] == 'UNCERTAIN_SIDE_EFFECT' else 'STOP_REQUESTED') if unsafe else self._stop_state(row)
            db.execute('UPDATE tasks SET state=? WHERE id=?', (state, task))
            if not unsafe: self._release(db, task)
            self._event(db, self._owned(db, owner, task), 'authorization_changed', status=status, epoch=row['auth_epoch'])

    def restore_authorization(self, owner, task, expected_epoch, capabilities, *, source):
        """Trusted callback AFTER actual credential validation or explicit reauthorization."""
        if source not in {'VERIFIED_REPLACEMENT', 'USER_REAUTHORIZE'}: raise ValueError('invalid_source')
        caps = sorted(set(identifier(c) for c in capabilities))
        with self.transaction() as db:
            row = self._owned(db, owner, task)
            if row['auth_epoch'] != expected_epoch: raise Conflict('stale_authorization_epoch')
            if row['state'] in TERMINAL: raise Conflict('terminal_task')
            if row['authorization'] == 'REVOKED' and source != 'USER_REAUTHORIZE':
                raise AuthorizationRequired('revocation_requires_explicit_reauthorization')
            if source == 'VERIFIED_REPLACEMENT' and not set(caps) <= set(json.loads(row['capabilities_json'])):
                raise AuthorizationRequired('credential_refresh_cannot_expand_scope')
            db.execute("UPDATE tasks SET authorization='ACTIVE',auth_epoch=auth_epoch+1,capabilities_json=?,revision=revision+1,updated=? WHERE id=?",
                       (canonical(caps), time.time(), task))
            if row['state'] == 'WAITING_AUTHORIZATION' and not row['manual_pause'] and not row['cancel_requested'] and not self._unsafe(db, task):
                state = row['blocked_on'] or 'QUEUED'
                db.execute("UPDATE tasks SET state=?,priority=1,queue_order=? WHERE id=?", (state, self._order(db), task))
            self._event(db, self._owned(db, owner, task), 'authorization_restored', source=source)

    def wait(self, lease, state, reason, *, foreground_required=False):
        if state not in WAIT: raise ValueError('invalid_wait_state')
        identifier(reason)
        with self.transaction() as db:
            row = self._lease(db, lease)
            if row['state'] != 'RUNNING' or self._unsafe(db, row['id']):
                raise UncertainEffect('cannot_release_an_unverified_operation')
            blocked = state if state in {'WAITING_DEVICE','WAITING_INPUT'} else None
            db.execute('UPDATE tasks SET state=?,reason=?,device_confirmation=?,blocked_on=?,revision=revision+1,updated=? WHERE id=?',
                       (state, reason, int(state == 'WAITING_DEVICE' and foreground_required), blocked, time.time(), row['id']))
            self._release(db, row['id'])
            self._event(db, self._owned(db, row['owner'], row['id']), 'waiting', state=state)

    def resume(self, owner, task, expected_revision, *, foreground_device_confirmed=False):
        """Explicit host/user action; do not call on arbitrary connectivity events."""
        with self.transaction() as db:
            row = self._owned(db, owner, task)
            if row['revision'] != expected_revision: raise Conflict('stale_task_revision')
            if row['state'] not in WAIT | {'PAUSED_BY_USER', 'FAILED_BLOCKED'} or row['cancel_requested']:
                raise Conflict('task_not_resumable')
            if self._unsafe(db, task): raise UncertainEffect('reconcile_before_resume')
            if row['authorization'] != 'ACTIVE': raise AuthorizationRequired('authorization_not_active')
            if row['device_confirmation'] and not foreground_device_confirmed:
                raise AuthorizationRequired('foreground_device_confirmation_required')
            db.execute("""UPDATE tasks SET state='QUEUED',manual_pause=0,device_confirmation=0,blocked_on=NULL,
                          priority=1,queue_order=?,reason=NULL,revision=revision+1,updated=? WHERE id=?""", (self._order(db), time.time(), task))
            self._event(db, self._owned(db, owner, task), 'resumed_by_user')

    def device_available(self, owner, task):
        """Record availability; return True only when safely requeued, not merely observed."""
        with self.transaction() as db:
            row = self._owned(db, owner, task)
            if (row['blocked_on'] != 'WAITING_DEVICE' or row['device_confirmation'] or
                row['state'] in TERMINAL or self._unsafe(db, task)):
                return False
            db.execute('UPDATE tasks SET blocked_on=NULL,revision=revision+1,updated=? WHERE id=?', (time.time(), task))
            row = self._owned(db, owner, task)
            state = self._stop_state(row) or 'QUEUED'
            db.execute("UPDATE tasks SET state=?,priority=1,queue_order=? WHERE id=?", (state, self._order(db), task))
            self._event(db, self._owned(db, owner, task), 'device_available', requeued=state == 'QUEUED')
            return state == 'QUEUED'

    def recover_interrupted(self):
        """Startup on a trusted host. Timeout does not prove an external effect absent."""
        with self.transaction() as db:
            slot = db.execute('SELECT * FROM execution_slot WHERE singleton=1').fetchone()
            if slot['task_id'] is None: return []
            row = db.execute('SELECT * FROM tasks WHERE id=?', (slot['task_id'],)).fetchone()
            db.execute("""UPDATE calls SET state=CASE WHEN effect='READ' THEN 'FAILED_SAFE' ELSE 'UNKNOWN' END,
                          reason='executor_interrupted',updated=? WHERE task_id=? AND state='IN_FLIGHT'""", (time.time(), row['id']))
            unsafe = self._unsafe(db, row['id'])
            state = 'UNCERTAIN_SIDE_EFFECT' if unsafe else self._stop_state(row) or 'FAILED_BLOCKED'
            db.execute("UPDATE tasks SET state=?,reason='executor_interrupted',revision=revision+1,updated=? WHERE id=?", (state, time.time(), row['id']))
            if not unsafe: self._release(db, row['id'])
            self._event(db, self._owned(db, row['owner'], row['id']), 'recovered_checkpoint', state=state)
            return [row['id']]

    def reconcile_applied(self, owner, task, call, result, proof, *, executor_stopped):
        """Trusted independent readback. No 'not found therefore replay' shortcut."""
        if executor_stopped is not True: raise UncertainEffect('old_executor_not_confirmed_stopped')
        body, evidence = self._payload(result, proof)
        with self.transaction() as db:
            row = self._owned(db, owner, task)
            if row['state'] != 'UNCERTAIN_SIDE_EFFECT': raise Conflict('task_not_uncertain')
            changed = db.execute("UPDATE calls SET state='SUCCEEDED',result_json=?,verification_json=?,updated=? WHERE task_id=? AND call_id=? AND state='UNKNOWN'",
                                 (body, evidence, time.time(), task, call)).rowcount
            if changed != 1 or self._unsafe(db, task): raise UncertainEffect('unresolved_operation')
            state = self._stop_state(row) or 'FAILED_BLOCKED'
            db.execute("UPDATE tasks SET state=?,reason='verified_recovery_requires_explicit_resume',revision=revision+1,updated=? WHERE id=?", (state, time.time(), task))
            self._release(db, task)
            self._event(db, self._owned(db, owner, task), 'effect_reconciled', call_id=call)

    def replan(self, owner, task, expected_revision, required_calls, acceptance):
        validate_plan(required_calls, acceptance, True)
        with self.transaction() as db:
            row = self._owned(db, owner, task)
            if row['revision'] != expected_revision or row['state'] not in WAIT | {'PAUSED_BY_USER', 'QUEUED', 'FAILED_BLOCKED'}:
                raise Conflict('pause_and_refresh_before_replanning')
            if self._unsafe(db, task): raise UncertainEffect('cannot_replan_unknown_effect')
            db.execute('UPDATE tasks SET plan_json=?,acceptance_json=?,revision=revision+1,updated=? WHERE id=?',
                       (canonical(required_calls), canonical(acceptance), time.time(), task))
            self._event(db, self._owned(db, owner, task), 'plan_revised', step_count=len(required_calls))

    def complete(self, lease, summary):
        no_credentials(summary)
        encoded = canonical(summary)
        if len(encoded.encode()) > 16000: raise ValueError('summary_too_large')
        with self.transaction() as db:
            row = self._lease(db, lease)
            if row['state'] != 'RUNNING' or self._stop_state(row) or self._unsafe(db, row['id']): raise Conflict('task_not_completable')
            required = json.loads(row['plan_json']); rules = json.loads(row['acceptance_json'])
            done = {r['call_id']: json.loads(r['result_json']) for r in db.execute("SELECT * FROM calls WHERE task_id=? AND state='SUCCEEDED' AND verification_json IS NOT NULL", (row['id'],))}
            if not set(required) <= set(done) or (row['require_evidence'] and not required):
                raise Conflict('planned_receipts_missing')
            if not all(accepts(done[call], rule) for call, rule in rules.items()):
                raise Conflict('goal_acceptance_not_satisfied')
            db.execute("UPDATE tasks SET state='SUCCEEDED',result_json=?,revision=revision+1,updated=? WHERE id=?", (encoded, time.time(), row['id']))
            self._release(db, row['id'])
            self._event(db, self._owned(db, row['owner'], row['id']), 'completed')

    def record_usage(self, owner, task, request_id, provenance, input_tokens=None, output_tokens=None, cached_tokens=None):
        identifier(request_id)
        if provenance not in {'provider_reported', 'estimated', 'unknown'}: raise ValueError('invalid_provenance')
        values = (input_tokens, output_tokens, cached_tokens)
        if any(v is not None and (type(v) is not int or v < 0 or v > 2**63-1) for v in values): raise ValueError('invalid_usage')
        if provenance == 'unknown' and any(v is not None for v in values): raise ValueError('unknown_usage_is_not_zero')
        with self.transaction() as db:
            row = self._owned(db, owner, task); model = json.loads(row['model_json'])
            old = db.execute('SELECT * FROM usage WHERE task_id=? AND request_id=?', (task, request_id)).fetchone()
            if old:
                current = (old['provenance'], old['input_tokens'], old['output_tokens'], old['cached_tokens'])
                if current == (provenance, *values): return
                rank = {'unknown': 0, 'estimated': 1, 'provider_reported': 2}
                if rank[provenance] < rank[old['provenance']]: return
                if rank[provenance] == rank[old['provenance']]: raise Conflict('usage_receipt_conflict')
                db.execute('UPDATE usage SET provenance=?,input_tokens=?,output_tokens=?,cached_tokens=? WHERE task_id=? AND request_id=?',
                           (provenance, *values, task, request_id))
                self._event(db, row, 'usage_reconciled', request_id=request_id, provenance=provenance)
                return
            db.execute('INSERT INTO usage VALUES (?,?,?,?,?,?,?,?,?)', (task, request_id, model['provider_id'], model['model_id'], provenance, *values, time.time()))
            # No monetary/token ceiling, model routing or automatic stop is applied.

    def usage_summary(self, owner, start_epoch, end_epoch):
        """Caller supplies day/week/month boundaries in the user's intended timezone.

        Reported, estimated and unknown are separate groups. SUM(NULL) remains
        NULL; this API does not invent zero usage or prices for an unknown call.
        """
        if not isinstance(start_epoch, (float,int)) or not isinstance(end_epoch, (float,int)) or not start_epoch < end_epoch:
            raise ValueError('invalid_time_window')
        with self.transaction() as db:
            return [dict(row) for row in db.execute("""SELECT u.provider_id,u.model_id,u.provenance,
                     COUNT(*) AS calls,SUM(u.input_tokens) AS input_tokens,SUM(u.output_tokens) AS output_tokens,
                     SUM(u.cached_tokens) AS cached_tokens,COUNT(u.input_tokens) AS known_input_calls,
                     COUNT(u.output_tokens) AS known_output_calls
                     FROM usage u JOIN tasks t ON t.id=u.task_id
                     WHERE t.owner=? AND u.created>=? AND u.created<?
                     GROUP BY u.provider_id,u.model_id,u.provenance
                     ORDER BY u.provider_id,u.model_id,u.provenance""", (owner,start_epoch,end_epoch))]
