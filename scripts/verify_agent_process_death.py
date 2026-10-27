"""Five actual process-stop checkpoints for the dedicated synthetic QA package."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'org.hxsi.agent.nativeqa'
RUNNER = PACKAGE + '.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
parser = argparse.ArgumentParser()
parser.add_argument('--run-id', required=True)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--native-run', default='native-final-03', help='Native verification run that installed these QA APKs')
args = parser.parse_args()
assert re.fullmatch(r'[a-zA-Z0-9_-]+', args.run_id)
assert re.fullmatch(r'[a-zA-Z0-9_-]+', args.native_run)
out = ROOT / 'HXai_CHANGE_REVIEW/AGENT-MODEL-NATIVE-RESUME-20261012/runs' / args.run_id
out.mkdir(parents=True, exist_ok=False)
adb = [str(ROOT / '.local/android-sdk/platform-tools/adb.exe'), '-s', args.serial]
records = []
inputs = {
    'sources': {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in [
        'client/app/src/main/java/me/rerere/rikkahub/feature/agent/AgentTaskStore.kt',
        'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/AgentProcessDeathProbe.kt',
        'client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/AgentNativeTestRunner.kt',
        'scripts/verify_agent_process_death.py',
    ]},
    'installed_by_previous_native_verification': args.native_run + '/APK_INPUTS.json',
    'apk_hashes': json.loads((out.parent / args.native_run / 'APK_INPUTS.json').read_text()),
}
(out / 'SOURCE_INPUTS.json').write_text(json.dumps(inputs, indent=2))


def run(name, command, accept=lambda p: p.returncode == 0):
    started = time.time()
    result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=60)
    log = out / (name + '.log')
    log.write_bytes(('$ ' + subprocess.list2cmdline(command) + '\nCWD: ' + str(ROOT) + '\n').encode() + result.stdout + result.stderr)
    passed = accept(result)
    records.append(dict(name=name, command=command, cwd=str(ROOT), seconds=round(time.time()-started, 3),
        exit_code=result.returncode, status='PASS' if passed else 'FAIL', log=log.name,
        sha256=hashlib.sha256(log.read_bytes()).hexdigest()))
    (out / 'TEST_RUNS.json').write_text(json.dumps(records, indent=2))
    if not passed:
        raise SystemExit('FAILED: ' + name)
    return result


run('emulator-only', adb + ['shell', 'getprop', 'ro.kernel.qemu'], lambda p: p.returncode == 0 and p.stdout.strip() == b'1')
for package, artifact, suffix in [(PACKAGE, 'qa-app.apk', 'app-x86_64-debug.apk'),
        (PACKAGE + '.test', 'qa-test.apk', 'app-debug-androidTest.apk')]:
    remote = run(artifact + '-path', adb + ['shell', 'pm', 'path', package]).stdout.decode().strip().splitlines()[0]
    assert remote.startswith('package:')
    run(artifact + '-readback', adb + ['pull', remote.removeprefix('package:'), str(out / artifact)])
    expected = next(h for p, h in inputs['apk_hashes'].items() if p.endswith(suffix))
    actual = hashlib.sha256((out / artifact).read_bytes()).hexdigest()
    assert actual == expected, 'Installed QA APK differs from the selected native run'
for checkpoint in ['before_claim', 'after_claim', 'after_journal', 'after_effect', 'after_receipt']:
    fixture = str(uuid.uuid4())
    command = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'me.rerere.rikkahub.feature.agent.AgentProcessDeathProbe#prepare',
        '-e', 'fixture', fixture, '-e', 'checkpoint', checkpoint, RUNNER]
    prepared_log = out / (checkpoint + '-prepare.log')
    with prepared_log.open('wb') as stream:
        process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        ready = False
        try:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                result = subprocess.run(adb + ['exec-out', 'run-as', PACKAGE, 'cat',
                    'cache/agent-process-' + fixture + '/ready'], capture_output=True, timeout=5)
                if result.returncode == 0 and result.stdout.decode().strip() == checkpoint:
                    ready = True
                    break
                if process.poll() is not None:
                    break
                time.sleep(0.25)
            if not ready:
                raise RuntimeError('No durable ready checkpoint: ' + checkpoint)
            run(checkpoint + '-force-stop', adb + ['shell', 'am', 'force-stop', PACKAGE])
            process.wait(timeout=15)
            run(checkpoint + '-old-process-stopped', adb + ['shell', 'pidof', PACKAGE], lambda p: p.returncode in (0,1) and not p.stdout.strip())
        finally:
            if process.poll() is None:
                subprocess.run(adb + ['shell', 'am', 'force-stop', PACKAGE], capture_output=True, timeout=15)
                process.wait(timeout=15)
    records.append(dict(name=checkpoint+'-actual-process-exit', command=command, cwd=str(ROOT),
        exit_code=process.returncode, status='PASS' if ready else 'FAIL', fixture=fixture,
        log=prepared_log.name, sha256=hashlib.sha256(prepared_log.read_bytes()).hexdigest(),
        note='adb/instrument exit is not the verdict; durable readiness plus host stop and independent reopened verification are required'))
    verify = adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'me.rerere.rikkahub.feature.agent.AgentProcessDeathProbe#verify',
        '-e', 'fixture', fixture, '-e', 'checkpoint', checkpoint, RUNNER]
    run(checkpoint + '-reopen-verify', verify, lambda p: p.returncode == 0 and b'OK (1 test)' in p.stdout and b'FAILURES!!!' not in p.stdout)
    print(checkpoint, 'PASS', flush=True)
print('Five actual process-death checkpoints PASS', flush=True)
