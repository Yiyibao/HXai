"""Install debug test artifacts and run only isolated local FILE-05 instrumentation.

Preserves emulator app data. The custom runner uses a plain Application so that
real background memory/document jobs and pending restores never start.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'HXai_CHANGE_REVIEW/FILE-05-OOXML-PPT-20261012'
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--run-id', default='android-final')
args = parser.parse_args()
adb = ROOT / '.local/android-sdk/platform-tools/adb.exe'
base = [str(adb), '-s', args.serial]
records = []

def run(name, command, required=None):
    name = args.run_id + '-' + name
    started = time.time()
    result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True)
    text = (result.stdout + result.stderr).decode('utf-8', errors='replace')
    (OUT / 'logs' / (name + '.log')).write_text('$ ' + subprocess.list2cmdline(list(map(str, command))) + '\nCWD: ' + str(ROOT) + '\n' + text + '\nexit_code=' + str(result.returncode), encoding='utf-8')
    passed = result.returncode == 0 and (required is None or required in text)
    records.append(dict(name=name, command=list(map(str, command)), cwd=str(ROOT), seconds=round(time.time()-started, 3), exit_code=result.returncode, status='PASS' if passed else 'FAIL'))
    (OUT / 'ANDROID_RUNS.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
    print(name, text)
    if not passed:
        raise SystemExit(1)
    return result.stdout

apks = [ROOT / 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk',
        ROOT / 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
(OUT / 'ANDROID_INPUTS.json').write_text(json.dumps({str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in apks}, indent=2))
run('android-device', base + ['shell', 'getprop', 'ro.build.version.sdk'])
for i, apk in enumerate(apks):
    run('android-install-' + str(i), base + ['install', '-r', apk], 'Success')
run('android-instrumentation-registry', base + ['shell', 'pm', 'list', 'instrumentation'], 'OfficeSafetyTestRunner')
run('android-office-safety', base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
    'me.rerere.rikkahub.feature.material.OfficeSafetyRuntimeTest',
    'org.hxsi.agent.debug.test/me.rerere.rikkahub.feature.material.OfficeSafetyTestRunner'], 'OK (3 tests)')
artifacts = OUT / 'artifacts'
artifacts.mkdir(exist_ok=True)
for name in ['source.pptx', 'output.pptx', 'hashes.json']:
    result = subprocess.run(base + ['exec-out', 'run-as', 'org.hxsi.agent.debug', 'cat', 'cache/file-05-evidence/' + name], capture_output=True)
    assert result.returncode == 0, result.stderr.decode(errors='replace')
    (artifacts / name).write_bytes(result.stdout)
run('artifact-independent-readback', ['python', ROOT / 'scripts/verify_office_artifact.py'], '"status": "PASS"')
