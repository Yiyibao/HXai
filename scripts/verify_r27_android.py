"""Owned-emulator R27 cloud/device smoke. Synthetic loopback services only.

Installs frozen QA APKs, archives before clearing ONLY nativeqa, kills its actual
phone process, and checks independent cloud/native artifact bytes. No dependencies,
real model, production service, user device or cross-App automation are used.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import io
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tarfile
import threading
import xml.etree.ElementTree as ET
from zipfile import ZipFile
import secrets
import time

try:
    from project_storage import enforce
except ModuleNotFoundError as error:
    if error.name != "project_storage":
        raise
    from scripts.project_storage import enforce

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server/agent-runtime'))
from hxai_agent.authority import NetworkAuthority, NetworkStore
from hxai_agent.store import canonical, Conflict
from verify_agent_android import freeze_apk

PACKAGE = 'org.hxsi.agent.nativeqa'
RUNNER = PACKAGE + '.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
PROBE = 'me.rerere.rikkahub.feature.agent.CloudDeviceProcessProbe'
SECRET = 'SYNTHETIC-R27-ANDROID-NOT-A-REAL-KEY'
CONTENT = '# R27 cloud output\nVerified after the phone process exited.\n'


class Provider(BaseHTTPRequestHandler):
    def log_message(self, *_): pass

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            assert self.headers.get('Authorization') == 'Bearer ' + SECRET
            assert body['model'] == 'synthetic-locked-model'
            assert body['reasoning_effort'] == 'high'
            assert self.server.release.wait(90), 'Phone exit barrier not released'
            messages = body['messages']; system = messages[0].get('content', '')
            native = self.server.native
            tool = 'create_material_file' if native else 'write_report_version'
            required = 'R27 native verified document' if native else 'Verified after the phone process exited.'
            if system.startswith('HXAI_TASK_PLAN_V6'):
                text = canonical(dict(steps=[dict(id='actual-output', tool=tool, purpose='Create the requested real file')],
                    acceptance=[dict(stepId='actual-output', kind='artifact_content', format='docx' if native else 'md', contains=[required])], explanation='Independent artifact readback required'))
                message = dict(role='assistant', content=text)
            elif system.startswith('HXAI_GOAL_VERIFY_V6'):
                evidence = json.loads(messages[-1]['content'])
                artifacts = evidence['evidence'][0].get('artifacts', []) if native else [evidence['evidence'][0]['artifact']]
                assert any(required in a['content'] for a in artifacts)
                message = dict(role='assistant', content=canonical(dict(accepted=True, goalRevision=evidence['goalRevision'], evidenceHash=evidence['evidenceHash'], reason='Synthetic fixture only')))
            elif any(m['role'] == 'tool' for m in messages):
                message = dict(role='assistant', content='The actual file has been independently read back.')
            else:
                arguments = dict(format='docx', title='R27 actual phone document', content=canonical(dict(body=required))) if native else dict(family='phone-exit', content=CONTENT, parent_artifact_id=None)
                message = dict(role='assistant', content='', tool_calls=[dict(id='actual-file', type='function', function=dict(name=tool, arguments=canonical(arguments)))])
            self.server.requests.append(dict(model=body['model'], reasoning=body['reasoning_effort'], requestId=self.headers.get('Idempotency-Key'), bodyHash=hashlib.sha256(canonical(body).encode()).hexdigest()))
            value = dict(choices=[dict(message=message)])
            if system.startswith('HXAI_'): value['usage'] = dict(prompt_tokens=13, completion_tokens=9)
            data = canonical(value).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        except Exception:
            self.server.failed.set()
            self.send_error(500, 'synthetic_fixture_rejected')


class FaultGateway(BaseHTTPRequestHandler):
    """Owned transport only; holds a real committed ACK, never changes a product result."""
    protocol_version='HTTP/1.1'
    def log_message(self,*_):pass
    def do_POST(self):
        if self.path=='/qa/release':
            if self.headers.get('X-QA-Fault-Key')!=self.server.key:self.send_error(403);return
            self.server.offline.clear();self.server.release.set();self.send_response(200);self.send_header('Content-Length','0');self.end_headers();return
        if self.server.offline.is_set():
            self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();self.close_connection=True;return
        length=int(self.headers.get('Content-Length','0'))
        if not 0<length<=2097152:self.send_error(413);return
        data=self.rfile.read(length);value=json.loads(data)
        connection=http.client.HTTPConnection('127.0.0.1',self.server.upstream,timeout=150)
        try:
            connection.request('POST',self.path,body=data,headers={'Content-Type':'application/json','Authorization':self.headers.get('Authorization','')})
            response=connection.getresponse();body=response.read(2097153);status=response.status
            if len(body)>2097152:raise ValueError('owned_gateway_response_limit')
        finally:connection.close()
        operation=value.get('operation')
        expected='device.finish' if self.server.mode=='receipt_restart' else 'device.claim'
        inject=False
        if status==200 and operation==expected and (self.server.mode in {'lock_claim','stop_claim','profile_claim','claim_restart','receipt_restart'} or self.server.mode.startswith('offline_')):
            with self.server.gate_lock:
                if not self.server.injected:
                    self.server.injected=True;inject=True
                    self.server.boundary={'operation':operation,'taskId':value['value']['taskId'],'callId':value['value']['callId']}
        if inject:
            self.server.entered.set()
            if self.server.mode.startswith('offline_'):
                self.server.offline.set();self.server.ack_lost=True
                self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();self.close_connection=True;return
            if not self.server.release.wait(120):raise TimeoutError('Owning test never released actual committed ACK')
        self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers()
        try:self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):
            # Deliberate process-exit/lost-ACK scenario, not a product retry/fallback.
            self.server.ack_lost=True


def until(check, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = check()
        if value: return value
        time.sleep(.2)
    raise TimeoutError('Owned smoke condition not observed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--ownership', type=Path, required=True)
    parser.add_argument('--apk-dir', type=Path, required=True)
    parser.add_argument('--scenarios',nargs='+',choices=['server','device','stale_confirmation','offline_claim','offline_pause','offline_pause_restart','offline_cancel','offline_revoke','lock_pending','lock_claim','stop_claim','profile_claim','claim_restart','receipt_restart','document_pdf','document_xlsx'],default=['server','device'])
    args = parser.parse_args()
    enforce(ROOT)
    folder = args.run_dir.resolve(); folder.mkdir(parents=True, exist_ok=False)
    ownership = json.loads(args.ownership.read_text())
    assert ownership['purpose']=='R29_TLS_HOSTING_DEVICE' and re.fullmatch(r'emulator-[0-9]+',ownership['serial'])
    avd_home=Path(ownership['avdHome']).resolve()
    assert avd_home.is_relative_to(ROOT/'.local') and (avd_home/(ownership['name']+'.ini')).is_file()
    adb = [str(ROOT / '.local/android-sdk/platform-tools/adb.exe'), '-s', ownership['serial']]
    records = []

    def run(label, command, *, data=None, timeout=120, required=None):
        started = time.time()
        try:result = subprocess.run(list(map(str, command)), input=data, cwd=ROOT, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            result=subprocess.CompletedProcess(command,124,error.stdout or b'',(error.stderr or b'')+b'\nOWNED_COMMAND_TIMEOUT')
        raw = result.stdout + b'\nSTDERR\n' + result.stderr
        (folder / (label + '.log')).write_bytes(raw)
        passed = result.returncode == 0 and (required is None or required(result.stdout))
        records.append(dict(label=label, command=list(map(str, command)), cwd=str(ROOT), exitCode=result.returncode, status='PASS' if passed else 'FAIL', seconds=round(time.time()-started, 3), log=label+'.log', sha256=hashlib.sha256(raw).hexdigest()))
        (folder / 'COMMANDS.json').write_text(json.dumps(records, indent=2))
        if not passed: raise RuntimeError('Smoke command failed: ' + label)
        return result.stdout

    run('owned-avd', adb+['emu', 'avd', 'name'], required=lambda b: b.splitlines()[0].decode()==ownership['name'])
    run('emulator-only', adb+['shell', 'getprop', 'ro.kernel.qemu'], required=lambda b: b.strip()==b'1')
    until(lambda: subprocess.run(adb+['shell','getprop','sys.boot_completed'],capture_output=True).stdout.strip()==b'1')
    run('owned-api',adb+['shell','getprop','ro.build.version.sdk'],required=lambda b:b.strip().decode()==str(ownership['api']))
    run('owned-abi',adb+['shell','getprop','ro.product.cpu.abi'],required=lambda b:b.strip().decode()==ownership['abi'])
    frozen = folder / 'selected-apks'; frozen.mkdir()
    hashes = {}
    for name, package in [('app-x86_64-debug.apk', PACKAGE), ('app-debug-androidTest.apk', PACKAGE+'.test')]:
        hashes[name] = freeze_apk(args.apk_dir/name, frozen/name)
        aapt = sorted((ROOT/'.local/android-sdk/build-tools').glob('*/aapt2.exe'))[-1]
        run('identity-'+package, [aapt,'dump','badging',frozen/name], required=lambda b,p=package: ("package: name='"+p+"'").encode() in b)
    (folder/'APK_INPUTS.json').write_text(json.dumps(hashes,indent=2))
    for name in hashes:
        run('install-'+name, adb+['install','-r',frozen/name], required=lambda b: b'Success' in b)
    for package, name in [(PACKAGE,'app-x86_64-debug.apk'),(PACKAGE+'.test','app-debug-androidTest.apk')]:
        path = run('installed-'+package, adb+['shell','pm','path',package]).decode().strip().removeprefix('package:')
        run('hash-'+package, adb+['shell','sha256sum',path], required=lambda b,n=name: b.split()[0].decode()==hashes[n])

    def reset(label):
        run(label+'-stop', adb+['shell','am','force-stop',PACKAGE])
        archive = run(label+'-archive', adb+['exec-out','run-as',PACKAGE,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'])
        with tarfile.open(fileobj=io.BytesIO(archive)): pass
        (folder/(label+'-STATE.tar')).write_bytes(archive)
        run(label+'-clear', adb+['shell','pm','clear',PACKAGE], required=lambda b: b'Success' in b)

    def instrument(method):
        return adb+['shell','am','instrument','-w','-r','-e','r27','1','-e','class',PROBE+'#'+method,RUNNER]
    def marker():
        result = subprocess.run(adb+['exec-out','run-as',PACKAGE,'cat','no_backup/r27-cloud-probe.json'],capture_output=True)
        if result.returncode != 0 or not result.stdout.startswith(b'{'): return {}
        try: return json.loads(result.stdout)
        except json.JSONDecodeError: return {}  # Only a readiness marker; final verification still requires its fields.
    def passes(b): return re.search(rb'OK \(1 test\)', b) is not None and b'FAILURES!!!' not in b

    results = []
    for label in args.scenarios:
        document_tool={'document_pdf':'render_office_pdf','document_xlsx':'recalculate_xlsx'}.get(label)
        native=label!='server' and document_tool is None;reset(label)
        private_root = folder/('private-'+label); private_root.mkdir(mode=0o700); (private_root/'.gitignore').write_text('*\n')
        authority_root = private_root/'authority'; key = private_root/'wrapping.key'
        runtime = ROOT/'server/agent-runtime'
        # Module startup requires its own cwd; use the same public init implementation.
        from hxai_agent.vault import TaskVault
        authority_root.mkdir(); TaskVault.initialize(key)
        authority = NetworkAuthority(NetworkStore(authority_root/'authority.sqlite3'))
        token = authority.enroll('r27-phone',['default-learner'])
        document_source=None;ca=None
        if document_tool:
            from verify_r29_tls import certificates
            from verify_r29_documents import Provider as DocumentProvider
            from verify_b_xlsx_readback import make_source,parts,package,X
            ca,_,_=certificates(private_root)
            original=private_root/'original.xlsx';document_source=folder/(label+'-source.xlsx');make_source(original)
            contents=parts(original);sheet=ET.fromstring(contents['xl/worksheets/sheet1.xml'])
            sheet.find(f'.//{{{X}}}c[@r="A2"]/{{{X}}}v').text='7';contents['xl/worksheets/sheet1.xml']=ET.tostring(sheet);package(document_source,contents)
            class NativeDocumentProvider(DocumentProvider):
                wire_model='synthetic-locked-model'
                secret=SECRET
                def do_POST(self):
                    if not self.server.release.wait(120):raise TimeoutError('Phone process-exit barrier was not released')
                    super().do_POST()
        provider = ThreadingHTTPServer(('127.0.0.1',0),NativeDocumentProvider if document_tool else Provider)
        provider.native=native; provider.requests=[]; provider.release=threading.Event(); provider.failed=threading.Event()
        if document_tool:provider.tool=document_tool
        if native: provider.release.set()
        thread=threading.Thread(target=provider.serve_forever,daemon=True); thread.start()
        with socket.socket() as s: s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
        server_log=(folder/(label+'-authority.log')).open('xb')
        command=[sys.executable,'-m','hxai_agent.service','serve','--root',str(authority_root),'--key-file',str(key),'--port',str(port),'--loopback-http','--worker']
        server_env=dict(os.environ,PYTHONUTF8='1')
        if ca:server_env['SSL_CERT_FILE']=str(ca)
        server=subprocess.Popen(command,cwd=runtime,stdout=server_log,stderr=subprocess.STDOUT,env=server_env)
        gateway=ThreadingHTTPServer(('127.0.0.1',0),FaultGateway)
        gateway.upstream=port;gateway.mode=label;gateway.key=secrets.token_urlsafe(32);gateway.gate_lock=threading.Lock()
        gateway.release=threading.Event();gateway.entered=threading.Event();gateway.injected=False;gateway.boundary=None;gateway.ack_lost=False
        gateway.offline=threading.Event()
        gateway_thread=threading.Thread(target=gateway.serve_forever,daemon=True);gateway_thread.start()
        phone_port=gateway.server_port
        retiring=threading.Event(); retire_errors=[]
        def retire_when_completed():
            while not retiring.wait(.1):
                try:
                    with authority.store.transaction() as db:
                        complete=db.execute("SELECT 1 FROM tasks WHERE state='SUCCEEDED'").fetchone()
                    if complete:
                        authority.domain.retire(); return
                except Conflict: pass
                except Exception as e: retire_errors.append(type(e).__name__); return
        retire_thread=threading.Thread(target=retire_when_completed,daemon=True); retire_thread.start()
        pin_set=False;old_lock_timeout=None
        document_process=None;document_log=None
        try:
            def ready():
                if server.poll() is not None: raise RuntimeError('Owned authority exited')
                try:
                    with socket.create_connection(('127.0.0.1',port),timeout=.2): return True
                except OSError: return False
            until(ready,20)
            document_identity=None;document_port=None
            if document_tool:
                with socket.socket() as s:s.bind(('127.0.0.1',0));document_port=s.getsockname()[1]
                document_root=private_root/'document-service';document_log=(folder/(label+'-document-service.log')).open('xb')
                document_process=subprocess.Popen([sys.executable,'scripts/verify_b_xlsx_service.py','--run-dir',str(document_root),'--port',str(document_port),
                    '--cert-file',str(private_root/'leaf.pem'),'--tls-key-file',str(private_root/'leaf.key')],cwd=ROOT,stdout=document_log,stderr=subprocess.STDOUT)
                def document_ready():
                    if document_process.poll() is not None:raise RuntimeError('Owned TLS document service exited')
                    return (document_root/'fixture-identities.json').is_file()
                until(document_ready,40);document_identity=json.loads((document_root/'fixture-identities.json').read_text())
            run(label+'-reverse',adb+['reverse','tcp:'+str(phone_port),'tcp:'+str(phone_port)])
            config=dict(endpoint=f'http://127.0.0.1:{phone_port}',token=token,secret=SECRET,providerBase=f'http://127.0.0.1:{provider.server_port}/v1',
                faultMode=label,faultEndpoint=f'http://127.0.0.1:{phone_port}',faultKey=gateway.key)
            if document_tool:
                payload=document_source.read_bytes()
                config.update(documentEndpoint=f'https://localhost:{document_port}',documentToken=document_identity['token'],documentTool=document_tool,
                    sourceBytes=base64.b64encode(payload).decode(),sourceSha256=hashlib.sha256(payload).hexdigest())
            if label.startswith('lock_'):
                old_lock_timeout=run(label+'-previous-lock-timeout',adb+['shell','settings','get','secure','lock_screen_lock_after_timeout']).decode().strip()
                run(label+'-pin',adb+['shell','locksettings','set-pin','7351'],required=lambda b:b'pin set' in b.lower());pin_set=True
                run(label+'-lock-timeout',adb+['shell','settings','put','secure','lock_screen_lock_after_timeout','0'])
            run(label+'-fixture-directory',adb+['shell','run-as',PACKAGE,'mkdir','-p','no_backup'])
            run(label+'-fixture',adb+['shell','run-as',PACKAGE,'sh','-c','"cat > no_backup/r27-test-input.json"'],data=canonical(config).encode())
            if native and label not in {'claim_restart','receipt_restart','offline_pause_restart'}:
                run(label+'-instrumentation',instrument('deviceLifecycle'),timeout=300,required=passes)
            elif native:
                phone_log=(folder/(label+'-phone-before-exit.log')).open('xb')
                phone=subprocess.Popen(instrument('deviceLifecycle' if label=='offline_pause_restart' else 'prepareDeviceRecovery'),cwd=ROOT,stdout=phone_log,stderr=subprocess.STDOUT)
                try:
                    def boundary_ready():
                        if phone.poll() is not None:raise RuntimeError('Phone recovery preparation exited')
                        value=marker();return value if value.get('ready') and gateway.entered.is_set() else None
                    saved=until(boundary_ready,120)
                    run(label+'-phone-original-pid',adb+['shell','pidof',PACKAGE],required=lambda b:bool(b.strip()))
                    run(label+'-phone-force-stop',adb+['shell','am','force-stop',PACKAGE])
                    assert subprocess.run(adb+['shell','pidof',PACKAGE],capture_output=True).stdout.strip()==b''
                    gateway.offline.clear();gateway.release.set();phone.wait(timeout=20)
                    run(label+'-phone-restarted-settlement',instrument('verifyDeviceRecovery'),timeout=240,required=passes)
                finally:
                    if phone.poll() is None:run(label+'-phone-emergency-stop',adb+['shell','am','force-stop',PACKAGE]);phone.wait(timeout=20)
                    phone_log.close()
            else:
                phone_log=(folder/(label+'-phone-before-exit.log')).open('xb')
                phone=subprocess.Popen(instrument('prepareCloudDocument' if document_tool else 'prepareServerTask'),cwd=ROOT,stdout=phone_log,stderr=subprocess.STDOUT)
                try:
                    def phone_ready():
                        if phone.poll() is not None: raise RuntimeError('Phone preparation failed; see phone-before-exit.log')
                        value=marker()
                        return value if value.get('ready') else None
                    saved=until(phone_ready,90)
                    pid=run(label+'-phone-original-pid',adb+['shell','pidof',PACKAGE]).decode().strip(); assert pid
                    run(label+'-phone-force-stop',adb+['shell','am','force-stop',PACKAGE])
                    assert subprocess.run(adb+['shell','pidof',PACKAGE],capture_output=True).stdout.strip()==b''
                    provider.release.set()
                    phone.wait(timeout=20)
                    until(lambda: authority.domain.status()['retired'],90)
                    run(label+'-phone-restarted-readback',instrument('verifyCloudDocument' if document_tool else 'verifyServerTask'),timeout=180,required=passes)
                finally:
                    if phone.poll() is None: run(label+'-phone-emergency-stop',adb+['shell','am','force-stop',PACKAGE]);phone.wait(timeout=20)
                    phone_log.close()
            assert not provider.failed.is_set() and not retire_errors
            saved=marker(); assert saved.get('deviceVerified' if native else 'verified') is True
            (folder/(label+'-MARKER.json')).write_text(json.dumps(saved,indent=2))
            surface='cloud-document-success' if document_tool else 'device-recovered' if label in {'claim_restart','receipt_restart','offline_pause_restart'} else 'device-waiting' if native else 'server-success'
            for extension in ['png','txt']:
                data=run(label+'-surface-'+extension,adb+['exec-out','run-as',PACKAGE,'cat','no_backup/r27-'+surface+'.'+extension])
                assert data.startswith(b'\x89PNG') if extension=='png' else ('权威状态：'+('SUCCEEDED' if not native or label in {'claim_restart','receipt_restart','offline_pause_restart'} else 'WAITING_DEVICE')).encode() in data
                (folder/(label+'-'+surface+'.'+extension)).write_bytes(data)
            if label=='stale_confirmation':
                for extension in ['png','txt']:
                    data=run(label+'-confirmation-'+extension,adb+['exec-out','run-as',PACKAGE,'cat','no_backup/r29-device-confirmation.'+extension])
                    (folder/(label+'-confirmation.'+extension)).write_bytes(data)
            with authority.store.transaction() as db:
                processes=[dict(r) for r in db.execute('SELECT * FROM net_processes')]
                assert all(p['state']=='STOPPED' and p['pid']!=server.pid for p in processes)
                assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0] is None
            assert all(r['model']=='synthetic-locked-model' and r['reasoning']=='high' for r in provider.requests)
            request_count=2 if saved.get('safetyOnly') else 4
            assert len(provider.requests)==request_count and len({r['requestId'] for r in provider.requests})==request_count
            state=run(label+'-final-state',adb+['exec-out','run-as',PACKAGE,'tar','--exclude=./app_flutter','--exclude=./app_dxmaker_cache','-cf','-','.'])
            (folder/(label+'-FINAL-STATE.tar')).write_bytes(state)
            if saved.get('safetyOnly'):
                action=saved['safetyOnly']
                with authority.store.transaction() as db:
                    task=dict(db.execute("SELECT * FROM tasks WHERE id=?",(gateway.boundary['taskId'],)).fetchone())
                    assert task['state']=='CANCELED' if action=='cancel' else task['authorization']=='REVOKED'
                    assert db.execute("SELECT state FROM calls WHERE task_id=?",(task['id'],)).fetchone()[0]=='FAILED_SAFE'
                with tarfile.open(fileobj=io.BytesIO(state)) as archive:
                    assert not any(m.name.endswith('.docx') for m in archive.getmembers())
            elif native or document_tool:
                artifact_id=saved['nativeDocxId'] if native else saved['nativeArtifactId']
                extension='docx' if native else saved['outputExtension']
                expected_hash=saved['nativeDocxSha256'] if native else saved['nativeArtifactSha256']
                with tarfile.open(fileobj=io.BytesIO(state)) as archive:
                    member=next(m for m in archive.getmembers() if m.name.endswith('/'+artifact_id+'.'+extension))
                    actual=archive.extractfile(member).read()
                    if document_tool:
                        original_member=next(m for m in archive.getmembers() if m.name.endswith('/'+saved['nativeSourceId']+'.xlsx'))
                        assert hashlib.sha256(archive.extractfile(original_member).read()).hexdigest()==saved['sourceSha256']
                assert hashlib.sha256(actual).hexdigest()==expected_hash
                output=folder/(label+'-imported.'+extension);output.write_bytes(actual)
                if extension=='docx':
                    with ZipFile(io.BytesIO(actual)) as archive:
                        assert 'R27 native verified document' in ''.join(ET.fromstring(archive.read('word/document.xml')).itertext())
                elif extension=='pdf':
                    from pypdf import PdfReader
                    assert 'Count' in '\n'.join(p.extract_text() for p in PdfReader(output,strict=True).pages)
                else:
                    with ZipFile(io.BytesIO(actual)) as archive:
                        sheet=ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
                        assert float(sheet.find(f'.//{{{X}}}c[@r="B2"]/{{{X}}}v').text)==21
            expected_fault=label in {'lock_claim','stop_claim','profile_claim','claim_restart','receipt_restart'} or label.startswith('offline_')
            assert gateway.injected==expected_fault
            results.append(dict(scenario=label,status='PASS',authorityPid=server.pid,workerPids=[p['pid'] for p in processes],
                requests=provider.requests,marker=saved,faultBoundary=gateway.boundary,ackLost=gateway.ack_lost))
        finally:
            run(label+'-owned-phone-stop',adb+['shell','am','force-stop',PACKAGE])
            gateway.release.set()
            if pin_set:
                run(label+'-wake',adb+['shell','input','keyevent','KEYCODE_WAKEUP'])
                run(label+'-clear-owned-pin',adb+['shell','locksettings','clear','--old','7351'],required=lambda b:b'cleared' in b.lower())
                run(label+'-dismiss-unsecured-keyguard',adb+['shell','wm','dismiss-keyguard'])
                command=['delete','secure','lock_screen_lock_after_timeout'] if old_lock_timeout=='null' else ['put','secure','lock_screen_lock_after_timeout',old_lock_timeout]
                run(label+'-restore-lock-timeout',adb+['shell','settings',*command])
            retiring.set();retire_thread.join(timeout=5);provider.release.set()
            if server.poll() is None: server.terminate();server.wait(timeout=30)
            server_log.close();provider.shutdown();provider.server_close();thread.join(timeout=5)
            gateway.shutdown();gateway.server_close();gateway_thread.join(timeout=5)
            if document_process and document_process.poll() is None:document_process.terminate();document_process.wait(timeout=35)
            if document_log:document_log.close()
            run(label+'-reverse-remove',adb+['reverse','--remove','tcp:'+str(phone_port)])
    (folder/'RESULT.json').write_text(json.dumps(dict(status='PASS_SCOPED',scope='Actual Android/Host, independent cloud worker, synthetic Provider; cross-App/real Provider/production not evaluated',results=results),indent=2))
    print('PASS_SCOPED: actual phone exit/cloud Markdown and foreground/native DOCX readback')
    return 0


if __name__=='__main__': raise SystemExit(main())
