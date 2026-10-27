"""B01 local lifecycle smoke: production dispatcher logic with real UNO child processes.

Docker/mount/flock are explicitly adapted to local owned processes, NOT container
isolation evidence. Synthetic sources, new run-dir, no network/model/deployment.
"""
import argparse
import asyncio
import hashlib
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import types
import xml.etree.ElementTree as ET

from verify_b_xlsx_readback import make_source, parts, package, X

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
sys.path.insert(0, str(SERVICE))
from api.main import create_app
from api.store import Store
from fastapi.testclient import TestClient
import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', required=True)
    parser.add_argument('--office-program', default=r'C:\Program Files\LibreOffice\program')
    args = parser.parse_args()
    folder = Path(args.run_dir).resolve(); folder.mkdir(parents=True, exist_ok=False)
    office = Path(args.office_program)
    env = os.environ.copy(); env['PATH'] = str(office) + os.pathsep + env['PATH']
    import pypdf
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    version = subprocess.run([office / 'soffice.com', '--version'], capture_output=True, check=True, text=True).stdout.split()[1]
    # The production main still uses Linux flock. This adapter does not test that lock.
    if os.name == 'nt':
        sys.modules['fcntl'] = types.SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=lambda *_: None)
    dispatch = importlib.import_module('dispatcher.main')
    real_subprocess = dispatch.subprocess; real_os = dispatch.os
    dispatch.subprocess = types.SimpleNamespace(SubprocessError=subprocess.SubprocessError,
        run=lambda command, **kw: subprocess.CompletedProcess(command, 0, '', '') if command[0] in {'mount', 'umount'} else subprocess.run(command, **kw))
    dispatch.os = types.SimpleNamespace(chown=lambda *_: None)
    dispatch.OFFICE_IMAGE = 'sha256:' + 'a' * 64
    source = folder / 'source.xlsx'; original = folder / 'original.xlsx'; make_source(original)
    contents = parts(original); sheet = ET.fromstring(contents['xl/worksheets/sheet1.xml'])
    sheet.find(f'.//{{{X}}}c[@r="A2"]/{{{X}}}v').text = '7'
    contents['xl/worksheets/sheet1.xml'] = ET.tostring(sheet); package(source, contents)
    payload = source.read_bytes(); source_hash = hashlib.sha256(payload).hexdigest()
    records = []
    source_map = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in SERVICE.rglob('*.py') if '__pycache__' not in p.parts}
    (folder / 'SOURCE_INPUTS.json').write_text(json.dumps(source_map, indent=2))

    class LocalProcesses:
        def __init__(self, directory, hold=None, output_fault=None):
            self.directory = directory; self.hold = hold; self.output_fault = output_fault
            self.containers = {}; self.processes = []
            self.fail_stop = False; self.starts = 0
            self.marker = directory / 'engine-checkpoint'; self.release = directory / 'release-engine'
            self.commands = []

        def result(self, args, code=0, out='', err='', check=True):
            if code and check:
                raise subprocess.CalledProcessError(code, args, out, err)
            return subprocess.CompletedProcess(args, code, out, err)

        def kill(self, process):
            if process.poll() is None:
                result = subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=30)
                if result.returncode and process.poll() is None:
                    raise RuntimeError('owned_process_stop_unconfirmed')
            process.wait(timeout=30)

        def __call__(self, *args, check=True):
            self.commands.append(args)
            if args[0] == 'create':
                name = args[args.index('--name') + 1]
                mounts = [args[n + 1] for n, value in enumerate(args) if value == '--mount']
                inputs = next(value.split('src=', 1)[1].split(',dst=', 1)[0] for value in mounts if ',dst=/input' in value)
                output = next(value.split('src=', 1)[1].split(',dst=', 1)[0] for value in mounts if ',dst=/output' in value)
                self.containers[name] = {'input': Path(inputs), 'output': Path(output), 'process': None}
            elif args[0] == 'start':
                item = self.containers[args[1]]
                code = 'from pathlib import Path\nfrom office_worker.run import main\n'
                if self.hold:
                    code += ('import office_worker.engine as e\nimport os,time\nfrom contextlib import contextmanager\n'
                        'marker=Path(' + repr(str(self.marker)) + ')\nrelease=Path(' + repr(str(self.release)) + ')\n'
                        'def wait_at_checkpoint():\n marker.write_text(str(os.getpid()))\n while not release.exists(): time.sleep(0.05)\n')
                    if self.hold == 'saved':
                        code += ('original=e.save_calculated\ndef held(*args,**kwargs):\n value=original(*args,**kwargs)\n wait_at_checkpoint()\n return value\ne.save_calculated=held\n')
                    else:
                        code += ('original=e.office_document\n@contextmanager\ndef held(*args,**kwargs):\n with original(*args,**kwargs) as doc:\n  wait_at_checkpoint()\n  yield doc\ne.office_document=held\n')
                code += 'main(Path(' + repr(str(item['input'])) + '),Path(' + repr(str(item['output'])) + '))\n'
                log = (self.directory / ('worker-' + str(self.starts) + '.log')).open('wb')
                process = subprocess.Popen([office / 'python.exe', '-c', code], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
                self.starts += 1; item['process'] = process; self.processes.append((process, log))
            elif args[0] == 'inspect':
                process = self.containers[args[-1]]['process']; status = process.poll()
                return self.result(args, out=json.dumps({'Running': status is None, 'ExitCode': status or 0, 'OOMKilled': False}))
            elif args[0] == 'run':
                mount = args[args.index('--mount') + 1]
                output = Path(mount.split('src=', 1)[1].split(',dst=', 1)[0])
                if self.output_fault:
                    manifest_file = output / 'manifest.json'; manifest = json.loads(manifest_file.read_text())
                    witness_file = output / 'calculation.json'
                    if self.output_fault == 'witness':
                        witness = json.loads(witness_file.read_text()); witness['cells'][0]['number'] = 999
                        witness_file.write_text(json.dumps(witness))
                        manifest['assets'][1].update(size=witness_file.stat().st_size, sha256=hashlib.sha256(witness_file.read_bytes()).hexdigest())
                    elif self.output_fault == 'missing':
                        witness_file.unlink()
                    else:
                        manifest['rendererVersion'] = 'forged-renderer-binding'
                    manifest_file.write_text(json.dumps(manifest))
                code = 'from pathlib import Path; from office_worker.check_output import check_output; check_output(Path(' + repr(str(output)) + '))'
                result = subprocess.run([sys.executable, '-c', code], cwd=ROOT, env=env, capture_output=True, timeout=60)
                (self.directory / 'independent-validator.log').write_bytes(result.stdout + result.stderr)
                return self.result(args, result.returncode, result.stdout.decode(errors='replace'), result.stderr.decode(errors='replace'), check)
            elif args[:2] == ('rm', '-f'):
                name = args[2]
                if self.fail_stop and name in self.containers:
                    return self.result(args, 1, err='Synthetic unavailable Docker stop transport', check=check)
                item = self.containers.pop(name, None)
                if item is None:
                    return self.result(args, 1, err='No such locally adapted task', check=check)
                if item['process'] is not None:
                    self.kill(item['process'])
            elif args[:2] == ('container', 'ls'):
                name = args[args.index('--filter') + 1].removeprefix('name=^/').removesuffix('$')
                if self.fail_stop and name in self.containers:
                    return self.result(args, 1, err='Synthetic unavailable Docker observation transport', check=check)
                return self.result(args, out=name + '\n' if name in self.containers else '')
            else:
                raise RuntimeError('Unexpected local transport command: ' + str(args))
            return self.result(args)

        def wait_checkpoint(self):
            deadline = time.monotonic() + 60
            while not self.marker.exists():
                if self.processes and self.processes[-1][0].poll() is not None:
                    raise RuntimeError('Real Office worker exited before checkpoint')
                if time.monotonic() >= deadline:
                    raise RuntimeError('Real Office checkpoint timeout')
                time.sleep(0.05)

        def close(self):
            for process, log in self.processes:
                self.kill(process); log.close()
            (self.directory / 'adapted-docker-commands.json').write_text(json.dumps(self.commands, indent=2))

    def case(name, action=None, boundary=None, output_fault=None):
        directory = folder / name; directory.mkdir()
        app = create_app(directory / 'data'); store = app.state.store
        (store.root / 'office-ready.json').write_text(json.dumps({'rendererVersion': version, 'recalculationContractVersion': 1}))
        owner, token = store.issue(); _, other_token = store.issue()
        headers = {'Authorization': 'Bearer ' + token, 'Idempotency-Key': 'synthetic-' + name + '-request', 'X-Source-Sha256': source_hash}
        local = LocalProcesses(directory, 'open' if boundary == 'running' else 'saved' if boundary == 'restart' else None, output_fault)
        dispatch.docker = local; dispatch.ROOT = store.root
        publication = dispatch.validate_recalculation_output
        with TestClient(app) as client:
            def restrict(job):
                if action == 'cancel':
                    assert client.post('/api/v1/jobs/' + job + '/cancel', headers=headers).status_code == 200
                else:
                    store.revoke(owner)
            result = client.post('/api/v1/recalculations?extension=xlsx', content=payload, headers=headers)
            assert result.status_code == 202, result.text
            job = result.json()['taskId']; row = store.get(owner, job); claimed = None
            try:
                assert client.get('/api/v1/jobs/' + job, headers={'Authorization': 'Bearer ' + other_token}).status_code == 404
                if boundary == 'claim':
                    restrict(job); assert store.claim() is None
                else:
                    claimed = store.claim(); assert claimed['id'] == job
                    if boundary == 'publish':
                        def fenced(directory, options):
                            manifest = publication(directory, options); restrict(job); return manifest
                        dispatch.validate_recalculation_output = fenced
                    if boundary == 'restart':
                        # Start a real worker, wait until calculated XLSX/witness were written, then lose stop ACK.
                        local('create', '--name', 'mathdoc-' + job, '--mount', 'type=bind,src=' + str(store.root / 'jobs' / job / 'input') + ',dst=/input,readonly',
                              '--mount', 'type=bind,src=' + str(store.root / 'jobs' / job / 'output') + ',dst=/output')
                        local('start', 'mathdoc-' + job); local.wait_checkpoint(); local.fail_stop = True
                        original_cleanup = dispatch.cleanup
                        class Recovered(RuntimeError):
                            pass
                        def end(_store):
                            raise Recovered()
                        dispatch.cleanup = end
                        try:
                            try:
                                dispatch.main()
                            except dispatch.WorkerStopUnconfirmed:
                                pass
                            else:
                                raise AssertionError('Unconfirmed live UNO worker stop was accepted')
                            assert local.processes[0][0].poll() is None
                            assert Store(store.root).get(owner, job)['state'] == 'RUNNING'
                            local.fail_stop = False
                            try:
                                dispatch.main()
                            except Recovered:
                                pass
                            assert local.processes[0][0].poll() is not None
                            row = Store(store.root).get(owner, job)
                            assert row['state'] == 'FAILED' and row['error'] == 'server_restarted' and row['manifest'] is None
                        finally:
                            dispatch.cleanup = original_cleanup
                    elif boundary == 'running':
                        error = []
                        def execute():
                            try:
                                dispatch.execute(store, claimed)
                            except BaseException as caught:
                                error.append(caught)
                        thread = threading.Thread(target=execute); thread.start()
                        try:
                            local.wait_checkpoint(); restrict(job); thread.join(45)
                            assert not thread.is_alive() and not error, error
                            assert local.processes[0][0].poll() is not None
                        finally:
                            local.release.write_text('release for cleanup')
                            if thread.is_alive():
                                local.close(); thread.join(30)
                    else:
                        dispatch.execute(store, claimed)
                row = store.get(owner, job)
                original_file = store.root / 'jobs' / job / 'input/source.xlsx'
                assert hashlib.sha256(original_file.read_bytes()).hexdigest() == source_hash
                expected = 'FAILED' if boundary == 'restart' or output_fault else 'CANCELLED' if action else 'SUCCEEDED'
                assert row['state'] == expected, row
                if action or boundary == 'restart' or output_fault:
                    assert row['manifest'] is None and not (store.root / 'jobs' / job / 'published').exists()
                    assert client.get('/api/v1/jobs/' + job + '/manifest', headers=headers).status_code in {401, 409}
                else:
                    manifest = json.loads(row['manifest']); assert manifest['recalculation']['formulaCount'] == 7
                    from office_worker.recalculation import read_witness
                    output = store.root / 'jobs' / job / 'published'
                    witness, _ = read_witness(output / 'calculation.json', source_hash, manifest['assets'][0]['sha256'])
                    cells = {(c['sheet'], c['cell']): c for c in witness['cells']}
                    assert cells['Data', 'B2']['number'] == 21 and cells['Summary', 'B1']['text'] == 'sum=22'
                    assert client.get('/api/v1/jobs/' + job + '/assets/xlsx', headers=headers).content == (output / 'recalculated.xlsx').read_bytes()
                    # Same idempotency key reopens the known receipt, not a second worker.
                    again = client.post('/api/v1/recalculations?extension=xlsx', content=payload, headers=headers)
                    assert again.json()['taskId'] == job and local.starts == 1
                record = {'scenario': name, 'state': row['state'], 'sourceSha256': source_hash, 'workerStarts': local.starts,
                          'processExitCodes': [p.poll() for p, _ in local.processes], 'containerIsolation': 'NOT_RUN', 'status': 'PASS'}
                records.append(record); (folder / 'RESULTS.json').write_text(json.dumps(records, indent=2)); print(json.dumps(record), flush=True)
                if boundary == 'restart':
                    # Reopen the SAME owner/database: the old key stays failed, only an explicit new key executes.
                    again = client.post('/api/v1/recalculations?extension=xlsx', content=payload, headers=headers)
                    assert again.json()['taskId'] == job and again.json()['state'] == 'FAILED' and local.starts == 1
                    next_headers = {**headers, 'Idempotency-Key': headers['Idempotency-Key'] + '-explicit-new'}
                    accepted = client.post('/api/v1/recalculations?extension=xlsx', content=payload, headers=next_headers)
                    assert accepted.status_code == 202 and accepted.json()['taskId'] != job
                    reopened = Store(store.root); next_job = reopened.claim(); assert next_job['id'] == accepted.json()['taskId']
                    local.hold = None
                    dispatch.execute(reopened, next_job)
                    completed = reopened.get(owner, next_job['id'])
                    assert completed['state'] == 'SUCCEEDED' and local.starts == 2
                    assert reopened.get(owner, job)['state'] == 'FAILED'
                    assert hashlib.sha256(original_file.read_bytes()).hexdigest() == source_hash
                    record = {'scenario': 'same-domain-explicit-request-after-restart', 'state': completed['state'],
                              'oldState': 'FAILED', 'sameOwnerAndDatabase': True, 'workerStarts': local.starts,
                              'sourceSha256': source_hash, 'containerIsolation': 'NOT_RUN', 'status': 'PASS'}
                    records.append(record); (folder / 'RESULTS.json').write_text(json.dumps(records, indent=2)); print(json.dumps(record), flush=True)
            finally:
                dispatch.validate_recalculation_output = publication; local.close()

    async def upload_case(action):
        name = 'upload-' + action
        directory = folder / name; directory.mkdir()
        app = create_app(directory / 'data'); store = app.state.store; owner, token = store.issue()
        (store.root / 'office-ready.json').write_text(json.dumps({'rendererVersion': version, 'recalculationContractVersion': 1}))
        key = 'synthetic-upload-' + action + '-request'
        async def chunks():
            yield payload[:len(payload) // 2]
            if action == 'cancel':
                store.cancel_request(owner, key)
            else:
                store.revoke(owner)
            yield payload[len(payload) // 2:]
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://synthetic.invalid') as client:
            response = await client.post('/api/v1/recalculations?extension=xlsx', content=chunks(), headers={
                'Authorization': 'Bearer ' + token, 'Idempotency-Key': key, 'X-Source-Sha256': source_hash})
        assert response.status_code == (202 if action == 'cancel' else 401)
        assert list((store.root / 'uploads').iterdir()) == [] and store.claim() is None
        if action == 'cancel':
            assert store.get(owner, response.json()['taskId'])['state'] == 'CANCELLED'
        record = {'scenario': name, 'httpStatus': response.status_code, 'sourceSha256': source_hash,
                  'workerStarts': 0, 'status': 'PASS', 'scope': 'ASGI streaming real synthetic XLSX'}
        records.append(record); (folder / 'RESULTS.json').write_text(json.dumps(records, indent=2)); print(json.dumps(record), flush=True)

    try:
        asyncio.run(upload_case('cancel'))
        asyncio.run(upload_case('revoke'))
        case('normal')
        for boundary in ['claim', 'running', 'publish']:
            for action in ['cancel', 'revoke']:
                case(boundary + '-' + action, action, boundary)
        for fault in ['witness', 'missing', 'renderer']:
            case('invalid-output-' + fault, output_fault=fault)
        case('worker-written-output-restart', boundary='restart')
        (folder / 'SUMMARY.json').write_text(json.dumps({'status': 'PASS', 'cases': len(records), 'rendererVersion': version,
            'realWorkerProcesses': True, 'realIndependentValidation': True, 'productionDispatcherLogic': True,
            'dockerMountFlockTransport': 'ADAPTED_NOT_CONTAINER_EVIDENCE', 'LinuxDocker': 'NOT_RUN'}, indent=2))
    finally:
        dispatch.subprocess = real_subprocess; dispatch.os = real_os


if __name__ == '__main__':
    main()
