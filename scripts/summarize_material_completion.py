"""Aggregate final logs and actual cross-UID receipts; missing evidence fails the summary."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
qa = root / 'qa/material'
tests = errors = failures = 0
for path in (root / 'client/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    tests += int(suite.get('tests', 0)); errors += int(suite.get('errors', 0)); failures += int(suite.get('failures', 0))
assert tests > 0 and errors == failures == 0
android = {}
for name in ['android-final.log', 'fidelity-android.log', 'delivery-android.log', 'real-model.log']:
    log = (qa / name).read_text(encoding='utf-8-sig')
    match = re.search(r'OK \((\d+) tests?\)', log)
    assert match and 'FAILURES!!!' not in log, name
    android[name] = int(match[1])
receipts = {}
for line in (qa / 'delivery-receipts.txt').read_text(encoding='utf-8').splitlines():
    ext, action, rest = line.split(' ', 2)
    receipts.setdefault(ext, {})[action] = rest if action == 'SAVE' else json.loads(rest)
assert set(receipts) == {'md', 'docx', 'xlsx', 'pptx', 'pdf'}
for ext, actions in receipts.items():
    assert set(actions) == {'VIEW', 'SEND', 'SAVE'}
    assert actions['VIEW']['sha256'] == actions['SEND']['sha256']
    assert actions['VIEW']['size'] == actions['SEND']['size'] > 0
    assert actions['VIEW']['action'] == 'android.intent.action.VIEW'
    assert actions['SEND']['action'] == 'android.intent.action.SEND'
    if ext in {'docx', 'xlsx'}:
        expected = hashlib.sha256((qa / f'fidelity.{ext}').read_bytes()).hexdigest()
        assert actions['SEND']['sha256'] == expected, 'Rich fixture was not delivered'
artifacts = ['client/app/build/outputs/apk/debug/app-x86_64-debug.apk', 'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', '.local/material-receiver/receiver.apk']
result = {
    'date': '2026-10-07', 'emulator': 'emulator-5554 / API34 x86_64',
    'jvm': {'tests': tests, 'failures': failures, 'errors': errors},
    'android': android,
    'delivery': {ext: {'VIEW': 'PASS', 'SAVE': 'PASS', 'SEND': 'PASS', 'sha256': actions['SEND']['sha256'], 'bytes': actions['SEND']['size']} for ext, actions in receipts.items()},
    'apk_sha256': {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in artifacts},
}
(qa / 'completion-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
