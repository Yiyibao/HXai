"""Observe real QA process exits during immutable evidence publication.

No model calls, dependency installation, clearing, or changes to original runs.
Requires this task's owned synthetic emulator and already installed pinned APKs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'org.hxsi.agent.nativeqa'
RUNNER = PACKAGE + '.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
PROBE = 'me.rerere.rikkahub.feature.agent.QualityEvidenceProcessProbe'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ownership', type=Path, required=True)
    parser.add_argument('--apk-inputs', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    ownership = json.loads(args.ownership.read_text(encoding='utf-8'))
    assert ownership['purpose'] == 'R30_QUALITY_COMPLETION_SYNTHETIC_ONLY' and ownership['createdForThisTask'] is True
    assert re.fullmatch(r'emulator-[0-9]+|127\.0\.0\.1:[0-9]+', args.serial)
    # A loopback transport is only the explicitly owned emulator's ADB port.
    console_port = int(ownership['serial'].removeprefix('emulator-'))
    assert args.serial in (ownership['serial'], f'127.0.0.1:{console_port + 1}')
    out = args.out.resolve();out.mkdir(parents=True, exist_ok=False)
    hashes = json.loads(args.apk_inputs.read_text(encoding='utf-8'))
    adb = [str(ROOT / '.local/android-sdk/platform-tools/adb.exe'), '-s', args.serial]
    records = []

    def run(label, command, accept=lambda r: r.returncode == 0, binary=False):
        started = time.time()
        result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, timeout=90)
        raw = result.stdout + b'\nSTDERR\n' + result.stderr
        suffix = '.tar' if binary else '.log'
        path = out / (label + suffix)
        with path.open('xb') as stream:
            stream.write(result.stdout if binary else raw)
        passed = accept(result)
        records.append({'label': label, 'command': list(map(str, command)), 'cwd': str(ROOT),
                        'exitCode': result.returncode, 'seconds': time.time() - started,
                        'status': 'PASS' if passed else 'FAIL', 'artifact': path.name,
                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        (out / 'COMMANDS.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        assert passed, 'owned_process_probe_failed:' + label
        return result

    run('qemu', adb + ['shell', 'getprop', 'ro.kernel.qemu'], lambda r: r.stdout.strip() == b'1')
    run('avd', adb + ['shell', 'getprop', 'ro.boot.qemu.avd_name'], lambda r:
        r.returncode == 0 and r.stdout.decode().strip() == ownership['name'])
    for package, suffix in [(PACKAGE, 'app-x86_64-debug.apk'), (PACKAGE + '.test', 'app-debug-androidTest.apk')]:
        remote = run(suffix + '-path', adb + ['shell', 'pm', 'path', package]).stdout.decode().strip()
        assert remote.startswith('package:') and '\n' not in remote
        expected = next(v for k, v in hashes.items() if k.endswith(suffix))
        run(suffix + '-sha256', adb + ['shell', 'sha256sum', remote.removeprefix('package:')],
            lambda r: r.returncode == 0 and r.stdout.decode().split()[0] == expected)
    cases = []
    for point in ('after_half_write', 'after_file_sync', 'after_publish'):
        fixture = str(uuid.uuid4())
        base = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'evidenceFixture', fixture,
                      '-e', 'evidencePoint', point, '-e', 'class']
        run(point + '-expected-process-exit', base + [PROBE + '#prepare', RUNNER], lambda _: True)
        ready = run(point + '-durable-ready', adb + ['exec-out', 'run-as', PACKAGE, 'cat',
            f'no_backup/qa-evidence-process/{fixture}/READY']).stdout.decode().strip()
        assert re.fullmatch(point + ':[0-9]+', ready)
        old_pid = ready.split(':')[1]
        run(point + '-old-process-exited', adb + ['shell', 'pidof', PACKAGE], lambda r:
            r.returncode in (0, 1) and old_pid not in r.stdout.decode().split())
        run(point + '-reopened-verification', base + [PROBE + '#verify', RUNNER], lambda r:
            r.returncode == 0 and b'OK (1 test)' in r.stdout and b'FAILURES!!!' not in r.stdout
            and b'test=verify' in r.stdout and PROBE.encode() in r.stdout)
        run(point + '-public-fixture', adb + ['exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-',
            f'no_backup/qa-evidence-process/{fixture}'], binary=True)
        cases.append({'checkpoint': point, 'fixture': fixture, 'actualProcessExit': True,
                      'reopenedVerification': 'PASS', 'oldPid': old_pid})
    (out / 'RESULT.json').write_text(json.dumps({'status': 'PASS', 'cases': cases,
        'scope': 'NATIVEQA_IMMUTABLE_EVIDENCE_PROCESS_EXIT_ONLY',
        'apkInputsSha256': hashlib.sha256(args.apk_inputs.read_bytes()).hexdigest(),
        'writerSha256': hashlib.sha256((ROOT / 'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/QualityEvidenceFiles.kt').read_bytes()).hexdigest()}, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'actualProcessExits': len(cases)}))


if __name__ == '__main__':
    main()
