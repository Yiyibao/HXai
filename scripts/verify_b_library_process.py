"""Real nativeqa process death at unpublished write and committed restore/lost-ACK boundaries.
Requires the matching QA and test APK already installed on an exclusively owned emulator.
No installs, clears, production data, model requests or network calls.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

PACKAGE = 'org.hxsi.agent.nativeqa'
RUNNER = PACKAGE + '.test/me.rerere.rikkahub.feature.agent.AgentNativeTestRunner'
CLASS = 'me.rerere.rikkahub.feature.material.MaterialLibraryProcessProbe'
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=False)
    adb = [str(ROOT / '.local/android-sdk/platform-tools/adb.exe'), '-s', args.serial]
    records = []

    def run(name, command):
        started = time.time()
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=180)
        log = args.run_dir / (name + '.log')
        log.write_bytes(result.stdout + b'\n' + result.stderr)
        records.append(dict(name=name, command=command, cwd=str(ROOT), exit_code=result.returncode,
                            seconds=round(time.time()-started, 3), log=log.name,
                            sha256=hashlib.sha256(log.read_bytes()).hexdigest()))
        (args.run_dir/'TEST_RUNS.json').write_text(json.dumps(records, indent=2))
        return result, (result.stdout + result.stderr).decode(errors='replace')

    result, text = run('emulator-only', adb + ['shell', 'getprop', 'ro.kernel.qemu'])
    assert result.returncode == 0 and text.strip() == '1'
    for method, phase in [('writeThenDie', 'unpublished_write'), ('recoverThenRestoreAndDie', 'restore_saved_no_ack')]:
        result, text = run(method, adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'b04ProcessProbe', 'true', '-e', 'class', CLASS+'#'+method, RUNNER])
        assert 'Process crashed' in text or 'INSTRUMENTATION_FAILED' in text, text
        assert not re.search(r'OK \(\d+ tests?\)', text), text
        result, raw = run(method+'-durable-marker', adb + ['exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/b04-library-process.json'])
        assert result.returncode == 0
        marker = json.loads(raw)
        assert marker['phase'] == phase
        (args.run_dir/(method+'-MARKER.json')).write_text(json.dumps(marker, indent=2))
    result, text = run('verifyRecovery', adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'b04ProcessProbe', 'true', '-e', 'class', CLASS+'#verifyRecovery', RUNNER])
    assert result.returncode == 0 and re.search(r'OK \(1 tests?\)', text) and 'FAILURES!!!' not in text, text
    result, text = run('recovery-evidence', adb + ['exec-out', 'run-as', PACKAGE, 'cat', 'cache/b04-library-process-evidence.json'])
    assert result.returncode == 0 and json.loads(text)['status'] == 'PASS'
    evidence = json.loads(text)
    readback = {}
    for label in ['original', 'restored']:
        result, _ = run(label+'-independent-bytes', adb + ['exec-out', 'run-as', PACKAGE, 'cat',
            f"files/materials/{evidence['owner']}/{evidence[label]}.md"])
        assert result.returncode == 0
        actual = hashlib.sha256(result.stdout).hexdigest()
        assert actual == evidence['source_sha256']
        readback[label] = dict(bytes=len(result.stdout), sha256=actual)
    (args.run_dir/'RESULT.json').write_text(json.dumps(dict(status='PASS', scope='real process death, unpublished output not exposed, immutable original, durable tombstone and idempotent independently read restore after lost ACK', evidence=evidence, independent_readback=readback), indent=2))
    print((args.run_dir/'RESULT.json').read_text())


if __name__ == '__main__':
    main()
