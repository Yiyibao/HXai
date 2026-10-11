import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
QA = Path(__file__).parent
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'
START = (QA / 'material-diagnostic.log').stat().st_ctime

def adb(*args):
    return subprocess.run([str(ADB), '-s', 'emulator-5554', *args], capture_output=True, check=True).stdout

names = ['review-real-teaching.md', 'material-real-model.md',
         'material-evidence/fidelity.docx', 'material-evidence/word-vision-output.docx',
         'material-evidence/scan-input.pdf', 'material-evidence/scan-output.md']
for log in QA.glob('*.log'):
    names += re.findall(r'review_evidence=(s1-real-(?:teaching|approvals)-[a-f0-9-]{36}\.md)', log.read_text(encoding='utf-8-sig', errors='replace'))
records = json.loads((QA / 'fixture-manifest.json').read_text(encoding='utf-8')) if (QA / 'fixture-manifest.json').exists() else []
for name in sorted(set(names)):
    existing = next((r for r in records if r['name'] == name), None)
    if existing is not None:
        assert hashlib.sha256((QA / 'fixtures' / name).read_bytes()).hexdigest() == existing['sha256']
        continue
    stamp = int(adb('shell', 'run-as', 'org.mathstudy.agent.debug', 'stat', '-c', '%Y', 'cache/' + name).strip())
    # Teaching finished immediately before the material test.
    reused_input = name == 'material-evidence/fidelity.docx'
    assert reused_input or stamp >= START - 90, f'Stale fixture: {name}'
    data = adb('exec-out', 'run-as', 'org.mathstudy.agent.debug', 'cat', 'cache/' + name)
    target = QA / 'fixtures' / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    records.append(dict(name=name, deviceMtime=stamp, reusedSyntheticInput=reused_input, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
(QA / 'fixture-manifest.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
