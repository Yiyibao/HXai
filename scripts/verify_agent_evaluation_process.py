"""Actual export-process exits and independent reopened readback on an owned QA emulator.

No install, clear, dependency download, model calls or user-package operations.
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
PROBE = 'me.rerere.rikkahub.feature.agent.AgentEvaluationDeathProbe'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ownership', type=Path, required=True)
    parser.add_argument('--native-run', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    ownership = json.loads(args.ownership.read_text(encoding='utf-8'))
    assert ownership['purpose'] == 'R29_TLS_HOSTING_DEVICE' and re.fullmatch('emulator-[0-9]+', ownership['serial'])
    home = Path(ownership['avdHome']).resolve()
    assert home.is_relative_to(ROOT / '.local') and (home / (ownership['name'] + '.ini')).is_file()
    native = args.native_run.resolve()
    assert native.is_relative_to(ROOT / 'HXai_CHANGE_REVIEW')
    source = {key.replace('\\', '/'): value for key, value in
        json.loads((native / 'SOURCE_INPUTS.json').read_text(encoding='utf-8')).items()}
    for name in ('AgentEvaluationSnapshot.kt', 'AgentEvaluationExport.kt'):
        path = 'client/app/src/main/java/me/rerere/rikkahub/feature/agent/' + name
        assert source[path] == hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), 'native export source changed'
    inputs = json.loads((native / 'APK_INPUTS.json').read_text(encoding='utf-8'))
    output = args.out.resolve();output.mkdir(parents=True, exist_ok=False)
    adb = [str(ROOT / '.local/android-sdk/platform-tools/adb.exe'), '-s', ownership['serial']]
    records = []
    def run(label, command, accept=lambda r: r.returncode == 0):
        started = time.time()
        result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, timeout=120)
        raw = result.stdout + b'\nSTDERR\n' + result.stderr
        with (output / (label + '.log')).open('xb') as f:
            f.write(raw)
        passed = accept(result)
        records.append({'label': label, 'command': list(map(str, command)), 'cwd': str(ROOT), 'exitCode': result.returncode,
                        'seconds': time.time() - started, 'status': 'PASS' if passed else 'FAIL', 'logSha256': hashlib.sha256(raw).hexdigest()})
        (output / 'COMMANDS.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        assert passed, 'owned_qa_probe_failed:' + label
        return result
    run('avd', adb + ['emu', 'avd', 'name'], lambda r: r.returncode == 0 and r.stdout.splitlines()[0].decode() == ownership['name'])
    run('qemu', adb + ['shell', 'getprop', 'ro.kernel.qemu'], lambda r: r.stdout.strip() == b'1')
    run('api', adb + ['shell', 'getprop', 'ro.build.version.sdk'], lambda r: r.stdout.strip().decode() == str(ownership['api']))
    for package, filename in [(PACKAGE, 'app-x86_64-debug.apk'), (PACKAGE + '.test', 'app-debug-androidTest.apk')]:
        remote = run(filename + '-path', adb + ['shell', 'pm', 'path', package]).stdout.decode().strip()
        assert remote.startswith('package:') and '\n' not in remote
        run(filename + '-readback', adb + ['pull', remote.removeprefix('package:'), output / filename])
        expected = next(v for k,v in inputs.items() if k.endswith(filename))
        assert hashlib.sha256((output / filename).read_bytes()).hexdigest() == expected, 'installed APK changed'
    cases = []
    for point in ('after_intent', 'after_move'):
        fixture = str(uuid.uuid4())
        base = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'fixture', fixture, '-e', 'checkpoint', point, '-e', 'class']
        run(point + '-prepare-expected-process-exit', base + [PROBE + '#prepare', RUNNER], lambda _: True)
        ready = run(point + '-durable-ready', adb + ['exec-out', 'run-as', PACKAGE, 'cat', f'no_backup/qa-evaluation-process/{fixture}/ready']).stdout.decode().strip()
        assert re.fullmatch(point + ':[0-9]+', ready)
        old_pid = ready.split(':')[1]
        run(point + '-old-process-exited', adb + ['shell', 'pidof', PACKAGE], lambda r: r.returncode in (0,1) and old_pid not in r.stdout.decode().split())
        run(point + '-reopened-verification', base + [PROBE + '#verify', RUNNER], lambda r:
            r.returncode == 0 and b'OK (1 test)' in r.stdout and b'FAILURES!!!' not in r.stdout
            and PROBE.encode() in r.stdout and b'test=verify' in r.stdout)
        # verify asserts a different PID and exact durable hashes inside the reopened
        # process. The runner may exit it after completion; System.out is logcat-only.
        # Do not infer failure from pidof after a completed instrumentation run.
        archive = run(point + '-archive-own-fixture', adb + ['exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-',
            f'no_backup/qa-evaluation-process/{fixture}', f'no_backup/qa-evaluation-process-{fixture}.db', f'files/materials/{fixture}'])
        (output / (point + '-SYNTHETIC_STATE.tar')).write_bytes(archive.stdout)
        cases.append({'checkpoint': point, 'fixture': fixture, 'actualProcessExitObserved': True, 'reopenedVerification': 'PASS'})
    (output / 'RESULT.json').write_text(json.dumps({'status': 'PASS', 'scope': 'OWNED_NATIVEQA_EXPORT_PROCESS_RECOVERY',
        'nativeRun': str(native), 'ownership': ownership, 'cases': cases}, indent=2), encoding='utf-8')
    print(json.dumps({'status': 'PASS', 'checkpoints': len(cases)}))


if __name__ == '__main__':
    main()
