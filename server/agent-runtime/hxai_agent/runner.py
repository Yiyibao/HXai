"""Journaled executor with trusted LOCAL tool adapters, not a language-model Agent.

Directory ownership is the sandbox boundary. The local account controlling these
private directories is trusted; this does not sandbox arbitrary OS processes.
"""
from __future__ import annotations
import contextlib
import hashlib
import json
import os
import re
import stat
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from .math_verify import first_error
from .store import Conflict, Lease, ModelLock, Receipt, TaskStore, canonical, digest

if os.name == 'nt':
    import ctypes
    from ctypes import wintypes
    _move_file_ex = ctypes.WinDLL('kernel32', use_last_error=True).MoveFileExW
    _move_file_ex.argtypes = (wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD)
    _move_file_ex.restype = wintypes.BOOL


def _is_reparse_point(path: Path):
    if os.name != 'nt': return False
    try: attributes = path.lstat().st_file_attributes
    except FileNotFoundError: return False
    return bool(attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT)


def _reject_reparse_traversal(path: Path):
    if os.name != 'nt': return
    # Check ancestors before resolving: resolve() would hide a junction's origin.
    for parent in reversed(path.parents):
        if _is_reparse_point(parent): raise ValueError('reparse_point_not_allowed')
    if _is_reparse_point(path): raise ValueError('reparse_point_not_allowed')


def _publish_immutable(pending, target: Path):
    if os.name == 'nt':
        # Same directory/volume; neither replacement nor copy-delete is allowed.
        # WRITE_THROUGH is not evidence of hardware power-loss durability.
        if not _move_file_ex(str(pending), str(target), 8):
            raise ctypes.WinError(ctypes.get_last_error())
    else:
        os.link(pending, target)


@dataclass(frozen=True)
class Tool:
    capability: str
    effect: str
    validate: Callable[[dict], None]
    execute: Callable[[Lease, str, dict], dict]
    verify: Callable[[Lease, str, dict, dict], bool]


class LocalFiles:
    """Managed synthetic/plain-text fixtures only; not a second production library."""
    def __init__(self, root: Path):
        root = Path(root).absolute()
        _reject_reparse_traversal(root)
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        _reject_reparse_traversal(self.root)

    def directory(self, owner, category):
        if category not in {'materials', 'artifacts'}: raise ValueError('invalid_category')
        _reject_reparse_traversal(self.root)
        path = self.root
        for segment in (hashlib.sha256(owner.encode()).hexdigest(), category):
            path = path / segment
            if _is_reparse_point(path): raise ValueError('reparse_point_not_allowed')
            if path.is_symlink(): raise ValueError('symlink_not_allowed')
            path.mkdir(exist_ok=True, mode=0o700)
            if _is_reparse_point(path): raise ValueError('reparse_point_not_allowed')
            if path.resolve() != path: raise ValueError('path_not_owned')
        return path

    @staticmethod
    def material_id(value):
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', value):
            raise ValueError('invalid_material_id')

    def import_synthetic_text(self, owner, material_id, text):
        self.material_id(material_id)
        path = self.directory(owner, 'materials') / (material_id + '.txt')
        with path.open('xb') as out:
            out.write(text.encode()); out.flush(); os.fsync(out.fileno())
        return path

    @staticmethod
    def validate_read(args):
        if set(args) != {'material_id'}: raise ValueError('invalid_read_arguments')
        LocalFiles.material_id(args['material_id'])

    def read(self, lease, call, args):
        path = self.directory(lease.owner, 'materials') / (args['material_id'] + '.txt')
        if path.is_symlink() or _is_reparse_point(path) or not path.is_file() or path.stat().st_size > 16000: raise ValueError('invalid_or_large_material')
        fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        with os.fdopen(fd, 'rb') as source: data = source.read(16001)
        if len(data) > 16000: raise ValueError('material_too_large')
        return {'material_id': args['material_id'], 'text': data.decode('utf-8'),
                'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data),
                'coverage': 'complete_utf8_plain_text_only'}

    @staticmethod
    def validate_write(args):
        if set(args) != {'family', 'content', 'parent_artifact_id'}: raise ValueError('invalid_write_arguments')
        LocalFiles.material_id(args['family'])
        if not isinstance(args['content'], str) or len(args['content'].encode()) > 32000: raise ValueError('report_too_large')
        parent = args['parent_artifact_id']
        if parent is not None and (not isinstance(parent, str) or str(uuid.UUID(parent)) != parent): raise ValueError('invalid_parent_artifact')

    def report_result(self, lease, call, args):
        data = args['content'].encode()
        artifact = str(uuid.uuid5(uuid.NAMESPACE_URL, canonical([lease.task_id, call, digest(args)])))
        return {'artifact_id': artifact, 'family': args['family'], 'parent_artifact_id': args['parent_artifact_id'],
                'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data),
                'media_type': 'text/markdown; charset=utf-8', 'relative_path': artifact + '.md'}

    def write(self, lease, call, args):
        result = self.report_result(lease, call, args)
        folder = self.directory(lease.owner, 'artifacts')
        parent = args['parent_artifact_id']
        if parent is not None:
            previous = folder / (parent + '.md')
            if previous.is_symlink() or _is_reparse_point(previous) or not previous.is_file(): raise ValueError('parent_artifact_not_owned')
        target = folder / result['relative_path']
        if target.exists() or target.is_symlink() or _is_reparse_point(target):
            if not self.verify_report(lease, call, args, result): raise ValueError('immutable_artifact_conflict')
            return result
        fd, pending = tempfile.mkstemp(prefix='.pending-', dir=folder)
        try:
            with os.fdopen(fd, 'wb') as out:
                out.write(args['content'].encode()); out.flush(); os.fsync(out.fileno())
            try:
                _publish_immutable(pending, target)
            except FileExistsError:
                if not self.verify_report(lease, call, args, result): raise ValueError('immutable_artifact_conflict')
            if os.name != 'nt':
                directory_fd = os.open(folder, os.O_RDONLY)
                try: os.fsync(directory_fd)
                finally: os.close(directory_fd)
        finally:
            Path(pending).unlink(missing_ok=True)
        return result

    def verify_report(self, lease, call, args, result):
        if result != self.report_result(lease, call, args): return False
        path = self.directory(lease.owner, 'artifacts') / result['relative_path']
        if path.is_symlink() or _is_reparse_point(path) or not path.is_file() or path.stat().st_size != result['bytes']: return False
        fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        with os.fdopen(fd, 'rb') as source: data = source.read(32001)
        return len(data) == result['bytes'] and hashlib.sha256(data).hexdigest() == result['sha256']


def registry(files: LocalFiles):
    def validate_chain(args):
        if set(args) != {'expressions'}: raise ValueError('invalid_math_arguments')
        first_error(args['expressions'])  # Bounded schema validation, no effects.
    return {
        'read_material_text': Tool('files.read', 'READ', files.validate_read, files.read,
                                  lambda lease, call, args, result: files.read(lease, call, args) == result),
        'write_report_version': Tool('files.write_version', 'VERSIONED_WRITE', files.validate_write, files.write, files.verify_report),
        'verify_equal_expression_chain': Tool('math.exact_polynomial', 'READ', validate_chain,
                                             lambda lease, call, args: first_error(args['expressions']),
                                             lambda lease, call, args, result: result == first_error(args['expressions'])),
    }


class Runner:
    def __init__(self, store: TaskStore, tools: dict[str, Tool]): self.store, self.tools = store, tools

    def call(self, lease, call, tool_name, args, *, fault=None):
        tool = self.tools[tool_name]  # Trusted registry chooses effect and verifier, not the model.
        tool.validate(args)
        task = self.store.get(lease.owner, lease.task_id)
        model = ModelLock(**json.loads(task['model_json']))
        prepared = self.store.prepare_call(lease, call, tool_name, args, tool.capability, tool.effect, model)
        if isinstance(prepared, Receipt): return prepared
        fault = fault or (lambda stage: None)
        fault('after_prepare')
        self.store.dispatch(prepared)
        try:
            fault('after_dispatch')
            result = tool.execute(lease, call, args)
            fault('after_execute')
            self.store.begin_verification(prepared)
            if not tool.verify(lease, call, args, result): raise ValueError('result_verification_failed')
            proof = {'verified': True, 'method': tool_name + '.readback', 'result_sha256': digest(result)}
            fault('after_verify')
            self.store.commit_result(prepared, result, proof)
        except Exception:
            # A commit may be durable even though its acknowledgement was lost.
            durable = self.store.get_call(lease.owner, lease.task_id, call)
            if durable['state'] == 'SUCCEEDED':
                return Receipt(call, json.loads(durable['result_json']), json.loads(durable['verification_json']))
            with contextlib.suppress(Conflict):
                self.store.fail_call(prepared, safe_local_read=tool.effect == 'READ')
            raise
        # BaseException/process death leaves IN_FLIGHT for startup reconciliation.
        fault('after_commit')
        return Receipt(call, result, proof)

    def reconcile_report(self, owner, task, call, files, *, executor_stopped):
        stored = self.store.get_call(owner, task, call)
        if stored['tool'] != 'write_report_version' or stored['state'] != 'UNKNOWN': raise ValueError('not_an_unknown_report')
        lease = Lease(task, owner, stored['fence']); args = json.loads(stored['arguments_json'])
        result = files.report_result(lease, call, args)
        if not files.verify_report(lease, call, args, result): raise ValueError('artifact_not_verified_do_not_replay')
        proof = {'verified': True, 'method': 'independent_recovery_readback', 'result_sha256': digest(result)}
        self.store.reconcile_applied(owner, task, call, result, proof, executor_stopped=executor_stopped)
        return result
