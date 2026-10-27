"""Exercise frozen domain APKs on an explicitly owned emulator/API, without weakening tests.
No build, credentials, model selection, real-device access or production-package mutation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'org.hxsi.agent.nativeqa'
DEFAULT_CLASSES = ','.join('me.rerere.rikkahub.feature.material.' + name for name in [
    'OfficeSafetyRuntimeTest', 'MaterialLibraryLifecycleRuntimeTest',
    'MaterialMathQualityRuntimeTest', 'MaterialLibrarySurfaceRuntimeTest'])

def status_packets(text):
    """Instrumentation fields are a map terminated by STATUS_CODE, not an ordered regex."""
    packet = {}
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                packet[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            yield int(line.partition(': ')[2]), packet
            packet = {}


def instrumentation_methods(text):
    methods = [fields['class'] + '#' + fields['test'] for code, fields in status_packets(text)
               if code == 1 and 'class' in fields and 'test' in fields]
    count = re.search(r'OK \((\d+) tests?\)', text)
    if (not methods or count is None or len(methods) != int(count[1]) or len(set(methods)) != len(methods) or
            any(not re.fullmatch(r'[\w.$]+#[\w]+', item) for item in methods)):
        raise ValueError('Discovery does not contain an unambiguous method set matching its count')
    return methods


def method_outcome(text, method):
    outcomes = [code for code, fields in status_packets(text)
                if code != 1 and fields.get('class', '') + '#' + fields.get('test', '') == method]
    if outcomes == [-3] or outcomes == [-4]:
        return 'SKIP'
    if (outcomes == [0] and re.search(r'OK \(1 tests?\)', text) and
            'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text):
        return 'PASS'
    return 'FAIL'



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--api', type=int, required=True)
    parser.add_argument('--ownership', type=Path, required=True)
    parser.add_argument('--apk-inputs', type=Path, required=True)
    parser.add_argument('--source-inputs', type=Path, required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--classes', default=DEFAULT_CLASSES)
    args = parser.parse_args()
    args.run_dir = args.run_dir.resolve()
    args.run_dir.mkdir(parents=True, exist_ok=False)
    records = []
    result = {'status': 'BLOCKED', 'mutations_started': False}
    adb = [str(ROOT / '.local/android-sdk/platform-tools/adb.exe'), '-s', args.serial]

    def run(name, command, timeout=120):
        started = time.time()
        try:
            completed = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, timeout=timeout)
            raw = completed.stdout + b'\nSTDERR\n' + completed.stderr
            code = completed.returncode
        except subprocess.TimeoutExpired as error:
            raw = (error.stdout or b'') + b'\nSTDERR\n' + (error.stderr or b'') + b'\nTIMEOUT\n'
            code = 124
        log = args.run_dir / (name + '.log')
        log.write_bytes(raw)
        records.append(dict(name=name, command=list(map(str, command)), cwd=str(ROOT),
            exit_code=code, seconds=round(time.time()-started, 3), log=log.name,
            sha256=hashlib.sha256(raw).hexdigest()))
        (args.run_dir / 'TEST_RUNS.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        text = raw.decode('utf-8', errors='replace')
        print(name, code, text[-1800:], flush=True)
        return code, completed.stdout if code != 124 else b'', text

    try:
        if not re.fullmatch(r'emulator-\d+', args.serial):
            raise ValueError('Only an explicitly owned emulator is allowed')
        if not re.fullmatch(r'[A-Za-z0-9_-]+', args.run_dir.name):
            raise ValueError('Run directory basename must be a plain unique identifier')
        ownership = json.loads(args.ownership.read_text(encoding='utf-8'))
        if ownership['serial'] != args.serial or ownership['api'] != args.api or not ownership['created_this_batch']:
            raise ValueError('Ownership record does not authorize this serial/API')
        code, raw, _ = run('emulator-only', adb + ['shell', 'getprop', 'ro.kernel.qemu'])
        if code or raw.strip() != b'1':
            raise ValueError('Not an emulator')
        code, raw, _ = run('actual-api', adb + ['shell', 'getprop', 'ro.build.version.sdk'])
        if code or raw.strip() != str(args.api).encode():
            raise ValueError('Actual API differs from requested API')
        code, raw, _ = run('owned-avd', adb + ['emu', 'avd', 'name'])
        if code or raw.decode(errors='replace').splitlines()[0] != ownership['avd']:
            raise ValueError('AVD name differs from ownership record')
        code, raw, _ = run('actual-abi', adb + ['shell', 'getprop', 'ro.product.cpu.abi'])
        if code or raw.strip().decode() != ownership['abi']:
            raise ValueError('Actual ABI differs from ownership record')
        inputs = json.loads(args.apk_inputs.read_text(encoding='utf-8'))
        apks = [ROOT / 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk',
                ROOT / 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
        checked = {}
        for apk, expected_package in zip(apks, [PACKAGE, PACKAGE + '.test']):
            key = str(apk.relative_to(ROOT))
            expected = inputs.get(key, inputs.get(key.replace('\\', '/')))
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            if expected != digest:
                raise ValueError('APK bytes do not match frozen input: ' + key)
            code, raw, _ = run('manifest-' + expected_package, [ROOT / '.local/android-sdk/build-tools/37.0.0/aapt2.exe', 'dump', 'badging', apk])
            package = re.search(r"^package: name='([^']+)'", raw.decode(errors='replace'), re.MULTILINE)
            if code or package is None or package[1] != expected_package:
                raise ValueError('APK is not the dedicated QA package')
            checked[key] = digest
        source_inputs = json.loads(args.source_inputs.read_text(encoding='utf-8'))
        for relative, expected in source_inputs.items():
            path = ROOT / relative
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError('Frozen source changed: ' + relative)
        (args.run_dir / 'FROZEN_INPUTS.json').write_text(json.dumps(dict(apks=checked,
            source_manifest_sha256=hashlib.sha256(args.source_inputs.read_bytes()).hexdigest(),
            ownership=ownership), indent=2), encoding='utf-8')
        code, raw, text = run('existing-qa-package', adb + ['shell', 'pm', 'path', PACKAGE])
        installed = code == 0 and raw.startswith(b'package:')
        # Android 8 returns 1 for an absent package; this is not an execution PASS or a transport failure.
        absent = code in (0, 1) and not raw.strip() and text.endswith('STDERR\n')
        if not installed and not absent:
            raise ValueError('Unexpected package query failure; no data cleared')
        result['mutations_started'] = True
        if installed:
            code, _, _ = run('stop-owned-qa', adb + ['shell', 'am', 'force-stop', PACKAGE])
            if code:
                raise RuntimeError('Cannot stop owned QA before preserving it')
            archive = subprocess.run(adb + ['exec-out', 'run-as', PACKAGE, 'tar',
                '--exclude=./app_flutter', '--exclude=./app_dxmaker_cache', '-cf', '-', '.'], cwd=ROOT, capture_output=True, timeout=60)
            if archive.returncode:
                raise RuntimeError('Cannot archive previous synthetic QA; no clear performed')
            (args.run_dir / 'PREVIOUS_SYNTHETIC_STATE.tar').write_bytes(archive.stdout)
        for index, apk in enumerate(apks):
            code, raw, _ = run('preinstall-' + str(index), adb + ['install', '-r', apk])
            if code or b'Success' not in raw:
                raise RuntimeError('QA APK install failed on actual API')
        def preserve(label, clear):
            code, _, _ = run(label + '-stop', adb + ['shell', 'am', 'force-stop', PACKAGE])
            if code:
                raise RuntimeError('Cannot stop QA; state not cleared')
            archived = subprocess.run(adb + ['exec-out', 'run-as', PACKAGE, 'tar',
                '--exclude=./app_flutter', '--exclude=./app_dxmaker_cache', '-cf', '-', '.'],
                cwd=ROOT, capture_output=True, timeout=60)
            archive_path = args.run_dir / (label + '-SYNTHETIC_STATE.tar')
            archive_path.write_bytes(archived.stdout)
            if archived.returncode:
                raise RuntimeError('Cannot preserve UNKNOWN/receipts; state not cleared')
            if clear:
                code, raw, _ = run(label + '-clear', adb + ['shell', 'pm', 'clear', PACKAGE])
                if code or b'Success' not in raw:
                    raise RuntimeError('Cannot reset owned synthetic QA')

        runner = PACKAGE + '.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
        preserve('before-discovery', clear=True)
        code, _, text = run('discover', adb + ['shell', 'am', 'instrument', '-w', '-r',
            '-e', 'log', 'true', '-e', 'class', args.classes, runner])
        if code:
            raise RuntimeError('Discovery transport failed; QA state preserved')
        methods = instrumentation_methods(text)
        (args.run_dir / 'DISCOVERED_TESTS.json').write_text(json.dumps(methods, indent=2), encoding='utf-8')
        executed = []
        for index, method in enumerate(methods):
            label = 'method-' + str(index)
            preserve(label, clear=True)
            code, _, text = run(label + '-instrumentation', adb + ['shell', 'am', 'instrument',
                '-w', '-r', '-e', 'class', method, runner], timeout=180)
            outcome = method_outcome(text, method) if code == 0 else 'FAIL'
            executed.append(dict(method=method, status=outcome, log=label + '-instrumentation.log'))
            (args.run_dir / 'EXECUTED_TESTS.json').write_text(json.dumps(executed, indent=2), encoding='utf-8')
            if code:
                preserve('transport-failure', clear=False)
                raise RuntimeError('Instrumentation transport failed; original state preserved')
        preserve('final', clear=False)
        result.update(api=args.api, abi=ownership['abi'], methods=executed,
            scope='Actual frozen QA APK domain methods on the explicitly owned emulator; not real provider/release/production')
        if any(item['status'] != 'PASS' for item in executed):
            raise RuntimeError('Actual method FAIL/SKIP; discovery and ADB exit0 are not acceptance')
        result['status'] = 'PASS'
    except Exception as error:
        result.update(status='FAIL' if result['mutations_started'] else 'BLOCKED', error=str(error))
    finally:
        (args.run_dir / 'RESULT.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result), flush=True)
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
