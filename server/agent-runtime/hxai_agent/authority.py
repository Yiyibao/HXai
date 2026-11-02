"""Authenticated admission over the existing task store; one database per user workspace.

Enrollment and process retirement are host operations, never model/RPC parameters.
A disconnected executor retains its slot. Device retirement needs its paired signing
key; server-worker retirement additionally needs the supervisor's actual wait result.
"""
from __future__ import annotations
import base64
import contextlib
import hashlib
import hmac
import json
import secrets
import threading
import time
import uuid
from dataclasses import asdict
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from .store import (TaskStore, ModelLock, Lease, Conflict, AuthorizationRequired,
                    UncertainEffect, canonical, digest, identifier, TERMINAL)
from .domain import WorkspaceDomain
from .hosting import grant_expired

NETWORK_SCHEMA = '''
CREATE TABLE net_actors(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL UNIQUE,role TEXT NOT NULL,
 owners_json TEXT NOT NULL,epoch INTEGER NOT NULL DEFAULT 1,revoked INTEGER NOT NULL DEFAULT 0,
 expires REAL NOT NULL,public_key TEXT);
CREATE TABLE net_commands(actor TEXT NOT NULL REFERENCES net_actors(id),id TEXT NOT NULL,
 intent_hash TEXT NOT NULL,result_json TEXT,PRIMARY KEY(actor,id));
CREATE TABLE net_jobs(task_id TEXT PRIMARY KEY REFERENCES tasks(id),mode TEXT NOT NULL,
 source_id TEXT NOT NULL,actor TEXT NOT NULL REFERENCES net_actors(id),snapshot_json TEXT NOT NULL,
 config_cipher TEXT,credential_revision INTEGER NOT NULL DEFAULT 1,credential_revoked INTEGER NOT NULL DEFAULT 0,
 input_blocked INTEGER NOT NULL DEFAULT 0,device_blocked INTEGER NOT NULL DEFAULT 0,
 device_confirmation INTEGER NOT NULL DEFAULT 0,goal_revision INTEGER NOT NULL DEFAULT 1,
 planned_revision INTEGER NOT NULL DEFAULT 0,plan_json TEXT,UNIQUE(actor,source_id,mode));
CREATE TABLE net_instances(task_id TEXT NOT NULL REFERENCES tasks(id),fence INTEGER NOT NULL,
 actor TEXT NOT NULL REFERENCES net_actors(id),instance TEXT NOT NULL,state TEXT NOT NULL,
 stop_hash TEXT,PRIMARY KEY(task_id,fence));
CREATE TABLE net_checkpoints(task_id TEXT PRIMARY KEY REFERENCES tasks(id),value_json TEXT NOT NULL);
CREATE TABLE net_provider_calls(task_id TEXT NOT NULL REFERENCES tasks(id),id TEXT NOT NULL,
 fence INTEGER NOT NULL,instance TEXT NOT NULL,input_hash TEXT NOT NULL,body_json TEXT NOT NULL,
 role TEXT NOT NULL,state TEXT NOT NULL,result_json TEXT,PRIMARY KEY(task_id,id));
CREATE TABLE net_device_calls(task_id TEXT NOT NULL REFERENCES tasks(id),id TEXT NOT NULL,
 tool TEXT NOT NULL,input_json TEXT NOT NULL,input_hash TEXT NOT NULL,grant_epoch INTEGER NOT NULL,
 state TEXT NOT NULL,device_actor TEXT,instance TEXT,fence INTEGER,result_json TEXT,result_hash TEXT,
 PRIMARY KEY(task_id,id));
CREATE TABLE net_processes(instance TEXT PRIMARY KEY,actor TEXT NOT NULL REFERENCES net_actors(id),
 pid INTEGER NOT NULL,birth TEXT NOT NULL,state TEXT NOT NULL);
'''
NETWORK_TABLES = frozenset({'net_actors','net_commands','net_jobs','net_instances',
                           'net_checkpoints','net_provider_calls','net_device_calls','net_processes'})
DOCUMENT_SCHEMA = '''
CREATE TABLE net_document_calls(task_id TEXT NOT NULL REFERENCES tasks(id),call_id TEXT NOT NULL,
 request_key TEXT NOT NULL UNIQUE,source_json TEXT NOT NULL,options_json TEXT NOT NULL,
 remote_id TEXT,state TEXT NOT NULL,result_json TEXT,PRIMARY KEY(task_id,call_id));
'''
MAX_FENCE = 2**63-1


def fields(value, names):
    if not isinstance(value, dict) or set(value) != set(names.split()):
        raise ValueError('invalid_fields')


def raw_sha(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


class NetworkStore(TaskStore):
    schema_version = 4
    extension_schema = NETWORK_SCHEMA + DOCUMENT_SCHEMA
    extension_tables = NETWORK_TABLES | {'net_document_calls'}
    extension_migrations = {3:(NETWORK_TABLES,DOCUMENT_SCHEMA)}

    def __init__(self, path):
        self._nested = threading.local()
        super().__init__(path)

    @contextlib.contextmanager
    def transaction(self):
        active = getattr(self._nested, 'connection', None)
        if active is not None:
            # Savepoints preserve atomicity even when a caller catches an inner failure.
            name = 'nested_' + uuid.uuid4().hex
            active.execute('SAVEPOINT ' + name)
            try:
                yield active
                active.execute('RELEASE SAVEPOINT ' + name)
            except BaseException:
                active.execute('ROLLBACK TO SAVEPOINT ' + name)
                active.execute('RELEASE SAVEPOINT ' + name)
                raise
            return
        with super().transaction() as db:
            self._nested.connection = db
            try:
                yield db
            finally:
                del self._nested.connection

    @staticmethod
    def _unsafe(db, task):
        return TaskStore._unsafe(db,task) or db.execute(
            "SELECT 1 FROM net_provider_calls WHERE task_id=? AND state IN ('IN_FLIGHT','UNKNOWN')",
            (task,)).fetchone() is not None

    @staticmethod
    def _release(db, task):
        # Finishing a call or receiving a pause is not proof that its host stopped.
        if db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'", (task,)).fetchone():
            return
        TaskStore._release(db, task)

    def recover_interrupted(self):
        with self.transaction() as db:
            if db.execute("SELECT 1 FROM net_instances WHERE state!='STOPPED'").fetchone():
                raise UncertainEffect('host_stop_proof_required')
            return super().recover_interrupted()


class NetworkAuthority:
    def __init__(self, store: NetworkStore):
        self.store = store
        self.domain = WorkspaceDomain(store)

    def enroll(self, actor, owners, *, role='pairing', lifetime=86400*30):
        """Local administrator only. Caller writes the returned token to a private file."""
        identifier(actor)
        if role not in {'pairing','worker'} or not owners or lifetime <= 0:
            raise ValueError('invalid_enrollment')
        for owner in owners:
            identifier(owner)
        token = secrets.token_urlsafe(32)
        with self.store.transaction() as db:
            db.execute('INSERT INTO net_actors(id,token_hash,role,owners_json,expires) VALUES(?,?,?,?,?)',
                       (actor, raw_sha(token), role, canonical(sorted(set(owners))), time.time()+lifetime))
        return token

    def revoke_actor(self, actor):
        with self.store.transaction() as db:
            changed = db.execute('UPDATE net_actors SET revoked=1,epoch=epoch+1 WHERE id=?', (actor,)).rowcount
            if changed != 1:
                raise KeyError('actor_not_found')
            tasks = db.execute("SELECT task_id FROM net_instances WHERE actor=? AND state!='STOPPED' UNION SELECT task_id FROM net_jobs WHERE actor=?", (actor,actor)).fetchall()
            for row in tasks:
                task = db.execute('SELECT owner FROM tasks WHERE id=?', (row[0],)).fetchone()
                self.store.change_authorization(task[0], row[0], 'REVOKED')
            db.execute('UPDATE net_jobs SET credential_revoked=1 WHERE actor=?', (actor,))

    @contextlib.contextmanager
    def request(self, token, owner=None, roles=('device','worker'), *, settlement=False):
        if not isinstance(token, str) or len(token) != 43:
            raise AuthorizationRequired('request_denied')
        with self.store.transaction() as db:
            actor = db.execute('SELECT * FROM net_actors WHERE token_hash=?', (raw_sha(token),)).fetchone()
            if (actor is None or (not settlement and (actor['revoked'] or actor['expires'] <= time.time())) or actor['role'] not in roles
                    or (owner is not None and owner not in json.loads(actor['owners_json']))):
                raise AuthorizationRequired('request_denied')
            yield db, actor

    def pair(self, token, value):
        fields(value, 'publicKey')
        pem = value['publicKey']
        if not isinstance(pem, str) or len(pem) > 8192:
            raise ValueError('invalid_public_key')
        public = serialization.load_pem_public_key(pem.encode())
        if not isinstance(public, rsa.RSAPublicKey) or public.key_size < 2048:
            raise ValueError('invalid_public_key')
        with self.request(token, roles=('pairing','device')) as (db, actor):
            if actor['public_key'] is not None and actor['public_key'] != pem:
                raise Conflict('pairing_identity_changed')
            db.execute("UPDATE net_actors SET role='device',public_key=? WHERE id=?", (pem,actor['id']))
            status=self.domain.status()
            return {'actor':actor['id'],'owners':json.loads(actor['owners_json']), 'protocolVersion':1,
                    'domain':status['domain'],'retired':status['retired']}

    @staticmethod
    def command(db, actor, command_id, operation, value):
        identifier(command_id)
        intent = digest([operation,value])
        old = db.execute('SELECT * FROM net_commands WHERE actor=? AND id=?', (actor,command_id)).fetchone()
        if old:
            if old['intent_hash'] != intent:
                raise Conflict('command_intent_changed')
            return json.loads(old['result_json']) if old['result_json'] is not None else None
        db.execute('INSERT INTO net_commands(actor,id,intent_hash) VALUES(?,?,?)', (actor,command_id,intent))
        return None

    @staticmethod
    def acknowledge(db, actor, command_id, value):
        db.execute('UPDATE net_commands SET result_json=? WHERE actor=? AND id=?', (canonical(value),actor,command_id))
        return value

    @staticmethod
    def worker_actor(task, instance):
        identifier(task);identifier(instance)
        if ':' in task or ':' in instance:raise ValueError('invalid_worker_identity')
        return 'worker:'+task+':'+instance

    @staticmethod
    def worker_scope(actor):
        parts=actor['id'].split(':')
        if actor['role']!='worker' or len(parts)!=3 or parts[0]!='worker':
            raise AuthorizationRequired('worker_has_no_task_grant')
        return parts[1],parts[2]

    def _bound(self, db, actor, owner, task, fence, instance, *, dispatch=True):
        if dispatch:self.domain.require_active()
        if actor['role']=='worker' and self.worker_scope(actor)!=(task,instance):
            raise AuthorizationRequired('worker_task_scope_denied')
        row = self.store._owned(db,owner,task)
        binding = db.execute('SELECT * FROM net_instances WHERE task_id=? AND fence=?', (task,fence)).fetchone()
        slot = db.execute('SELECT * FROM execution_slot WHERE singleton=1').fetchone()
        if (binding is None or binding['actor'] != actor['id'] or binding['instance'] != instance
                or binding['state'] == 'STOPPED' or slot['task_id'] != task or slot['fence'] != fence):
            raise Conflict('stale_executor_instance')
        if dispatch and (binding['state'] != 'ACTIVE' or row['state'] != 'RUNNING' or self.store._stop_state(row)):
            raise AuthorizationRequired('executor_not_dispatchable')
        if dispatch:
            job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(task,)).fetchone()
            if job is not None and job['mode']=='cloud':
                grant=json.loads(job['snapshot_json'])['grant']
                if job['credential_revoked'] or grant_expired(grant) or job['input_blocked'] or job['device_blocked'] or job['device_confirmation']:
                    raise AuthorizationRequired('hosting_grant_or_blocker_changed')
        return row

    def claim(self, token, value, *, mode):
        fields(value,'owner taskId instance')
        owner, task, instance = value['owner'], value['taskId'], value['instance']
        identifier(instance)
        if mode not in {'native','cloud','device'}:
            raise ValueError('invalid_executor_mode')
        with self.request(token,owner,roles=('worker',) if mode=='cloud' else ('device',)) as (db,actor):
            self.domain.require_active()
            if mode=='cloud' and self.worker_scope(actor)!=(task,instance):
                raise AuthorizationRequired('worker_task_scope_denied')
            job = db.execute('SELECT * FROM net_jobs WHERE task_id=? AND mode=?', (task,'cloud' if mode=='device' else mode)).fetchone()
            if job is None or (mode!='cloud' and job['actor'] != actor['id']):
                raise AuthorizationRequired('job_not_owned')
            row = self.store._owned(db,owner,task)
            slot = db.execute('SELECT * FROM execution_slot WHERE singleton=1').fetchone()
            if slot['task_id'] is not None:
                prior = db.execute('SELECT * FROM net_instances WHERE task_id=? AND fence=?', (task,slot['fence'])).fetchone()
                if slot['task_id']==task and prior and prior['actor']==actor['id'] and prior['instance']==instance and prior['state']=='ACTIVE':
                    return {'state':row['state'],'taskId':task,'fence':slot['fence'],'instance':instance}
                return {'state':'QUEUED','taskId':task}
            if row['state']!='QUEUED' or self.store._stop_state(row):
                raise Conflict('job_not_queued')
            first=db.execute("SELECT id FROM tasks WHERE state='QUEUED' AND manual_pause=0 AND cancel_requested=0 AND authorization='ACTIVE' AND blocked_on IS NULL AND device_confirmation=0 ORDER BY priority DESC,queue_order,sequence LIMIT 1").fetchone()
            if first is None or first[0]!=task:
                return {'state':'QUEUED','taskId':task}
            if slot['fence'] >= MAX_FENCE:
                raise Conflict('execution_fence_exhausted')
            fence=slot['fence']+1
            db.execute('UPDATE execution_slot SET task_id=?,fence=? WHERE singleton=1',(task,fence))
            db.execute("UPDATE tasks SET state='RUNNING',fence=?,revision=revision+1,updated=? WHERE id=?",(fence,time.time(),task))
            db.execute("INSERT INTO net_instances(task_id,fence,actor,instance,state) VALUES(?,?,?,?,'ACTIVE')",(task,fence,actor['id'],instance))
            return {'state':'RUNNING','taskId':task,'fence':fence,'instance':instance}

    def native_accept(self, token, command_id, value):
        fields(value,'owner sourceId goal inputHash model grantEpoch')
        owner=value['owner']; identifier(value['sourceId'])
        lock=ModelLock(**value['model']); lock.encoded()
        if type(value['grantEpoch']) is not int or value['grantEpoch']<1:
            raise ValueError('invalid_grant_epoch')
        with self.request(token,owner,roles=('device',)) as (db,actor):
            old=self.command(db,actor['id'],command_id,'native.accept',value)
            if old is not None:
                return old
            self.domain.require_active()
            task=self.store.enqueue(owner,'native:'+digest([actor['id'],value['sourceId']]),value['goal'],lock,[],[],{},require_evidence=False)
            db.execute("INSERT OR IGNORE INTO net_jobs(task_id,mode,source_id,actor,snapshot_json) VALUES(?,'native',?,?,?)",(task,value['sourceId'],actor['id'],canonical(value)))
            snapshot=db.execute('SELECT snapshot_json FROM net_jobs WHERE task_id=?',(task,)).fetchone()[0]
            if snapshot != canonical(value):
                raise Conflict('native_snapshot_changed')
            return self.acknowledge(db,actor['id'],command_id,{'taskId':task,'sourceId':value['sourceId']})

    def check(self, token, value):
        fields(value,'owner taskId fence instance')
        with self.request(token,value['owner']) as (db,actor):
            row=self._bound(db,actor,value['owner'],value['taskId'],value['fence'],value['instance'])
            return {'state':row['state'],'fence':row['fence'],'grantEpoch':row['auth_epoch']}

    def native_retire(self, token, value):
        fields(value,'owner taskId fence instance proof signature')
        fields(value['proof'],'sourceId sourceFence state journalHash')
        with self.request(token,value['owner'],roles=('device',),settlement=True) as (db,actor):
            task=value['taskId']; fence=value['fence']; instance=value['instance']
            binding=db.execute('SELECT * FROM net_instances WHERE task_id=? AND fence=?',(task,fence)).fetchone()
            if binding is None or binding['actor']!=actor['id'] or binding['instance']!=instance:
                raise Conflict('stale_executor_instance')
            message={k:value[k] for k in ('owner','taskId','fence','instance','proof')}
            # Sign the canonical structured payload, never a client-provided stopped boolean.
            public=serialization.load_pem_public_key(actor['public_key'].encode())
            public.verify(base64.b64decode(value['signature'],validate=True),canonical(message).encode(),padding.PKCS1v15(),hashes.SHA256())
            stop_hash=digest(message)
            if binding['state']=='STOPPED':
                if binding['stop_hash']!=stop_hash: raise Conflict('stop_receipt_changed')
                return {'state':'STOPPED','taskId':task,'fence':fence}
            self._bound(db,actor,value['owner'],task,fence,instance,dispatch=False)
            job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(task,)).fetchone()
            original=json.loads(job['snapshot_json'])
            if value['proof']['sourceId']!=original['sourceId'] or type(value['proof']['sourceFence']) is not int:
                raise Conflict('stop_receipt_not_bound')
            if value['proof']['state'] in ('RUNNING','STOP_REQUESTED','UNCERTAIN_SIDE_EFFECT'):
                raise UncertainEffect('native_journal_unsettled')
            if not isinstance(value['proof']['journalHash'],str) or len(value['proof']['journalHash'])!=64:
                raise ValueError('invalid_journal_hash')
            db.execute("UPDATE net_instances SET state='STOPPED',stop_hash=? WHERE task_id=? AND fence=?",(stop_hash,task,fence))
            # This task is only a native admission gate, never a claim of user-goal success.
            db.execute("UPDATE tasks SET state='CANCELED',revision=revision+1 WHERE id=?",(task,))
            TaskStore._release(db,task)
            return {'state':'STOPPED','taskId':task,'fence':fence}

    def retire_process(self, actor_id, instance, *, exit_code):
        """Called only after the owned Popen.wait; no RPC route exposes this method."""
        with self.store.transaction() as db:
            rows=db.execute("SELECT * FROM net_instances WHERE actor=? AND instance=? AND state!='STOPPED'",(actor_id,instance)).fetchall()
            for binding in rows:
                task=binding['task_id']
                db.execute("UPDATE net_instances SET state='STOPPED',stop_hash=? WHERE task_id=? AND fence=?",(digest(['process.wait',instance,exit_code]),task,binding['fence']))
                db.execute("UPDATE calls SET state=CASE WHEN effect='READ' THEN 'FAILED_SAFE' ELSE 'UNKNOWN' END,updated=? WHERE task_id=? AND state='IN_FLIGHT'",(time.time(),task))
                db.execute("UPDATE net_provider_calls SET state='UNKNOWN' WHERE task_id=? AND state='IN_FLIGHT'",(task,))
                row=db.execute('SELECT * FROM tasks WHERE id=?',(task,)).fetchone()
                unsafe=self.store._unsafe(db,task) or db.execute("SELECT 1 FROM net_provider_calls WHERE task_id=? AND state='UNKNOWN'",(task,)).fetchone()
                if unsafe:
                    db.execute("UPDATE tasks SET state='UNCERTAIN_SIDE_EFFECT',revision=revision+1 WHERE id=?",(task,))
                else:
                    if row['state'] not in TERMINAL:
                        db.execute('UPDATE tasks SET state=?,revision=revision+1 WHERE id=?',(self.store._stop_state(row) or 'FAILED_BLOCKED',task))
                    TaskStore._release(db,task)
            db.execute("UPDATE net_processes SET state='STOPPED' WHERE instance=? AND actor=?",(instance,actor_id))

    def task(self, token, value):
        fields(value,'owner taskId')
        with self.request(token,value['owner']) as (db,actor):
            if actor['role']=='worker' and self.worker_scope(actor)[0]!=value['taskId']:
                raise AuthorizationRequired('worker_task_scope_denied')
            row=self.store.get(value['owner'],value['taskId'])
            job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(value['taskId'],)).fetchone()
            if job is None or (actor['role']=='device' and job['actor']!=actor['id']):
                raise AuthorizationRequired('task_not_owned')
            snapshot=json.loads(job['snapshot_json'])
            children=list(db.execute("""SELECT t.* FROM tasks t JOIN net_jobs j ON j.task_id=t.id
                WHERE j.actor=? AND t.owner=? AND json_extract(j.snapshot_json,'$.validation.taskId')=?
                ORDER BY t.queue_order""",(job['actor'],row['owner'],row['id'])))
            scope=[row['id']]+[c['id'] for c in children]
            placeholders=','.join('?' for _ in scope)
            # No vault ciphertext, bearer, model secret, or signed stop key in UI snapshots.
            calls=[dict(c) for c in db.execute('SELECT call_id,tool,arguments_hash,state,reason,result_json,verification_json FROM calls WHERE task_id=?',(row['id'],))]
            usage=[]
            for item in db.execute("""SELECT u.request_id,u.provenance,u.input_tokens,u.output_tokens,u.cached_tokens,u.created,
                    p.role,p.state,p.result_json FROM usage u JOIN net_provider_calls p ON p.task_id=u.task_id AND p.id=u.request_id
                    WHERE u.task_id IN ("""+placeholders+""") ORDER BY u.created,u.request_id""",scope):
                observed=json.loads(item['result_json']) if item['result_json'] else {}
                reply=observed.get('result') or {};metadata=observed.get('metadata',{})
                usage.append({k:item[k] for k in ('request_id','provenance','input_tokens','output_tokens','cached_tokens','role')} |
                    {'createdAtMs':int(item['created']*1000),'settled':item['state']=='RETURNED',
                     'configurationHash':metadata.get('configurationHash'),'credentialRevision':metadata.get('credentialRevision'),
                     'total_tokens':reply.get('totalTokens'),'costUsd':reply.get('costUsd'),'costSource':reply.get('costSource')})
            return {'taskId':row['id'],'sourceId':job['source_id'],'owner':row['owner'],'state':row['state'],
                    'authorityDomain':self.domain.id,
                    'credentialValidations':[{'taskId':c['id'],'state':c['state'],
                        'result':json.loads(c['result_json']) if c['result_json'] else None,
                        'executorActive':bool(db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(c['id'],)).fetchone())}
                        for c in children],
                    'revision':row['revision'],'fence':row['fence'],'model':json.loads(row['model_json']),
                    'goal':row['goal'],'goalRevision':job['goal_revision'],'plannedRevision':job['planned_revision'],
                    'reason':row['reason'],
                    'documentCalls':[{'callId':d['call_id'],'requestId':d['request_key'],'state':d['state'],'remoteTaskId':d['remote_id'],
                        'operation':json.loads(d['options_json']).get('operation','parse'),'role':'document_service',
                        'usageProvenance':'unknown','costUsd':None}
                        for d in db.execute('SELECT * FROM net_document_calls WHERE task_id=?',(row['id'],))],
                    'grantExpiresAt':snapshot.get('grant',{}).get('expiresAt'),
                    'grantMode':snapshot.get('grant',{}).get('mode','UNTIL'),
                    'documentService':None if not snapshot.get('documentService') else {
                        k:snapshot['documentService'][k] for k in ('endpoint','configurationHash')},
                    'sources':snapshot.get('sources',[]),
                    'authorityRetired':self.domain.status()['retired'],
                    'executorActive':bool(db.execute("SELECT 1 FROM net_instances WHERE task_id=? AND state!='STOPPED'",(row['id'],)).fetchone()),
                    'retiredStepIds':[r[0] for r in db.execute("SELECT id FROM net_device_calls WHERE task_id=? AND state='CANCELED'",(row['id'],))],
                    'manualPaused':bool(row['manual_pause']),'authorization':row['authorization'],
                    'grantEpoch':row['auth_epoch'],'inputBlocked':bool(job['input_blocked']),
                    'deviceBlocked':bool(job['device_blocked']),'deviceConfirmation':bool(job['device_confirmation']),
                    'result':json.loads(row['result_json']) if row['result_json'] else None,'calls':calls,
                    'usage':usage}
