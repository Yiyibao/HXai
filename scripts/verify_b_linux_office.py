"""Real Linux/Docker Office qualification on an explicitly owned private daemon.
No Docker/mount/flock adapters. Synthetic owners/files only. A new run-dir is required.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / 'server/document-service'
sys.path.insert(0, str(SERVICE))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wait_until(predicate, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        time.sleep(0.05)
    raise TimeoutError('Qualification boundary not reached')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ownership', type=Path)
    parser.add_argument('--images', type=Path)
    parser.add_argument('--source-inputs', type=Path)
    parser.add_argument('--fixture-inputs', type=Path)
    parser.add_argument('--fixtures', type=Path)
    parser.add_argument('--run-dir', type=Path)
    parser.add_argument('--api-child', type=int)
    args = parser.parse_args()
    if args.api_child is not None:
        import uvicorn
        from api.main import create_app
        uvicorn.run(create_app(), host='127.0.0.1', port=args.api_child, access_log=False)
        return 0
    if sys.platform != 'linux':
        parser.error('Only the explicitly owned Linux environment is permitted')
    if any(getattr(args, key) is None for key in ['ownership', 'images', 'source_inputs', 'fixture_inputs', 'fixtures', 'run_dir']):
        parser.error('Ownership, image, source, fixture and new run-directory inputs are required')
    folder = args.run_dir.resolve()
    folder.mkdir(parents=True, exist_ok=False)
    report = dict(status='BLOCKED', mutations_started=False, scenarios=[])
    commands = []
    events = None
    events_log = None
    events_thread = None
    observed = {}
    observation_errors = []
    cases = []

    def command(label, argv, check=True, timeout=90):
        started = time.time()
        result = subprocess.run(list(map(str, argv)), capture_output=True, timeout=timeout, cwd=SERVICE)
        raw = result.stdout + b'\nSTDERR\n' + result.stderr
        log = folder / (str(len(commands)).zfill(4) + '-' + label + '.log')
        with log.open('xb') as output:
            output.write(raw)
        commands.append(dict(command=list(map(str, argv)), cwd=str(SERVICE), exit_code=result.returncode,
                             seconds=round(time.time() - started, 3), log=log.name,
                             sha256=hashlib.sha256(raw).hexdigest()))
        (folder / 'COMMAND_RUNS.json').write_text(json.dumps(commands, indent=2))
        if check and result.returncode:
            raise RuntimeError('Actual command failed: ' + log.name)
        return result

    def docker(*argv, **kwargs):
        return command('docker-' + argv[0], ['/usr/bin/docker', *argv], **kwargs)

    def record(name, **values):
        report['scenarios'].append(dict(name=name, status='PASS', **values))
        (folder / 'RESULT.json').write_text(json.dumps(report, indent=2))
        print(name + ' PASS', flush=True)

    try:
        ownership = json.loads(args.ownership.read_text())
        machine = json.loads(Path('/etc/hxai-b06-owned-instance.json').read_text())
        if machine != ownership or not ownership['created_this_batch'] or os.geteuid() != 0:
            raise ValueError('Dedicated environment ownership differs')
        socket_path = Path(ownership['daemon_socket'])
        if os.environ.get('DOCKER_HOST') != 'unix://' + str(socket_path):
            raise ValueError('Default or foreign Docker context forbidden')
        if folder.parent != Path(ownership['run_root']) or socket_path.is_symlink():
            raise ValueError('Run directory or socket outside owned namespace')
        info = json.loads(docker('info', '--format', '{{json .}}').stdout)
        if ('hxai.b06.owner=' + ownership['owner_nonce'] not in info['Labels'] or
                info['DockerRootDir'] != ownership['daemon_data_root'] or info['OSType'] != 'linux'):
            raise ValueError('Actual Docker daemon not owned by this batch')
        if not all(info[key] for key in ['MemoryLimit', 'SwapLimit', 'CpuCfsQuota', 'PidsLimit']):
            raise ValueError('Daemon cannot enforce the required CPU/memory/no-swap/PID limits')
        sources = json.loads(args.source_inputs.read_text())
        for relative, expected in sources.items():
            path = ROOT / relative.replace('\\', '/')
            if not path.is_file() or sha(path) != expected:
                raise ValueError('Frozen component source changed: ' + relative)
        fixtures = json.loads(args.fixture_inputs.read_text())
        if any(sha(args.fixtures / name) != expected for name, expected in fixtures.items()):
            raise ValueError('Synthetic source fixture bytes differ')
        images = json.loads(args.images.read_text())
        modes = ['normal', 'hold-open', 'hold-saved', 'witness', 'missing', 'renderer']
        if set(images) != set(modes) or any(not re.fullmatch(r'sha256:[a-f0-9]{64}', value) for value in images.values()):
            raise ValueError('All image IDs must be immutable and explicit')
        for image in images.values():
            actual = json.loads(docker('image', 'inspect', image).stdout)[0]
            if actual['Id'] != image or actual['Config']['Labels'].get('hxai.b06.owner') != ownership['owner_nonce']:
                raise ValueError('Image not owned and immutable')
        source_proof = docker('run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--user', '10010:10010', '--entrypoint', '/usr/bin/python3',
            images['normal'], '-c', "import hashlib,json,pathlib;print(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in pathlib.Path('/app/office_worker').glob('*.py')}))")
        expected_worker = {Path(relative.replace('\\', '/')).name: value for relative, value in sources.items()
                           if '/office_worker/' in relative.replace('\\', '/') and relative.endswith('.py')}
        if json.loads(source_proof.stdout) != expected_worker:
            raise ValueError('Actual built image worker sources differ')
        docker('run', '--rm', '--network', 'none', '--read-only', '--user', '10010:10010',
               '--entrypoint', '/bin/cat', images['normal'], '/office-packages.txt')
        version = docker('run', '--rm', '--network', 'none', '--read-only', '--user', '10010:10010',
                         '--entrypoint', '/usr/bin/soffice', images['normal'], '--version').stdout.decode().strip().split()[1]
        (folder / 'FROZEN_INPUTS.json').write_text(json.dumps(dict(ownership=ownership, daemon=info,
            images=images, sources=sources, fixtures=fixtures, renderer_version=version), indent=2))
        report['mutations_started'] = True
        import httpx
        from api.store import Store
        import dispatcher.main as dispatch
        uid = ownership['api_uid']
        if uid != 10010:
            raise ValueError('Unexpected synthetic API identity')
        events_log = (folder / 'docker-events.jsonl').open('xb')
        events = subprocess.Popen(['/usr/bin/docker', 'events', '--filter', 'type=container', '--format', '{{json .}}'],
                                  stdout=subprocess.PIPE, stderr=events_log)

        def observe_containers():
            for line in events.stdout:
                events_log.write(line)
                events_log.flush()
                event = json.loads(line)
                name = event.get('Actor', {}).get('Attributes', {}).get('name', '')
                if event.get('Action') != 'create' or not name.startswith('mathdoc-'):
                    continue
                result = subprocess.run(['/usr/bin/docker', 'inspect', event['Actor']['ID']],
                                        capture_output=True, timeout=30)
                if result.returncode:
                    observation_errors.append('create inspect failed: ' + name)
                    continue
                actual = json.loads(result.stdout)[0]
                (folder / ('created-' + name + '.json')).write_bytes(result.stdout)
                observed[name] = actual

        events_thread = threading.Thread(target=observe_containers)
        events_thread.start()

        class Case:
            def __init__(self, name):
                self.directory = folder / name
                self.directory.mkdir()
                self.data = self.directory / 'data'
                self.store = Store(self.data)
                self.owner, self.token = self.store.issue()
                _, self.other_token = self.store.issue()
                (self.data / 'office-ready.json').write_text(json.dumps(dict(rendererVersion=version, recalculationContractVersion=1)))
                for path in [self.data, *self.data.rglob('*')]:
                    os.chown(path, uid, uid)
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0))
                    port = listener.getsockname()[1]
                self.env = dict(os.environ, MATHDOC_DATA=str(self.data), MATHDOC_OFFICE_IMAGE=images['normal'],
                                PYTHONPATH=str(SERVICE), PYTHONDONTWRITEBYTECODE='1')
                self.api_log = (self.directory / 'api.log').open('xb')
                self.api = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--api-child', str(port)],
                    cwd=SERVICE, env=self.env, user=uid, group=uid, extra_groups=[],
                    stdout=self.api_log, stderr=subprocess.STDOUT)
                self.client = httpx.Client(base_url='http://127.0.0.1:' + str(port), timeout=120, trust_env=False)
                self.jobs = []
                self.extra_processes = []
                cases.append(self)

                def healthy():
                    if self.api.poll() is not None:
                        raise RuntimeError('Actual unprivileged API process exited')
                    try:
                        return self.client.get('/health').status_code == 200
                    except httpx.TransportError:
                        return False
                wait_until(healthy)

            def submit(self, filename='recalculation-source.xlsx', key=None, stream=None):
                source = args.fixtures / filename
                payload = source.read_bytes()
                self.key = key or 'b06-synthetic-' + self.directory.name + '-request'
                self.headers = {'Authorization': 'Bearer ' + self.token, 'Idempotency-Key': self.key,
                                'X-Source-Sha256': hashlib.sha256(payload).hexdigest()}
                endpoint = '/api/v1/recalculations?extension=xlsx' if filename.endswith('.xlsx') else '/api/v1/conversions?extension=' + source.suffix[1:]
                response = self.client.post(endpoint, content=stream if stream is not None else payload, headers=self.headers)
                if response.status_code == 202:
                    self.job = response.json()['taskId']
                    self.jobs.append(self.job)
                self.endpoint, self.payload = endpoint, payload
                return response

            def restrict(self, action):
                if action == 'cancel':
                    response = self.client.post('/api/v1/jobs/' + self.job + '/cancel', headers=self.headers)
                    assert response.status_code == 200
                else:
                    self.store.revoke(self.owner)

            def execute(self, image='normal'):
                row = self.store.claim()
                assert row is not None and row['id'] == self.job
                dispatch.OFFICE_IMAGE = images[image]
                dispatch.execute(self.store, row)

            def start(self, image):
                self.errors = []
                def execute():
                    try:
                        self.execute(image)
                    except BaseException as error:
                        self.errors.append(error)
                self.thread = threading.Thread(target=execute)
                self.thread.start()
                return self.checkpoint()

            def checkpoint(self):
                marker = self.data / 'jobs' / self.job / 'output/b06-checkpoint.json'
                def reached():
                    if hasattr(self, 'thread'):
                        if self.errors or not self.thread.is_alive():
                            raise RuntimeError('Actual worker exited before checkpoint')
                    elif self.extra_processes[-1].poll() is not None:
                        raise RuntimeError('Actual dispatcher exited before checkpoint')
                    return json.loads(marker.read_text()) if marker.is_file() else None
                proof = wait_until(reached)
                actual = json.loads(docker('inspect', 'mathdoc-' + self.job).stdout)[0]
                (self.directory / 'ACTUAL_CONTAINER.json').write_text(json.dumps(actual, indent=2))
                (self.directory / 'ACTUAL_CHECKPOINT.json').write_text(json.dumps(proof, indent=2))
                self.pid = actual['State']['Pid']
                cgroup = Path('/sys/fs/cgroup') / Path('/proc', str(self.pid), 'cgroup').read_text().strip().split('::')[1].lstrip('/')
                limits = {key: (cgroup / key).read_text().strip()
                          for key in ['memory.max', 'memory.swap.max', 'pids.max', 'cpu.max']}
                (self.directory / 'ACTUAL_CGROUP.json').write_text(json.dumps(limits, indent=2))
                assert limits['memory.max'] == str(4 * 1024 ** 3) and limits['memory.swap.max'] == '0'
                assert limits['pids.max'] == '192'
                quota, period = map(int, limits['cpu.max'].split())
                assert quota == 4 * period
                host = actual['HostConfig']
                assert actual['State']['Running'] and actual['Config']['User'] == '10010:10010'
                assert host['NetworkMode'] == 'none' and host['ReadonlyRootfs'] and host['CapDrop'] == ['ALL']
                assert 'no-new-privileges' in host['SecurityOpt'] or 'no-new-privileges=true' in host['SecurityOpt']
                assert host['Memory'] == host['MemorySwap'] == 4 * 1024 ** 3
                assert host['NanoCpus'] == 4_000_000_000 and host['PidsLimit'] == 192
                mounts = {item['Destination']: item for item in actual['Mounts']}
                assert {item['Destination'] for item in actual['Mounts'] if item['Type'] == 'bind'} == {'/input', '/output'}
                assert all(item['Type'] == 'bind' or (item['Type'] == 'tmpfs' and item['Destination'] == '/tmp') for item in actual['Mounts'])
                assert set(host['Tmpfs']) == {'/tmp'}
                assert not mounts['/input']['RW'] and mounts['/output']['RW']
                assert mounts['/input']['Source'] == str(self.data / 'jobs' / self.job / 'input')
                assert os.path.ismount(self.data / 'jobs' / self.job / 'output')
                output_space = os.statvfs(self.data / 'jobs' / self.job / 'output')
                assert output_space.f_frsize * output_space.f_blocks == 192 * 1024 ** 2
                return proof

            def join(self):
                self.thread.join(60)
                assert not self.thread.is_alive(), 'Actual worker execution did not settle'
                assert not self.errors, self.errors

            def dispatcher(self, image='normal'):
                with (self.directory / ('dispatcher-' + str(len(self.extra_processes)) + '.log')).open('xb') as log:
                    process = subprocess.Popen([sys.executable, '-m', 'dispatcher.main'], cwd=SERVICE,
                        env=dict(self.env, MATHDOC_OFFICE_IMAGE=images[image]), stdout=log, stderr=subprocess.STDOUT)
                self.extra_processes.append(process)
                return process

            def finish_check(self, expected):
                row = self.store.get(self.owner, self.job)
                assert row['state'] == expected, dict(row)
                source = self.data / 'jobs' / self.job / 'input' / ('source.' + Path(self.endpoint.split('extension=')[1]).name)
                assert hashlib.sha256(source.read_bytes()).hexdigest() == self.headers['X-Source-Sha256']
                if expected != 'SUCCEEDED':
                    assert row['manifest'] is None and not (source.parent.parent / 'published').exists()
                    response = self.client.get('/api/v1/jobs/' + self.job + '/manifest', headers=self.headers)
                    assert response.status_code in {401, 409}
                return row

            def download(self):
                row = self.finish_check('SUCCEEDED')
                response = self.client.get('/api/v1/jobs/' + self.job + '/manifest', headers=self.headers)
                assert response.status_code == 200
                manifest = response.json()
                assert manifest == json.loads(row['manifest']) and manifest['sourceSha256'] == self.headers['X-Source-Sha256']
                assert self.client.get('/api/v1/jobs/' + self.job, headers={'Authorization': 'Bearer ' + self.other_token}).status_code == 404
                for asset in manifest['assets']:
                    data = self.client.get('/api/v1/jobs/' + self.job + '/assets/' + asset['assetId'], headers=self.headers)
                    assert data.status_code == 200 and hashlib.sha256(data.content).hexdigest() == asset['sha256']
                    target = self.directory / asset['path']
                    target.write_bytes(data.content)
                repeated = self.client.post(self.endpoint, content=self.payload, headers=self.headers)
                assert repeated.status_code == 202 and repeated.json()['taskId'] == self.job
                assert self.store.claim() is None
                return manifest

            def close(self):
                for job in self.jobs:
                    dispatch.stop_task_containers('mathdoc-' + job)
                if hasattr(self, 'thread') and self.thread.is_alive():
                    self.thread.join(30)
                    assert not self.thread.is_alive()
                for job in self.jobs:
                    output = self.data / 'jobs' / job / 'output'
                    if output.exists() and os.path.ismount(output):
                        command('cleanup-umount', ['umount', str(output)])
                for process in self.extra_processes + [self.api]:
                    if process.poll() is None:
                        process.terminate()
                    process.wait(timeout=30)
                self.client.close()
                self.api_log.close()

        xlsx = Case('normal-recalculation')
        assert xlsx.submit().status_code == 202
        xlsx.execute()
        workbook_manifest = xlsx.download()
        assert workbook_manifest['recalculation']['formulaCount'] == 7 and workbook_manifest['recalculation']['sheetCount'] == 2
        reopen = """from pathlib import Path
from datetime import date as calendar_date
from zipfile import ZipFile
import xml.etree.ElementTree as ET
from office_worker.engine import office_document
with office_document(Path('/verified/recalculated.xlsx'),'xlsx') as doc:
 d=doc.getSheets().getByName('Data');s=doc.getSheets().getByName('Summary')
 assert d.getCellRangeByName('A2').getValue()==7
 assert d.getCellRangeByName('B2').getValue()==21 and d.getCellRangeByName('E2').getValue()==22
 assert d.getCellRangeByName('C2').getString()=='001' and s.getCellRangeByName('B1').getString()=='sum=22'
 assert s.IsVisible is False and d.getCharts().getCount()==1
 date=d.getCellRangeByName('D2')
 assert date.getValue()==(calendar_date(2026,10,12)-calendar_date(1899,12,30)).days
 assert date.getString()!=str(int(date.getValue()))
with ZipFile('/verified/recalculated.xlsx') as archive:
 chart=ET.fromstring(archive.read('xl/charts/chart1.xml'))
 ns='{http://schemas.openxmlformats.org/drawingml/2006/chart}'
 assert '7' in [n.text for n in chart.findall('.//'+ns+'numCache/'+ns+'pt/'+ns+'v')]
print('Reopened XLSX: formulas, text 001, date, hidden Summary, updated chart cache verified')
"""
        docker('run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
               '--user', '10010:10010', '--tmpfs', '/tmp:rw,noexec,nosuid,size=384m,mode=1777',
               '--mount', 'type=bind,src=' + str(xlsx.data / 'jobs' / xlsx.job / 'published') + ',dst=/verified,readonly',
               '--entrypoint', '/usr/bin/python3', images['normal'], '-c', reopen)
        record('normal-recalculation', formula_count=7, sheet_count=2, source_sha256=xlsx.headers['X-Source-Sha256'])

        from pypdf import PdfReader
        word = Case('normal-word')
        assert word.submit('document-source.docx').status_code == 202
        word.execute()
        word.download()
        word_text = '\n'.join(page.extract_text() for page in PdfReader(word.directory / 'output.pdf').pages)
        assert all(marker in word_text for marker in ['Bold source', 'plain source', 'Item', 'Quantity', 'Synthetic', '21'])
        record('normal-word', pages=len(PdfReader(word.directory / 'output.pdf').pages))
        command('actual-word-pixel-render', ['pdftoppm', '-png', '-scale-to', '900',
                str(word.directory / 'output.pdf'), str(word.directory / 'page')])
        complex_words = []
        for label in ['A', 'B']:
            pair = []
            for variant in ['source', 'edited']:
                case = Case('complex-word-' + label + '-' + variant)
                assert case.submit('complex-' + variant + '-' + label + '.docx').status_code == 202
                case.execute()
                case.download()
                pages = PdfReader(case.directory / 'output.pdf').pages
                assert len(pages) >= 3 and sum(len(page.images) for page in pages) >= 1
                texts = [page.extract_text() for page in pages]
                joined = '\n'.join(texts)
                for marker in [f'Lecture {label} header preserved', f'Footer {label} preserved',
                               f'Lecture {label} first-page preserved body', f'Lecture {label} last-page unchanged theorem',
                               'Header', 'Value', f'Data {label}']:
                    assert marker in joined, marker
                target = 'TARGET new precise phrase END' if variant == 'edited' else 'TARGET old phrase END'
                assert target in joined
                command('actual-complex-word-render', ['pdftoppm', '-png', '-scale-to', '900',
                        str(case.directory / 'output.pdf'), str(case.directory / 'page')])
                (case.directory / 'PHYSICAL_PAGE_TEXTS.json').write_text(json.dumps(texts, indent=2))
                pair.append(case)
                complex_words.append(case)
            source_pages = PdfReader(pair[0].directory / 'output.pdf').pages
            edited_pages = PdfReader(pair[1].directory / 'output.pdf').pages
            assert len(source_pages) == len(edited_pages)
            for page_number in [1, len(source_pages)]:
                assert sha(pair[0].directory / f'page-{page_number}.png') == sha(pair[1].directory / f'page-{page_number}.png')
            record('complex-word-' + label, pages_each=len(source_pages),
                   representative_unchanged_page_pixels=[1, len(source_pages)], native_omml_input_preserved=True)
        presentations = []
        for label in ['source', 'edited']:
            case = Case('normal-presentation-' + label)
            assert case.submit('presentation-' + label + '.pptx').status_code == 202
            case.execute()
            case.download()
            pages = PdfReader(case.directory / 'output.pdf').pages
            assert len(pages) == 3
            texts = [page.extract_text() for page in pages]
            for text, marker in zip(texts, ['VISIBLE EDIT PAGE', 'VISIBLE COMPAT PAGE', 'VISIBLE LAST']):
                assert marker in text and 'MASTER B03 PRESERVED' in text
                assert 'HIDDEN FIRST' not in text and 'HIDDEN MIDDLE' not in text
            assert 'VISIBLE FALLBACK' in texts[1] and 'UNSUPPORTED CHOICE' not in '\n'.join(texts)
            if label == 'edited':
                assert 'Edited grouped text' in texts[0]
            command('actual-ppt-pixel-render', ['pdftoppm', '-png', '-scale-to', '900', str(case.directory / 'output.pdf'), str(case.directory / 'page')])
            presentations.append(case)
        for page in [2, 3]:
            assert sha(presentations[0].directory / ('page-' + str(page) + '.png')) == sha(presentations[1].directory / ('page-' + str(page) + '.png'))
        assert sha(presentations[0].directory / 'page-1.png') != sha(presentations[1].directory / 'page-1.png')
        record('normal-presentation-original-and-edit', visible_pages_each=3, unchanged_page_pixels=[2, 3])

        for action in ['cancel', 'revoke']:
            case = Case('upload-' + action)
            key = 'b06-streaming-' + action + '-request'
            payload = (args.fixtures / 'recalculation-source.xlsx').read_bytes()
            def chunks():
                yield payload[:len(payload) // 2]
                if action == 'cancel':
                    case.store.cancel_request(case.owner, key)
                else:
                    case.store.revoke(case.owner)
                yield payload[len(payload) // 2:]
            response = case.submit(key=key, stream=chunks())
            assert response.status_code == (202 if action == 'cancel' else 401)
            assert case.store.claim() is None and not any((case.data / 'uploads').iterdir())
            record('upload-' + action, http_status=response.status_code)

        for boundary in ['claim', 'running', 'publish']:
            for action in ['cancel', 'revoke']:
                case = Case(boundary + '-' + action)
                assert case.submit().status_code == 202
                if boundary == 'claim':
                    row = case.store.claim()
                    case.restrict(action)
                    dispatch.OFFICE_IMAGE = images['normal']
                    dispatch.execute(case.store, row)
                elif boundary == 'running':
                    case.start('hold-open')
                    case.restrict(action)
                    case.join()
                else:
                    finish = case.store.finish
                    injected = False
                    def fenced(*values, **options):
                        nonlocal injected
                        if not injected:
                            injected = True
                            case.restrict(action)
                        return finish(*values, **options)
                    case.store.finish = fenced
                    case.execute()
                    assert injected
                case.finish_check('CANCELLED')
                record(boundary + '-' + action, state='CANCELLED')

        for mode in ['witness', 'missing', 'renderer']:
            case = Case('invalid-output-' + mode)
            assert case.submit().status_code == 202
            case.execute(mode)
            case.finish_check('FAILED')
            record('invalid-output-' + mode, state='FAILED')
        bad_formula = Case('actual-formula-error-rejected')
        assert bad_formula.submit('formula-error.xlsx').status_code == 202
        bad_formula.execute()
        assert bad_formula.finish_check('FAILED')['error'] == 'formula_recalculation_failed'
        record('actual-formula-error-rejected', state='FAILED', published=False)

        killed = Case('actual-worker-kill')
        assert killed.submit().status_code == 202
        killed.start('hold-open')
        docker('kill', '--signal', 'KILL', 'mathdoc-' + killed.job)
        killed.join()
        killed.finish_check('FAILED')
        record('actual-worker-kill', state='FAILED')

        saved = Case('actual-worker-kill-after-save')
        assert saved.submit().status_code == 202
        assert saved.start('hold-saved')['phase'] == 'real_worker_output_saved_before_exit'
        saved_assets = {p.name: sha(p) for p in (saved.data / 'jobs' / saved.job / 'output').iterdir() if p.is_file()}
        assert {'recalculated.xlsx', 'manifest.json', 'calculation.json'} <= set(saved_assets)
        (saved.directory / 'SAVED_BEFORE_KILL.json').write_text(json.dumps(saved_assets, indent=2))
        docker('kill', '--signal', 'KILL', 'mathdoc-' + saved.job)
        saved.join()
        saved.finish_check('FAILED')
        record('actual-worker-kill-after-save', state='FAILED', output_before_kill=saved_assets)

        crashed = Case('actual-dispatcher-sigkill-after-save')
        assert crashed.submit().status_code == 202
        old_job = crashed.job
        process = crashed.dispatcher('hold-saved')
        marker_path = crashed.data / 'jobs' / old_job / 'output/b06-checkpoint.json'
        wait_until(marker_path.is_file)
        assert crashed.checkpoint()['phase'] == 'real_worker_output_saved_before_exit'
        process.kill()
        assert process.wait(timeout=30) == -signal.SIGKILL
        assert crashed.store.get(crashed.owner, old_job)['state'] == 'RUNNING'
        assert json.loads(docker('inspect', 'mathdoc-' + old_job).stdout)[0]['State']['Running']
        recovery = crashed.dispatcher()
        wait_until(lambda: crashed.store.get(crashed.owner, old_job)['state'] == 'FAILED')
        crashed.finish_check('FAILED')
        assert not docker('container', 'ls', '--all', '--filter', 'name=^/mathdoc-' + old_job + '$', '--format', '{{.Names}}').stdout.strip()
        assert not os.path.ismount(crashed.data / 'jobs' / old_job / 'output')
        repeated = crashed.client.post(crashed.endpoint, content=crashed.payload, headers=crashed.headers)
        assert repeated.status_code == 202 and repeated.json()['taskId'] == old_job
        assert repeated.json()['state'] == 'FAILED'
        recovery.terminate()
        recovery.wait(timeout=30)
        record('actual-dispatcher-sigkill-after-save', old_state='FAILED', dispatcher_exit=-signal.SIGKILL,
               stopped_before_recovery=True, repeated_request_not_reexecuted=True)

        lost = Case('actual-upload-ack-lost')
        lost.endpoint = '/api/v1/recalculations?extension=xlsx'
        lost.payload = (args.fixtures / 'recalculation-source.xlsx').read_bytes()
        lost.headers = {'Authorization': 'Bearer ' + lost.token, 'Idempotency-Key': 'b06-real-dropped-ack',
                        'X-Source-Sha256': hashlib.sha256(lost.payload).hexdigest()}
        raw_headers = {'Host': '127.0.0.1', 'Content-Length': str(len(lost.payload)), 'Connection': 'close', **lost.headers}
        request = ('POST ' + lost.endpoint + ' HTTP/1.1\r\n' +
                   '\r\n'.join(key + ': ' + value for key, value in raw_headers.items()) + '\r\n\r\n').encode() + lost.payload
        with socket.create_connection(('127.0.0.1', lost.client.base_url.port), timeout=10) as connection:
            connection.sendall(request)
            connection.shutdown(socket.SHUT_WR)
            # Intentionally never read the server's acceptance response.
        def accepted_without_ack():
            with lost.store.db() as db:
                rows = db.execute('SELECT id FROM jobs WHERE owner=?', (lost.owner,)).fetchall()
            assert len(rows) <= 1
            return rows[0]['id'] if rows else None
        lost.job = wait_until(accepted_without_ack)
        lost.jobs.append(lost.job)
        resumed = lost.client.post(lost.endpoint, content=lost.payload, headers=lost.headers)
        assert resumed.status_code == 202 and resumed.json()['taskId'] == lost.job
        worker = lost.dispatcher()
        wait_until(lambda: lost.store.get(lost.owner, lost.job)['state'] == 'SUCCEEDED')
        lost.download()
        worker.terminate()
        worker.wait(timeout=30)
        with lost.store.db() as db:
            assert db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0] == 1
        record('actual-upload-ack-lost', acceptance_response_read=False, same_task_recovered=True, jobs=1)

        unknown = Case('saved-output-control-endpoint-lost')
        assert unknown.submit().status_code == 202
        marker = unknown.start('hold-saved')
        assert marker['phase'] == 'real_worker_output_saved_before_exit'
        hidden_socket = socket_path.with_name('docker.socket-unavailable')
        assert not hidden_socket.exists()
        socket_path.rename(hidden_socket)
        try:
            unknown.thread.join(45)
            assert not unknown.thread.is_alive() and len(unknown.errors) == 1
            assert isinstance(unknown.errors[0], dispatch.WorkerStopUnconfirmed)
            assert unknown.store.get(unknown.owner, unknown.job)['state'] == 'RUNNING'
            assert (unknown.data / 'jobs' / unknown.job / 'input/source.xlsx').is_file()
            assert os.path.ismount(unknown.data / 'jobs' / unknown.job / 'output')
            os.kill(unknown.pid, 0)
            (unknown.directory / 'STOP_UNCONFIRMED.json').write_text(json.dumps(dict(state='RUNNING',
                actual_live_host_pid=unknown.pid, real_docker_endpoint_unavailable=True, source_retained=True)))
        finally:
            hidden_socket.rename(socket_path)
        recovered_log = (unknown.directory / 'recovery-dispatcher.log').open('xb')
        recovered = subprocess.Popen([sys.executable, '-m', 'dispatcher.main'], cwd=SERVICE, env=unknown.env,
                                     stdout=recovered_log, stderr=subprocess.STDOUT)
        unknown.extra_processes.append(recovered)
        wait_until(lambda: unknown.store.get(unknown.owner, unknown.job)['state'] == 'FAILED')
        recovered.terminate()
        recovered.wait(timeout=30)
        recovered_log.close()
        unknown.finish_check('FAILED')
        repeated = unknown.client.post(unknown.endpoint, content=unknown.payload, headers=unknown.headers)
        assert repeated.status_code == 202 and repeated.json()['taskId'] == unknown.job and repeated.json()['state'] == 'FAILED'
        assert unknown.submit(key='b06-explicit-after-restart-request').status_code == 202
        unknown.execute()
        unknown.download()
        record('saved-output-control-endpoint-lost-and-explicit-recovery', old_state='FAILED', new_state='SUCCEEDED', actual_control_failure=True)

        lock = Case('actual-fcntl-lock')
        lock_log = (lock.directory / 'first-dispatcher.log').open('xb')
        first = subprocess.Popen([sys.executable, '-m', 'dispatcher.main'], cwd=SERVICE, env=lock.env,
                                 stdout=lock_log, stderr=subprocess.STDOUT)
        lock.extra_processes.append(first)
        import fcntl
        def locked():
            if first.poll() is not None:
                raise RuntimeError('First actual dispatcher exited')
            path = lock.data / 'dispatcher.lock'
            if not path.exists():
                return False
            with path.open('r+') as handle:
                try:
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    return True
                fcntl.flock(handle, fcntl.LOCK_UN)
            return False
        wait_until(locked)
        second = subprocess.run([sys.executable, '-m', 'dispatcher.main'], cwd=SERVICE, env=lock.env,
                                capture_output=True, timeout=30)
        (lock.directory / 'second-dispatcher.log').write_bytes(second.stdout + second.stderr)
        assert second.returncode != 0 and first.poll() is None and locked()
        first.terminate()
        first.wait(timeout=30)
        lock_log.close()
        record('actual-fcntl-lock', second_exit_code=second.returncode, first_remained_live=True)
        normal_jobs = [xlsx, word, *complex_words, *presentations, lost]
        wait_until(lambda: all('mathdoc-' + case.job + '-validate' in observed for case in normal_jobs))
        assert not observation_errors, observation_errors
        for case in normal_jobs:
            validator = observed['mathdoc-' + case.job + '-validate']
            host = validator['HostConfig']
            assert validator['Config']['User'] == '10010:10010'
            assert validator['Image'] == images['normal']
            assert host['NetworkMode'] == 'none' and host['ReadonlyRootfs']
            assert host['CapDrop'] == ['ALL'] and not host['Privileged']
            assert 'no-new-privileges' in host['SecurityOpt']
            assert host['Memory'] == host['MemorySwap'] == 768 * 1024 ** 2
            assert host['PidsLimit'] == 64 and host['NanoCpus'] == 1_000_000_000
            assert len(validator['Mounts']) == 1
            mount = validator['Mounts'][0]
            assert mount['Type'] == 'bind' and mount['Destination'] == '/output' and not mount['RW']
            assert mount['Source'] == str(case.data / 'jobs' / case.job / 'output')
        record('actual-independent-validator-isolation', normal_jobs_inspected=len(normal_jobs))
        report.update(status='PASS', renderer_version=version, linux_kernel=os.uname().release,
                      scope='Actual owned Linux private Docker, real mount/flock/UNO/validator/API; not production, physical device, App, OCR or MS Office')
    except Exception as error:
        report.update(status='FAIL' if report['mutations_started'] else 'BLOCKED', error=type(error).__name__ + ': ' + str(error))
        (folder / 'FAILURE_TRACEBACK.log').write_text(traceback.format_exc())
    finally:
        for case in reversed(cases):
            try:
                case.close()
            except Exception as error:
                report.update(status='FAIL', cleanup_error=type(error).__name__ + ': ' + str(error))
        if events is not None:
            events.terminate()
            events.wait(timeout=30)
            events_thread.join(30)
            assert not events_thread.is_alive()
            events_log.close()
        (folder / 'RESULT.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report), flush=True)
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
