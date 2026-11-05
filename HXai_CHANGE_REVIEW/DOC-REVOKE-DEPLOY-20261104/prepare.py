"""Prepare a minimal deployment from exact existing source; never edit product files."""
import ast
import asyncio
import difflib
import hashlib
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SSH = ['ssh', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'ConnectTimeout=15', '-i', r'C:\Users\Hfff\.ssh\id_ed25519_tencent_blog',
       'ubuntu@154.222.25.150']
EXPECTED = {
    'api/main.py': ('5f0277f8cb9c5b5ef9b63f7390976c2d268e682022fea39badefb75b94dd5dba', '89f3e3b0eaa95542760f75a9d9da9e2d6541e530c118e0ee1e6b4ae079e5ea4f'),
    'api/store.py': ('42b65697d49c13d90cc9f78bbc9af64cbd6f0d0bda0e9d2c07cd3d960caae37b', '2a5e7de3ad7ccbe4a29c2bb19131ebf414b9a99b8f0b88854a475baa70ebcf56'),
    'dispatcher/main.py': ('feda492ab26199f184f3356d1a8414daecc64f51d7ff322890583fdc647fe4d8', '1b63597bd28f31c8060b4b2cacd47d49fe009b0c20546a1a1cab540c6c84acb2'),
}

def digest(b):
    return hashlib.sha256(b).hexdigest()

def remote_read(rel):
    return subprocess.run(SSH + ['sudo -n cat /opt/mathdoc/service/' + rel], capture_output=True, check=True).stdout

def piece(text, name, parent=None):
    nodes = ast.parse(text).body
    if parent:
        nodes = next(n for n in nodes if getattr(n, 'name', None) == parent).body
    node = next(n for n in nodes if getattr(n, 'name', None) == name)
    first = min([node.lineno] + [n.lineno for n in getattr(node, 'decorator_list', [])])
    return ''.join(text.splitlines(True)[first - 1:node.end_lineno])

def replace_once(text, old, new):
    assert text.count(old) == 1, 'source anchor mismatch'
    return text.replace(old, new, 1)

def build():
    online, repo = {}, {}
    for rel, (before, source) in EXPECTED.items():
        online[rel] = remote_read(rel)
        repo[rel] = (ROOT / 'server/document-service' / rel).read_bytes()
        assert digest(online[rel]) == before, 'online source drift'
        assert digest(repo[rel]) == source, 'repository source drift'
    main = online['api/main.py'].decode()
    current_main = repo['api/main.py'].decode()
    main = replace_once(main, 'from fastapi.responses import FileResponse\n', 'from fastapi.responses import FileResponse, JSONResponse\n')
    main = replace_once(main, 'from api.store import Store\n', 'from api.store import Store, AuthorizationRevoked\n')
    handler = piece(current_main, 'revoked_after_authentication', 'create_app')
    main = replace_once(main, '    app.state.store = store\n', '    app.state.store = store\n\n' + handler + '\n')
    route = piece(current_main, 'revoke_self', 'create_app')
    main = replace_once(main, '    @app.get("/api/v1/capabilities")\n', route + '\n\n    @app.get("/api/v1/capabilities")\n')
    store = online['api/store.py'].decode()
    current_store = repo['api/store.py'].decode()
    store = replace_once(store, '\n\nclass Store:\n', '\n\n' + piece(current_store, 'AuthorizationRevoked') + '\n\n\nclass Store:\n')
    old_auth = piece(store, 'authenticate', 'Store')
    names = ['authenticate', 'token_owner', 'revoke', '_fence_revoked_jobs']
    store = replace_once(store, old_auth, '\n\n'.join(piece(current_store, n, 'Store').rstrip('\n') for n in names) + '\n')
    old_enqueue = piece(store, 'enqueue', 'Store')
    guard = '''                # Initial authentication before a slow upload is not a dispatch grant.
                if not db.execute("SELECT 1 FROM identities WHERE id=? AND active=1", (owner,)).fetchone():
                    raise AuthorizationRevoked()
'''
    assert guard in current_store
    new_enqueue = replace_once(old_enqueue, '                db.execute("BEGIN IMMEDIATE")\n', '                db.execute("BEGIN IMMEDIATE")\n' + guard)
    store = replace_once(store, old_enqueue, new_enqueue)
    old_query = '            row = db.execute("SELECT * FROM jobs WHERE state=\'QUEUED\' AND cancel=0 ORDER BY created LIMIT 1").fetchone()\n'
    new_query = '''            self._fence_revoked_jobs(db, time.time())
            row = db.execute("""SELECT jobs.* FROM jobs JOIN identities ON identities.id=jobs.owner
                                WHERE jobs.state='QUEUED' AND jobs.cancel=0 AND identities.active=1
                                ORDER BY jobs.created,jobs.id LIMIT 1""").fetchone()
'''
    assert new_query in current_store
    store = replace_once(store, old_query, new_query)
    store = replace_once(store, piece(store, 'finish', 'Store'), piece(current_store, 'finish', 'Store'))
    candidates = {'api/main.py': main.encode(), 'api/store.py': store.encode()}
    for rel, data in candidates.items():
        compile(data, rel, 'exec')
    patch = ''.join(''.join(difflib.unified_diff(online[rel].decode().splitlines(True), data.decode().splitlines(True),
                       fromfile='online/' + rel, tofile='deployed/' + rel)) for rel, data in candidates.items())
    manifest = {'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'files': {rel: {'before': digest(online[rel]), 'after': digest(candidates.get(rel, online[rel])),
                                 'repository': digest(repo[rel]), 'replace': rel in candidates} for rel in online},
                'patch_sha256': digest(patch.encode())}
    return online, candidates, patch, manifest

async def http_checks(main, data_root):
    import httpx
    app = main.create_app(data_root)
    store = app.state.store
    owner, token = store.issue()
    other, other_token = store.issue()
    (data_root / 'worker-ready.json').write_text('{}')
    payload = b'%PDF-1.4\nSYNTHETIC\n'
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test.invalid') as client:
        for headers in [{}, {'Authorization': 'Bearer deliberately-invalid-synthetic'}]:
            r = await client.post('/api/v1/identity/revoke', headers=headers)
            assert r.status_code == 401 and r.json() == {'detail': 'authentication_required'}
        r = await client.post('/api/v1/jobs', content=payload, headers={'Authorization': 'Bearer '+token,
            'Idempotency-Key':'synthetic-revoke-00001', 'X-Source-Sha256':digest(payload)})
        assert r.status_code == 202 and r.json()['state'] == 'QUEUED'
        job = r.json()['taskId']
        assert store.claim()['id'] == job
        r = await client.post('/api/v1/identity/revoke', headers={'Authorization':'Bearer '+token})
        assert r.status_code == 200 and r.json() == {'revoked': True}
        assert owner not in r.text and token not in r.text
        r = await client.get('/api/v1/capabilities', headers={'Authorization':'Bearer '+token})
        assert r.status_code == 401
        r = await client.post('/api/v1/identity/revoke', headers={'Authorization':'Bearer '+token})
        assert r.status_code == 200 and r.json() == {'revoked':True}
        r = await client.get('/api/v1/capabilities', headers={'Authorization':'Bearer '+other_token})
        assert r.status_code == 200 and store.authenticate(other_token) == other
    store.finish(job, 'SUCCEEDED', {'assets': []})
    row = store.get(owner, job)
    assert row['state'] == 'CANCELLED' and row['error'] == 'authorization_revoked' and row['manifest'] is None
    assert (data_root / 'jobs' / job / 'input/source.pdf').read_bytes() == payload
    source = data_root / 'uploads' / str(uuid.uuid4())
    source.write_bytes(payload)
    try:
        store.enqueue(owner, 'synthetic-revoke-00001', 'f', {'extension':'pdf'}, source)
    except main.AuthorizationRevoked:
        pass
    else:
        raise AssertionError('revoked old receipt accepted')
    reopened = main.Store(data_root)
    assert reopened.claim() is None and reopened.get(owner, job)['state'] == 'CANCELLED'
    with reopened.db() as db:
        assert {r['name'] for r in db.execute('PRAGMA table_info(jobs)')} == {
            'id','owner','request_key','fingerprint','options','state','created','updated','expires','error','manifest','cancel'}

def main():
    run = Path(os.environ['HXAI_RUN_DIR'])
    online, candidates, patch, manifest = build()
    for rel, content in dict(candidates, **{'api/office.py':remote_read('api/office.py')}).items():
        path = run / 'candidate' / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    (run / 'candidate/api/__init__.py').write_text('')
    sys.path.insert(0, str(run / 'candidate'))
    mod = importlib.import_module('api.main')
    asyncio.run(http_checks(mod, run / 'synthetic-state'))
    selected = [
        'test_active_idempotent_receipt_is_preserved',
        'test_old_receipt_cannot_be_returned_after_revocation',
        'test_late_finish_does_not_resurrect_revoked_task',
        'test_legacy_raw_sql_revoke_is_also_fenced_at_finish',
        'test_cancel_receipt_remains_idempotent_for_active_identity',
        'test_owner_isolation_and_revoke_idempotency',
        'test_queue_limit_is_not_removed_by_cost_budget_decision',
        'test_cross_owner_http_status_is_404',
        'test_revoke_claim_race_is_linearizable',
        'test_reopening_database_preserves_state_and_source',
        'test_self_revoke_is_idempotent_and_does_not_leak_identity',
    ]
    environment = dict(os.environ, PYTHONPATH=str(run / 'candidate'), PYTHONDONTWRITEBYTECODE='1')
    regression = subprocess.run([sys.executable, '-B', '-m', 'pytest', '-q', '-p', 'no:cacheprovider',
        '--basetemp', str(run / 'pytest'), str(ROOT / 'server/document-service/tests/test_revocation_fence.py'),
        '-k', ' or '.join(selected)], env=environment, capture_output=True, text=True)
    (HERE / 'REGRESSION.log').write_text(regression.stdout + regression.stderr, encoding='utf-8')
    print(regression.stdout, end='')
    assert regression.returncode == 0, 'minimal candidate regression failed'
    (HERE / 'FIXES.patch').write_text(patch, encoding='utf-8', newline='\n')
    (HERE / 'SOURCE_MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n')
    result = {'status':'PASS', 'scope':'minimal candidate ASGI + SQLite, synthetic only',
              'schema_unchanged':True, 'dispatcher_unchanged':True, 'existing_regression_cases':len(selected),
              'patch_sha256':manifest['patch_sha256']}
    (HERE / 'LOCAL_TEST_RESULTS.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result))

if __name__ == '__main__':
    main()
