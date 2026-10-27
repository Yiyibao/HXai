"""Synthetic XLSX Chat -> real loopback gateway/UNO -> Android readback smoke.

Requires installed Windows SDK/LibreOffice, a running exclusively owned emulator,
and existing build dependencies. Never connects to production or a paid model.
Archives QA data before resetting only org.hxsi.agent.nativeqa. New run-dir required.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from verify_b_xlsx_readback import make_source, parts, package, X

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'org.hxsi.agent.nativeqa'
RUNNER = PACKAGE + '.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
SCENARIOS = {
    'stable': 'realChatEditsApprovesRecalculatesAndDeliversEditableVersion',
    'witness': 'witnessChangedDuringSemanticAcceptanceCannotSucceed',
    'source': 'sourceChangedDuringSemanticAcceptanceCannotSucceed',
    'cancel': 'cancellationDuringSemanticAcceptanceCannotSucceed',
    'revoke': 'revocationDuringSemanticAcceptanceCannotSucceed',
    'downloadAck': 'downloadedCommitWithoutChatAckResumesSameVersion',
    'forged': 'forgedWitnessCannotPublishAnArtifact',
    'missing': 'missingWitnessCannotPublishAnArtifact',
    # Retain UNKNOWN and the execution slot; this scenario must be last.
    'uploadAck': 'uploadAckLossKeepsUnknownAndNeverReuploads',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--port', type=int, default=18869)
    parser.add_argument('--build', action='store_true', help='Build current Flutter AAR and isolated native QA APKs offline')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only an exclusively owned synthetic emulator is permitted')
    folder = Path(args.run_dir).resolve()
    folder.mkdir(parents=True, exist_ok=False)
    logs = folder / 'logs'; logs.mkdir()
    adb = [ROOT / '.local/android-sdk/platform-tools/adb.exe', '-s', args.serial]
    env = os.environ.copy(); env['PYTHONUTF8'] = '1'
    env.setdefault('JAVA_HOME', r'C:\Program Files\Java\jdk-21')
    records = []

    def run(name, command, cwd=ROOT, timeout=2400, instrumentation=False):
        started = time.monotonic()
        result = subprocess.run(list(map(str, command)), cwd=cwd, env=env, capture_output=True, timeout=timeout)
        text = (result.stdout + result.stderr).decode('utf-8', errors='replace')
        log = logs / (name + '.log')
        log.write_text('$ ' + subprocess.list2cmdline(list(map(str, command))) + '\nCWD: ' + str(cwd) + '\n' + text + '\nexit_code=' + str(result.returncode), encoding='utf-8')
        passed = result.returncode == 0 and (not instrumentation or ('OK (1 test)' in text and 'FAILURES!!!' not in text))
        records.append(dict(name=name, command=list(map(str, command)), cwd=str(cwd), exit_code=result.returncode,
            status='PASS' if passed else 'FAIL', seconds=round(time.monotonic() - started, 3),
            log=str(log.relative_to(folder)), sha256=hashlib.sha256(log.read_bytes()).hexdigest()))
        (folder / 'TEST_RUNS.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        print(name, records[-1]['status'], flush=True)
        if not passed:
            raise RuntimeError('Verification failed; see ' + str(log))
        return text

    def archive(label, clear=False):
        run(label + '-stop', adb + ['shell', 'am', 'force-stop', PACKAGE], timeout=30)
        result = subprocess.run(list(map(str, adb + ['exec-out', 'run-as', PACKAGE, 'tar',
            '--exclude=./app_flutter', '--exclude=./app_dxmaker_cache', '-cf', '-', '.'])), capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError('QA archive failed; existing state preserved')
        target = folder / (label + '-SYNTHETIC_STATE.tar'); target.write_bytes(result.stdout)
        if clear:
            run(label + '-clear-archived-qa', adb + ['shell', 'pm', 'clear', PACKAGE], timeout=30)

    if run('emulator-only', adb + ['shell', 'getprop', 'ro.kernel.qemu'], timeout=30).strip() != '1':
        raise RuntimeError('Not a synthetic emulator')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    if args.build:
        run('flutter-aar', [ROOT / '.local/flutter/bin/flutter.bat', 'build', 'aar', '--debug', '--no-release', '--no-profile', '--no-pub'], ROOT / 'client/flutter_ui')
        run('native-build', [ROOT / '.local/gradle-9.6.0/bin/gradle.bat', '-p', ROOT / 'client', ':app:assembleDebug',
            ':app:assembleDebugAndroidTest', '-PagentNativeTest=true', '--offline', '--console=plain'])
    apks = [ROOT / 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk', ROOT / 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    (folder / 'APK_INPUTS.json').write_text(json.dumps({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in apks}, indent=2))
    for n, apk in enumerate(apks):
        run('install-' + str(n), adb + ['install', '-r', apk], timeout=180)
    archive('previous', clear=True)
    sources = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
        for base in ['client/app/src', 'client/ai/src', 'client/flutter_ui/lib', 'server/document-service', 'scripts']
        for p in (ROOT / base).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix in {'.kt', '.dart', '.py'}}
    (folder / 'SOURCE_INPUTS.json').write_text(json.dumps(sources, indent=2))
    gateway_log = (logs / 'gateway.log').open('wb')
    gateway = subprocess.Popen([sys.executable, ROOT / 'scripts/verify_b_xlsx_service.py', '--run-dir', folder / 'gateway', '--port', str(args.port)],
        cwd=ROOT, env=env, stdout=gateway_log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 45
        while True:
            if gateway.poll() is not None:
                raise RuntimeError('Gateway failed; see gateway.log')
            try:
                with socket.create_connection(('127.0.0.1', args.port), timeout=0.5):
                    break
            except OSError:
                if time.monotonic() > deadline:
                    raise RuntimeError('Gateway readiness timeout')
                time.sleep(0.2)
        sys.path.insert(0, str(ROOT / 'server/document-service'))
        from api.store import Store
        store = Store(folder / 'gateway/data')
        fixture = {'port': args.port, 'tokens': {mode: store.issue()[1] for mode in SCENARIOS}}
        fixture_path = folder / 'fixture.json'; fixture_path.write_text(json.dumps(fixture))
        original = folder / 'original.xlsx'; make_source(original)
        contents = parts(original); sheet = ET.fromstring(contents['xl/worksheets/summary9.xml'])
        row = sheet.find(f'{{{X}}}sheetData/{{{X}}}row')
        cell = ET.SubElement(row, f'{{{X}}}c', {'r': 'F1'})
        ET.SubElement(cell, f'{{{X}}}f').text = '0'; ET.SubElement(cell, f'{{{X}}}v').text = '0'
        contents['xl/worksheets/summary9.xml'] = ET.tostring(sheet)
        source = folder / 'source.xlsx'; package(source, contents)
        run('reverse', adb + ['reverse', 'tcp:' + str(args.port), 'tcp:' + str(args.port)], timeout=30)
        run('fixture-directory', adb + ['shell', 'run-as', PACKAGE, 'mkdir', '-p', 'cache'], timeout=30)
        for kind, file in [('source.xlsx', source), ('fixture.json', fixture_path)]:
            target = '/data/local/tmp/xlsx-chat-' + kind
            run('push-' + kind, adb + ['push', file, target], timeout=30)
            run('copy-' + kind, adb + ['shell', 'run-as', PACKAGE, 'cp', target, 'cache/xlsx-chat-' + kind], timeout=30)
        for mode, method in SCENARIOS.items():
            run(mode, adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'me.rerere.rikkahub.feature.agent.XlsxSharedChatRuntimeTest#' + method, RUNNER], timeout=300, instrumentation=True)
        archive('final')
    finally:
        # The smoke owns this gateway and all its workers; preserve logs/data before stopping.
        subprocess.run(['taskkill', '/PID', str(gateway.pid), '/T', '/F'], capture_output=True, timeout=30)
        gateway.wait(timeout=30); gateway_log.close()
        run('remove-owned-reverse', adb + ['reverse', '--remove', 'tcp:' + str(args.port)], timeout=30)


if __name__ == '__main__':
    main()
