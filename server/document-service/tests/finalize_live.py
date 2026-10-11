"""Rotate synthetic test credentials or finalize deployment; never print secrets."""
import json
import os
import pwd
import sys
from pathlib import Path
from api.store import Store

store = Store(Path('/var/lib/mathdoc'))
file = Path('/tmp/mathdoc-private/live-test.json')
if file.exists():
    old = json.loads(file.read_text())
    with store.db() as db:
        db.execute('UPDATE identities SET active=0 WHERE id=?', (old['identity'],))
    file.unlink()
if sys.argv[1] == 'rotate':
    owner, token = store.issue()
    fd = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump({'identity': owner, 'baseUrl': 'https://hxnf.top/documents', 'token': token}, stream)
    user = pwd.getpwnam('ubuntu')
    os.chown(file, user.pw_uid, user.pw_gid)
else:
    from api.admin import main
    directory = Path('/var/lib/mathdoc-admin/credentials')
    directory.parent.mkdir(mode=0o700, exist_ok=True)
    directory.mkdir(mode=0o700, exist_ok=True)
    for name in ['learner-1.json', 'learner-2.json']:
        target = directory / name
        if not target.exists():
            sys.argv = ['admin', 'issue', '--output', str(target)]
            main()
    (store.root / 'worker-ready.json').write_text(json.dumps({
        'ready': True, 'tier': 'basic', 'mineru': '4.0.10',
        'validation': 'synthetic-pdf-png-jpeg-webp-lifecycle-isolation-android',
    }))
print('credential_operation_complete')
