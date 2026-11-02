"""Local-only real gateway/Store/Office worker/readback server for B1b synthetic smoke.

No production access, downloads, Docker claims or model calls. Native verification
uses adb reverse to this loopback HTTP server; only its test transport rewrites HTTPS.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
sys.path.insert(0, str(SERVICE))
from api.main import create_app
from api.recalculation_output import validate_recalculation_output
from api.office_output import validate_office_output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', required=True)
    parser.add_argument('--port', type=int, default=18864)
    parser.add_argument('--office-program', default=r'C:\Program Files\LibreOffice\program')
    parser.add_argument('--cert-file',type=Path);parser.add_argument('--tls-key-file',type=Path)
    args = parser.parse_args()
    if bool(args.cert_file)!=bool(args.tls_key_file):parser.error('TLS requires both certificate and key')
    folder = Path(args.run_dir).resolve()
    folder.mkdir(parents=True, exist_ok=False)
    app = create_app(folder / 'data')
    store = app.state.store
    owner, token = store.issue()
    other, other_token = store.issue()
    office = Path(args.office_program)
    env = os.environ.copy()
    env['PATH'] = str(office) + os.pathsep + env['PATH']
    import pypdf
    env['PYTHONPATH'] = os.pathsep.join([str(SERVICE), str(Path(pypdf.__file__).parents[1])])
    version = subprocess.run([office / 'soffice.com', '--version'], check=True, capture_output=True, text=True).stdout.split()[1]
    font_inventory=None
    if os.name=='nt':
        # Real installed Windows families replace Linux fc-list in this local adapter.
        command=['powershell.exe','-NoProfile','-Command',
            'Add-Type -AssemblyName System.Drawing; (New-Object System.Drawing.Text.InstalledFontCollection).Families.Name']
        fonts=subprocess.run(command,check=True,capture_output=True,text=True,timeout=30)
        font_inventory=folder/'windows-installed-fonts.txt'
        font_inventory.write_text(fonts.stdout,encoding='utf-8')
        (folder/'FONT_INVENTORY.json').write_text(json.dumps({'command':command,'exitCode':fonts.returncode,
            'sha256':hashlib.sha256(fonts.stdout.encode()).hexdigest(),'adapter':'Windows InstalledFontCollection replaces Linux fc-list only'},indent=2))
    (store.root / 'office-ready.json').write_text(json.dumps({'rendererVersion': version, 'recalculationContractVersion': 1}))
    # These are generated local test identities, never real account credentials.
    (folder / 'fixture-identities.json').write_text(json.dumps({'owner': owner, 'token': token, 'otherOwner': other, 'otherToken': other_token, 'port': args.port}))
    inputs = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in SERVICE.rglob('*.py') if '__pycache__' not in p.parts}
    (folder / 'SOURCE_INPUTS.json').write_text(json.dumps(inputs, indent=2))
    stop = threading.Event()
    def pump():
        while not stop.is_set():
            row = store.claim()
            if row is None:
                stop.wait(0.25)
                continue
            directory = store.root / 'jobs' / row['id']
            started = time.monotonic()
            adapter=''
            if font_inventory:
                adapter=('import types,subprocess\nimport office_worker.run as worker\nreal_run=worker.subprocess.run\n'
                    'def owned_run(command,**kwargs):\n'
                    ' if command==["fc-list","--format","%{family}\\n"]: return subprocess.CompletedProcess(command,0,Path('+repr(str(font_inventory))+').read_text(encoding="utf-8"),"")\n'
                    ' return real_run(command,**kwargs)\nworker.subprocess=types.SimpleNamespace(run=owned_run)\n')
            command = [str(office / 'python.exe'), '-c', 'from pathlib import Path\n'+adapter+
                       'from office_worker.run import main\nmain(Path(' + repr(str(directory / 'input')) + '), Path(' + repr(str(directory / 'output')) + '))']
            result = subprocess.run(command, env=env, capture_output=True, timeout=300)
            (folder / (row['id'] + '-worker.log')).write_bytes(result.stdout + result.stderr)
            record = {'jobId': row['id'], 'command': command, 'cwd': str(ROOT), 'exit_code': result.returncode,
                      'seconds': time.monotonic() - started, 'containerIsolation': 'NOT_RUN'}
            try:
                if result.returncode:
                    error = directory / 'output/error.json'
                    code = json.loads(error.read_text()).get('errorCode') if error.exists() else 'local_worker_failed'
                    store.finish(row['id'], 'FAILED', error=code)
                else:
                    # Independent process has only the actual output directory, not the source or UNO state.
                    validator = subprocess.run([sys.executable, '-c',
                        'from pathlib import Path; from office_worker.check_output import check_output; check_output(Path(' + repr(str(directory / 'output')) + '))'],
                        env=env, capture_output=True, timeout=60)
                    (folder / (row['id'] + '-validator.log')).write_bytes(validator.stdout + validator.stderr)
                    record['validator_exit_code'] = validator.returncode
                    if validator.returncode:
                        store.finish(row['id'], 'FAILED', error='independent_readback_failed')
                    else:
                        operation=json.loads(row['options']).get('operation')
                        validate=validate_office_output if operation=='office_pdf' else validate_recalculation_output
                        manifest = validate(directory, json.loads(row['options']))
                        store.finish(row['id'], 'SUCCEEDED', manifest)
                        record['manifest'] = manifest
                record['final_state'] = store.get(row['owner'], row['id'])['state']
            except Exception as error:
                store.finish(row['id'], 'FAILED', error='local_publication_failed')
                record['failure_type'] = type(error).__name__
            # Both owned worker and independent validator above have actually exited.
            # Timeout exceptions before this point deliberately leave the stop bit unset.
            store.record_worker_stopped(row['id'])
            (folder / (row['id'] + '-RUN.json')).write_text(json.dumps(record, indent=2))
    worker = threading.Thread(target=pump, name='B1b-local-office-worker')
    worker.start()
    print('B1B_LOCAL_GATEWAY_READY port=' + str(args.port), flush=True)
    import uvicorn
    try:
        uvicorn.run(app, host='127.0.0.1', port=args.port, access_log=False,
            ssl_certfile=str(args.cert_file) if args.cert_file else None,ssl_keyfile=str(args.tls_key_file) if args.tls_key_file else None)
    finally:
        stop.set()
        worker.join(timeout=310)


if __name__ == '__main__':
    main()
