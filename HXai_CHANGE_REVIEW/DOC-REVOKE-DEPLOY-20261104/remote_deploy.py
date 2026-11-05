"""One authorized deployment, executed through SSH stdin as root; no secrets logged."""
import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import sqlite3
import stat
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib

# PAYLOAD_INJECTION

SERVICE = Path('/opt/mathdoc/service')
DATA = Path('/var/lib/mathdoc')
PYTHON = '/opt/mathdoc/api-env/bin/python'
PUBLIC = 'https://hxnf.top/documents'
stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
backup = Path('/opt/mathdoc') / ('revoke-backup-' + stamp)
credential = Path('/var/lib/mathdoc-admin/credentials') / ('revoke-smoke-' + stamp + '.json')
result = {'host': socket.gethostname(), 'target': 'ubuntu@154.222.25.150', 'utc': stamp,
          'files': PAYLOAD['manifest']['files'], 'backup_directory': None}
stage = 'initial'
replaced = False
api_stopped = False
dispatcher_stopped = False
synthetic_owner = None
job_id = None
credential_issued = False
temporary_files = []

def emit(event, **fields):
    print(json.dumps({'event': event, **fields}, sort_keys=True), flush=True)

def require(condition, code):
    if not condition:
        raise RuntimeError(code)

def cmd(args, check=True, timeout=35):
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check:
        require(p.returncode == 0, 'command_failed')
    return p

def sha(data):
    return hashlib.sha256(data).hexdigest()

def readonly(sql, params=()):
    c = sqlite3.connect('file:/var/lib/mathdoc/tasks.sqlite3?mode=ro', uri=True, timeout=15)
    c.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in c.execute(sql, params)]
    finally:
        c.close()

def containers():
    p = cmd(['/usr/bin/docker', 'ps', '-a', '--format', '{{.Names}} {{.State}}'])
    return {parts[0]:parts[1] for line in p.stdout.splitlines()
            if len(parts := line.split()) == 2 and parts[0].startswith('mathdoc-')}

def idle(label, enforce=True):
    counts = {r['state']:r['n'] for r in readonly("SELECT state,count(*) n FROM jobs WHERE state IN ('QUEUED','RUNNING') GROUP BY state")}
    present = containers()
    evidence = {'QUEUED':counts.get('QUEUED', 0), 'RUNNING':counts.get('RUNNING', 0), 'task_containers':len(present)}
    result.setdefault('idle_evidence', {})[label] = evidence
    emit('idle_gate', gate=label, **evidence)
    if enforce:
        require(not sum(counts.values()) and not present, 'queue_or_container_busy')

def active(unit):
    return cmd(['systemctl', 'is-active', unit], check=False).stdout.strip() == 'active'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

def http(method, url, token=None, data=None, extra=None):
    headers = dict(extra or {})
    if token is not None:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(url, method=method, headers=headers, data=data)
    try:
        response = opener.open(request, timeout=20)
    except urllib.error.HTTPError as e:
        response = e
    with response:
        body = response.read(65537)
        require(len(body) <= 65536, 'response_limit')
        return response.code, json.loads(body)

def health():
    for _ in range(30):
        try:
            code, body = http('GET', 'http://127.0.0.1:8766/health')
            if code == 200 and body.get('status') == 'ok':
                return
        except Exception:
            pass
        time.sleep(0.5)
    raise RuntimeError('health_not_ready')

def write_new(path, data, mode=0o600):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(descriptor, 'wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    require(stat.S_IMODE(path.stat().st_mode) == mode and not path.is_symlink(), 'file_mode')

def replace_source(rel, data):
    target = SERVICE / rel
    require(not target.is_symlink(), 'source_symlink')
    before = target.stat()
    temp = target.with_name('.' + target.name + '.revoke-' + stamp)
    temporary_files.append(temp)
    write_new(temp, data)
    os.chown(temp, before.st_uid, before.st_gid)
    os.chmod(temp, stat.S_IMODE(before.st_mode))
    os.replace(temp, target)
    descriptor = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    require(sha(target.read_bytes()) == sha(data), 'replace_readback_failed')

def hash_gate(which):
    for rel, expected in result['files'].items():
        require(sha((SERVICE / rel).read_bytes()) == expected[which], 'source_drift')

def job_row():
    rows = readonly('SELECT state,error,cancel FROM jobs WHERE id=? AND owner=?', (job_id, synthetic_owner))
    require(len(rows) == 1, 'synthetic_task_missing')
    return rows[0]

def synthetic_containers():
    if job_id is None:
        return {}
    names = {'mathdoc-' + job_id, 'mathdoc-' + job_id + '-validate'}
    return {n:s for n,s in containers().items() if n in names}

def await_synthetic_stop():
    for _ in range(60):
        if not synthetic_containers():
            return
        time.sleep(0.5)
    raise RuntimeError('synthetic_worker_stop_unconfirmed')

def admin_cleanup(cancel_on_failure=False):
    global synthetic_owner
    if not credential_issued or result.get('credential_removed'):
        return
    require(credential.is_file() and not credential.is_symlink(), 'credential_missing')
    value = json.loads(credential.read_text())
    owner = value['identity']
    require(synthetic_owner in (None, owner), 'credential_identity_mismatch')
    synthetic_owner = owner
    require(str(uuid.UUID(owner)) == owner, 'invalid_synthetic_identity')
    cmd([PYTHON, '-B', '-m', 'api.admin', 'revoke', '--identity', owner])
    if cancel_on_failure:
        # The existing administrator CLI only disables active. The deployed Store
        # transaction also cancels this exact synthetic owner's pending work.
        sys.path.insert(0, str(SERVICE))
        from api.store import Store
        store = Store(DATA)
        if hasattr(store, 'revoke'):
            store.revoke(owner)
    rows = readonly('SELECT active FROM identities WHERE id=?', (owner,))
    require(len(rows) == 1 and rows[0]['active'] == 0, 'cleanup_revoke_unconfirmed')
    await_synthetic_stop()
    credential.unlink()
    require(not credential.exists(), 'credential_delete_unconfirmed')
    result['temporary_identity_revoked'] = True
    result['credential_removed'] = True
    emit('temporary_credential_cleanup', admin_revoked=True, removed=True)

def rollback():
    global api_stopped, dispatcher_stopped
    if replaced:
        idle('rollback_before_stop')
        for rel in PAYLOAD['candidates']:
            require(sha((SERVICE / rel).read_bytes()) in
                    (result['files'][rel]['before'], result['files'][rel]['after']), 'rollback_source_drift')
        cmd(['systemctl', 'stop', 'mathdoc-api'])
        api_stopped = True
        idle('rollback_after_api_stop')
        cmd(['systemctl', 'stop', 'mathdoc-dispatcher'])
        dispatcher_stopped = True
        for rel in PAYLOAD['candidates']:
            original = (backup / rel).read_bytes()
            require(sha(original) == result['files'][rel]['before'], 'backup_hash_mismatch')
            replace_source(rel, original)
        hash_gate('before')
    if dispatcher_stopped:
        cmd(['systemctl', 'start', 'mathdoc-dispatcher'])
        dispatcher_stopped = False
    if api_stopped:
        cmd(['systemctl', 'start', 'mathdoc-api'])
        api_stopped = False
    require(active('mathdoc-api') and active('mathdoc-dispatcher'), 'rollback_unit_inactive')
    health()
    result['rollback'] = 'RESTORED' if replaced else 'NO_SOURCE_REPLACED'
    emit('rollback', status=result['rollback'], backup_directory=result['backup_directory'])

def interrupt(_signal, _frame):
    raise RuntimeError('execution_interrupted')

for number in (signal.SIGHUP, signal.SIGTERM, signal.SIGINT):
    signal.signal(number, interrupt)

try:
    stage = '1_host_identity'
    require(os.geteuid() == 0 and socket.gethostname() == 'ecsLWs9K', 'wrong_host')
    require(SERVICE.is_dir() and not SERVICE.is_symlink() and SERVICE.resolve() == SERVICE, 'wrong_service_directory')
    os.chdir(SERVICE)
    for unit in ('mathdoc-api', 'mathdoc-dispatcher'):
        require(cmd(['systemctl', 'show', unit, '-p', 'LoadState', '--value']).stdout.strip() == 'loaded' and active(unit), 'unit_not_loaded_active')
    health()
    listening = cmd(['ss', '-ltnH', 'sport = :8766']).stdout.splitlines()
    require(len(listening) == 1 and listening[0].split()[3] == '127.0.0.1:8766', 'not_loopback_only')
    hash_gate('before')
    emit('host_proved', host=result['host'], service_directory=str(SERVICE), api_active=True, dispatcher_active=True, health_ok=True, listener='127.0.0.1:8766')
    stage = '2_idle_gate'
    idle('before_backup')
    stage = '3_source_backup'
    backup.mkdir(mode=0o700)
    result['backup_directory'] = str(backup)
    for rel in PAYLOAD['candidates']:
        source = SERVICE / rel
        require(source.is_file() and not source.is_symlink(), 'invalid_source')
        original = source.read_bytes()
        require(sha(original) == result['files'][rel]['before'], 'backup_source_drift')
        destination = backup / rel
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        write_new(destination, original)
        require(sha(destination.read_bytes()) == sha(original), 'backup_readback_failed')
    write_new(backup / 'SOURCE_MANIFEST.json', json.dumps(PAYLOAD['manifest'], indent=2).encode())
    emit('backup_verified', directory=str(backup), source_file_mode='0600', files=list(PAYLOAD['candidates']))
    stage = '4_stop_api_then_dispatcher'
    idle('immediately_before_stop')
    cmd(['systemctl', 'stop', 'mathdoc-api'])
    api_stopped = True
    require(not active('mathdoc-api'), 'api_stop_unconfirmed')
    idle('after_api_stop')
    cmd(['systemctl', 'stop', 'mathdoc-dispatcher'])
    dispatcher_stopped = True
    require(not active('mathdoc-dispatcher'), 'dispatcher_stop_unconfirmed')
    idle('after_both_stop')
    stage = '5_replace_reviewed_files'
    hash_gate('before')
    replaced = True
    for rel in ('api/store.py', 'api/main.py'):
        data = base64.b64decode(PAYLOAD['candidates'][rel], validate=True)
        require(sha(data) == result['files'][rel]['after'], 'payload_hash_mismatch')
        compile(data, rel, 'exec')
        replace_source(rel, data)
    hash_gate('after')
    cmd([PYTHON, '-B', '-c', 'import api.main, api.store'])
    emit('source_replaced', files=result['files'])
    stage = '6_start_dispatcher_then_api'
    cmd(['systemctl', 'start', 'mathdoc-dispatcher'])
    dispatcher_stopped = False
    require(active('mathdoc-dispatcher'), 'dispatcher_start_unconfirmed')
    cmd(['systemctl', 'start', 'mathdoc-api'])
    api_stopped = False
    health()
    require(active('mathdoc-api') and active('mathdoc-dispatcher'), 'service_start_unconfirmed')
    emit('services_ready', api_active=True, dispatcher_active=True, health_ok=True)
    stage = '7_issue_temporary_credential'
    require(credential.parent.is_dir() and not credential.parent.is_symlink() and stat.S_IMODE(credential.parent.stat().st_mode) == 0o700, 'credential_directory_unsafe')
    require(not credential.exists() and not credential.is_symlink(), 'credential_path_exists')
    credential_issued = True
    cmd([PYTHON, '-B', '-m', 'api.admin', 'issue', '--output', str(credential)])
    require(stat.S_IMODE(credential.stat().st_mode) == 0o600, 'credential_mode_unsafe')
    value = json.loads(credential.read_text())
    synthetic_owner, token = value['identity'], value['token']
    require(str(uuid.UUID(synthetic_owner)) == synthetic_owner and value['baseUrl'] == PUBLIC, 'credential_contract_mismatch')
    emit('temporary_credential_issued', mode='0600', exclusive_new_path=True)
    stage = '8_public_acceptance'
    code, body = http('GET', PUBLIC + '/health')
    require(code == 200 and body.get('status') == 'ok', 'public_health_failed')
    result['http'] = {}
    for label, candidate in [('anonymous_revoke', None), ('invalid_token_revoke', 'invalid-synthetic-credential')]:
        code, body = http('POST', PUBLIC + '/api/v1/identity/revoke', candidate)
        result['http'][label] = code
        require(code == 401 and body == {'detail':'authentication_required'}, 'unauthorized_response_mismatch')
        emit('public_http', check=label, status=code, generic_body=True)
    code, body = http('GET', PUBLIC + '/api/v1/capabilities', token)
    require(code == 200 and body.get('ready') is True, 'synthetic_capabilities_not_ready')
    # A valid tiny PNG is constructed in memory, never from a user document.
    def png_chunk(kind, content):
        return struct.pack('>I', len(content)) + kind + content + struct.pack('>I', zlib.crc32(kind + content) & 0xffffffff)
    payload = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 64, 8, 2, 0, 0, 0)) +
               png_chunk(b'IDAT', zlib.compress((b'\x00' + b'\xff\xff\xff' * 64) * 64)) + png_chunk(b'IEND', b''))
    code, body = http('POST', PUBLIC + '/api/v1/jobs?extension=png', token, payload,
                      {'Content-Type':'application/octet-stream', 'Idempotency-Key':'revoke-smoke-' + stamp,
                       'X-Source-Sha256':sha(payload)})
    result['http']['synthetic_submit'] = code
    require(code == 202 and body.get('state') in ('QUEUED', 'RUNNING'), 'synthetic_enqueue_failed')
    job_id = body['taskId']
    require(str(uuid.UUID(job_id)) == job_id, 'invalid_task_id')
    result['task_id'] = job_id
    result['initial_task_state'] = body['state']
    emit('synthetic_enqueued', status=code, state=body['state'], task_id=job_id)
    observed = {}
    for _ in range(80):
        observed = synthetic_containers()
        if any(s == 'running' for s in observed.values()):
            break
        require(job_row()['state'] in ('QUEUED', 'RUNNING'), 'task_ended_before_revoke')
        time.sleep(0.1)
    result['container_seen_before_revoke'] = bool(observed)
    result['container_running_before_revoke'] = any(s == 'running' for s in observed.values())
    emit('before_revoke_execution', task_state=job_row()['state'], container_seen=bool(observed), container_running=result['container_running_before_revoke'])
    code, body = http('POST', PUBLIC + '/api/v1/identity/revoke', token)
    result['http']['authenticated_revoke'] = code
    require(code == 200 and body == {'revoked':True}, 'revoke_response_mismatch')
    emit('public_http', check='authenticated_revoke', status=code, exact_revoked_true_only=True)
    code, body = http('GET', PUBLIC + '/api/v1/capabilities', token)
    result['http']['old_token_capabilities'] = code
    require(code == 401 and body == {'detail':'authentication_required'}, 'old_token_still_authorized')
    emit('public_http', check='old_token_capabilities', status=code, generic_body=True)
    row = job_row()
    require(row['state'] == 'CANCELLED' and row['error'] == 'authorization_revoked' and row['cancel'] == 1, 'task_cancel_readback_failed')
    original = DATA / 'jobs' / job_id / 'input/source.png'
    require(original.is_file() and not original.is_symlink() and sha(original.read_bytes()) == sha(payload), 'original_not_preserved')
    await_synthetic_stop()
    result.update(task_state=row['state'], task_error=row['error'], original_retained=True,
                  original_sha256=sha(payload), container_stopped_confirmed=True,
                  container_stop_evidence='docker ps -a: exact worker and validator names absent')
    emit('independent_readback', state=row['state'], error=row['error'], original_retained=True, original_hash_matches=True, worker_and_validator_absent=True)
    stage = '9_temporary_identity_cleanup'
    admin_cleanup()
    token = None
    stage = 'final_readback'
    health()
    require(active('mathdoc-api') and active('mathdoc-dispatcher'), 'final_service_inactive')
    hash_gate('after')
    idle('final', enforce=False)
    result.update(status='PASS_SCOPED', api_active=True, dispatcher_active=True, health_ok=True, rollback='NOT_NEEDED')
    emit('deployment_complete', status='PASS_SCOPED', backup_directory=str(backup))
except BaseException as error:
    result.update(status='BLOCK', failed_stage=stage, failure_type=type(error).__name__)
    # Exception strings/tracebacks may contain HTTP request details; do not log them.
    emit('deployment_failed', stage=stage, failure_type=type(error).__name__)
    try:
        admin_cleanup(cancel_on_failure=True)
    except BaseException as cleanup_error:
        result['cleanup_status'] = 'BLOCKED'
        result['credential_path_pending_cleanup'] = str(credential)
        emit('cleanup_blocked', failure_type=type(cleanup_error).__name__, credential_path=str(credential))
    try:
        rollback()
    except BaseException as rollback_error:
        result['rollback'] = 'BLOCKED'
        emit('rollback_blocked', failure_type=type(rollback_error).__name__, backup_directory=result['backup_directory'])
finally:
    for temporary in temporary_files:
        if temporary.exists() and not temporary.is_symlink():
            temporary.unlink()
    if backup.exists():
        write_new(backup / 'DEPLOYMENT_RESULT.json', (json.dumps(result, indent=2)+'\n').encode())
    emit('result', **result)
sys.exit(0 if result['status'] == 'PASS_SCOPED' else 1)
