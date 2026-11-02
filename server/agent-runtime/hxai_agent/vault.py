"""Task-scoped AES-GCM envelopes. The wrapping key is an operator-mounted private file.

This implements the file-key backend, not a claim of hardware KMS. The key is never
stored in the task database, protocol snapshots, command arguments or logs.
"""
from __future__ import annotations
import base64
import json
import os
import stat
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from .runner import _reject_reparse_traversal
from .store import canonical


class TaskVault:
    def __init__(self, key_file: Path):
        path=Path(key_file).absolute()
        _reject_reparse_traversal(path)
        if path.is_symlink() or not path.is_file() or path.stat().st_size!=32:
            raise ValueError('invalid_vault_key_file')
        if os.name!='nt' and stat.S_IMODE(path.stat().st_mode)&0o077:
            raise PermissionError('vault_key_permissions')
        self._cipher=AESGCM(path.read_bytes())

    @staticmethod
    def initialize(path):
        path=Path(path).absolute()
        path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
        _reject_reparse_traversal(path)
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as stream:
            stream.write(os.urandom(32));stream.flush();os.fsync(stream.fileno())

    def seal(self, binding, configuration):
        nonce=os.urandom(12)
        ciphertext=self._cipher.encrypt(nonce,canonical(configuration).encode(),canonical(binding).encode())
        return base64.b64encode(nonce+ciphertext).decode('ascii')

    def open(self, binding, envelope):
        raw=base64.b64decode(envelope,validate=True)
        if len(raw)>131072 or len(raw)<29:
            raise ValueError('invalid_vault_envelope')
        return json.loads(self._cipher.decrypt(raw[:12],raw[12:],canonical(binding).encode()))

    def __repr__(self):
        return 'TaskVault([redacted])'
