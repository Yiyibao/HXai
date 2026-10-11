"""Collect real rendering evidence using the existing strict method/run verifier."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from collect_run_evidence import collect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5554')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    log_path = args.log.resolve()
    raw = log_path.read_bytes()
    manifests = [json.loads(value) for value in re.findall(
        r'^INSTRUMENTATION_STATUS: qa_evidence_manifest=(.*)$',
        raw.decode('utf-8-sig'), re.MULTILINE)]
    if len(manifests) != 1:
        raise ValueError('Exactly one finalized method/run is required')
    manifest = manifests[0]
    apk = root / 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk'
    test = root / 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    sha = lambda data: hashlib.sha256(data).hexdigest()
    stat = log_path.stat()
    context = {
        'appSha256': sha(apk.read_bytes()), 'testSha256': sha(test.read_bytes()),
        'runs': [{'method': manifest['method'], 'runId': manifest['runId'],
                  'log': log_path.name, 'logSha256': sha(raw),
                  'notBefore': int(getattr(stat, 'st_birthtime', stat.st_ctime) * 1000) - 5000,
                  'notAfter': int(stat.st_mtime * 1000) + 5000}],
    }
    context_path = log_path.with_suffix('.context.json')
    context_path.write_text(json.dumps(context, indent=2), encoding='utf-8')

    def device(*command):
        # Adapt only exact package arguments; the shared verifier keeps all hash/terminal checks.
        packages = {'org.mathstudy.agent.debug': 'org.hxsi.agent.debug',
                    'org.mathstudy.agent.debug.test': 'org.hxsi.agent.debug.test'}
        return subprocess.run(
            [str(root / '.local/android-sdk/platform-tools/adb.exe'), '-s', args.serial,
             *(packages.get(value, value) for value in command)],
            check=True, capture_output=True).stdout

    result = collect(context_path, args.output.resolve(), device=device)
    print(json.dumps({'files': len(result['records']),
                      'statuses': sorted({entry['status'] for entry in result['records']})}))


if __name__ == '__main__':
    main()
