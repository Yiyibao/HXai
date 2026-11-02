"""Operator-owned immutable authority identity and one-way retirement witness.

Changing a client's local switch is not revocation of other registered executors.
Retirement therefore blocks future admissions on the old authority, and is permitted
only after its original journals and OS-supervised bindings are actually quiescent.
Both immutable files are published while holding the same SQLite writer transaction.
"""
import json
import os
from pathlib import Path
import tempfile
import time
import uuid
from .runner import _publish_immutable, _reject_reparse_traversal
from .store import Conflict, canonical


class DomainRetired(Conflict):
    pass


class WorkspaceDomain:
    def __init__(self,store):
        self.store=store
        self.identity=store.path.parent/'authority.identity.v1.json'
        self.retirement=store.path.parent/'authority.retired.v1.json'
        with store.transaction():
            if not self.identity.exists():self._publish(self.identity,{'version':1,'domain':str(uuid.uuid4())})
            value=self._read(self.identity)
            if set(value)!={'version','domain'} or value['version']!=1 or str(uuid.UUID(value['domain']))!=value['domain']:
                raise ValueError('invalid_authority_identity')
            self.id=value['domain']

    @staticmethod
    def _read(path):
        _reject_reparse_traversal(path)
        if path.is_symlink() or path.stat().st_size>16000:raise ValueError('invalid_authority_witness')
        return json.loads(path.read_bytes().decode('utf-8'))

    @staticmethod
    def _publish(path,value):
        _reject_reparse_traversal(path)
        fd,name=tempfile.mkstemp(prefix='.authority-witness-',dir=path.parent)
        pending=Path(name)
        try:
            with os.fdopen(fd,'wb') as stream:
                stream.write(canonical(value).encode());stream.flush();os.fsync(stream.fileno())
            _publish_immutable(pending,path)
        finally:pending.unlink(missing_ok=True)

    def require_active(self):
        _reject_reparse_traversal(self.retirement)
        if self.retirement.exists():raise DomainRetired('authority_retired_no_new_dispatch')

    def status(self):
        with self.store.transaction() as db:
            if self.retirement.exists():
                value=self._read(self.retirement)
                if value.get('version')!=1 or value.get('domain')!=self.id or value.get('state')!='RETIRED':
                    raise ValueError('invalid_authority_retirement')
                return dict(value,retired=True)
            return {'version':1,'domain':self.id,'state':'ACTIVE','retired':False,
                    'fence':db.execute('SELECT fence FROM execution_slot WHERE singleton=1').fetchone()[0]}

    def retire(self):
        """Local operator only. No public RPC performs this irreversible operation."""
        with self.store.transaction() as db:
            if self.retirement.exists():return self.status()
            if (db.execute('SELECT task_id FROM execution_slot WHERE singleton=1').fetchone()[0] is not None
                    or db.execute("SELECT 1 FROM tasks WHERE state NOT IN ('SUCCEEDED','CANCELED')").fetchone()
                    or db.execute("SELECT 1 FROM net_instances WHERE state!='STOPPED'").fetchone()
                    or db.execute("SELECT 1 FROM net_processes WHERE state!='STOPPED'").fetchone()
                    or db.execute("SELECT 1 FROM calls WHERE state IN ('IN_FLIGHT','UNKNOWN')").fetchone()
                    or db.execute("SELECT 1 FROM net_provider_calls WHERE state IN ('IN_FLIGHT','UNKNOWN')").fetchone()):
                raise Conflict('all_tasks_and_actual_executors_must_finish_before_retirement')
            value={'version':1,'domain':self.id,'state':'RETIRED','retiredAt':time.time(),
                   'fence':db.execute('SELECT fence FROM execution_slot WHERE singleton=1').fetchone()[0]}
            self._publish(self.retirement,value)
            return dict(value,retired=True)
