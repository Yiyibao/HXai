"""Collect only explicitly named review fixtures; never export app settings or credentials."""
import json
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
QA = (ROOT / (sys.argv[1] if len(sys.argv) > 1 else 'qa/full-review-20261008')).resolve()
QA.relative_to(ROOT)  # Only collect into a directory inside this workspace.
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'
STARTED = datetime.fromisoformat(json.loads((QA / 'emulator-results.json').read_text(encoding='utf-8-sig'))[0]['started']).timestamp()

def adb(*args):
    return subprocess.run([str(ADB), '-s', 'emulator-5554', *args], capture_output=True)

def export(remote, local):
    stamp = adb('shell', 'run-as', 'org.mathstudy.agent.debug', 'stat', '-c', '%Y', remote)
    target = QA / local
    try:
        fresh = int(stamp.stdout.strip()) >= STARTED
    except ValueError:
        fresh = False
    if not fresh:
        if target.is_file():
            target.unlink()
        return False
    result = adb('exec-out', 'run-as', 'org.mathstudy.agent.debug', 'cat', remote)
    if result.returncode == 0 and result.stdout:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(result.stdout)
        return True
    return False

for name in ('filter-before', 'filter-after', 'invented-symbol', 'missing-artifact', 'docx-preview',
             'material-text-preview', 'teaching-mode', 'teaching-large', 'corrupt-open', 'memory-edited',
             'long-material-tail', 'artifact-owner-switch'):
    export(f'cache/review-{name}.png', f'screenshots/{name}.png')
    export(f'cache/review-{name}.txt', f'screenshots/{name}.txt')
for name in ('s1-real-teaching.md', 's3-real-teaching.md', 's3-real-records.json', 'material-real-model.md',
             's2-real-memory.json', 's2-real-followup.md', 'review-real-teaching.md', 'document-service-live-evidence.json'):
    export('cache/' + name, 'fixtures/' + name)
# Method/run-specific filenames are emitted by instrumentation; never enumerate app files.
for log in QA.rglob('*.log'):
    for name in set(re.findall(r'review_evidence=(s1-real-(?:teaching|approvals)-[a-f0-9-]{36}\.md)', log.read_text(encoding='utf-8-sig', errors='replace'))):
        export('cache/' + name, 'fixtures/' + name)
export('cache/review-delivery/delivery-receipts.txt', 'delivery-receipts.txt')
for ext in ('md', 'docx', 'xlsx', 'pptx', 'pdf'):
    for action in ('open', 'send'):
        export(f'cache/review-delivery/delivery-{action}-{ext}.png', f'screenshots/delivery-{action}-{ext}.png')

receipts = QA / 'delivery-receipts.txt'
if receipts.exists():
    expected = {}
    for line in receipts.read_text(encoding='utf-8').splitlines():
        if ' VIEW ' in line:
            extension, value = line.split(' VIEW ', 1)
            expected[extension] = json.loads(value)['sha256']
    saved = []
    files = adb('shell', 'find', '/sdcard/Download', '-maxdepth', '1', '-name', 'QA交付-*', '-type', 'f')
    for path in files.stdout.decode('utf-8', errors='replace').splitlines():
        match = re.fullmatch(r'/sdcard/Download/QA交付-[0-9a-f-]+\.(md|docx|xlsx|pptx|pdf)', path)
        if not match:
            continue
        stamp = adb('shell', 'stat', '-c', '%Y', path)
        if stamp.returncode or int(stamp.stdout.strip()) < STARTED:
            continue
        digest = adb('shell', 'sha256sum', path).stdout.decode().split()[0]
        saved.append({'file': path, 'extension': match[1], 'modified': int(stamp.stdout.strip()), 'sha256': digest})
    # Repeated tests use different fixtures (and ZIP timestamps), so an earlier
    # file must not be compared with the latest receiver's source digest.
    (QA / 'all-saved-file-hashes.json').write_text(json.dumps(saved, ensure_ascii=False, indent=2), encoding='utf-8')
    newest = {}
    for item in sorted(saved, key=lambda item: (item['modified'], item['file'])):
        newest[item['extension']] = item
    comparisons = [{**item, 'matches_original': item['sha256'] == expected.get(ext)} for ext, item in newest.items()]
    (QA / 'saved-file-hashes.json').write_text(json.dumps(comparisons, ensure_ascii=False, indent=2), encoding='utf-8')

instrumentation = []
for log in sorted(QA.glob('*.log')):
    text = log.read_text(encoding='utf-8-sig', errors='replace')
    if 'INSTRUMENTATION_STATUS_CODE:' not in text:
        continue
    current = {}
    cases = []
    for line in text.splitlines():
        match = re.match(r'INSTRUMENTATION_STATUS: (class|test|stack)=(.*)', line)
        if match:
            current[match[1]] = match[2]
        match = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if match:
            code = int(match[1])
            if code in (0, -2, -3, -4):
                cases.append({**current, 'status': {0:'passed', -2:'failed', -3:'failed', -4:'skipped'}[code]})
                current = {}
    instrumentation.append({'log': log.name, 'modified': log.stat().st_mtime, 'cases': cases,
                            'passed': sum(x['status']=='passed' for x in cases),
                            'failed': sum(x['status']=='failed' for x in cases),
                            'skipped': sum(x['status']=='skipped' for x in cases)})

jvm = []
for module in ('app', 'ai', 'search', 'speech', 'common', 'document'):
    suites = []
    for source in (ROOT / 'client' / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite = ET.parse(source).getroot()
        suites.append({key: suite.get(key) for key in ('name','tests','failures','errors','skipped','timestamp')})
        target = QA / 'jvm' / module / source.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    jvm.append({'module': module, 'tests': sum(int(x['tests']) for x in suites),
                'failures': sum(int(x['failures']) for x in suites),
                'errors': sum(int(x['errors']) for x in suites), 'suites': suites})
latest = {}
for run in sorted(instrumentation, key=lambda run: run['modified']):
    for case in run['cases']:
        # Seed/verify are intentionally separate processes and count as separate cases.
        phase = run['log'] if any(part in run['log'] for part in ('seed','verify')) else ''
        latest[(case.get('class'), case.get('test'), phase)] = {**case, 'log': run['log']}
summary = {'instrumentation': instrumentation, 'latest_cases': list(latest.values()),
           'latest_counts': {status:sum(case['status']==status for case in latest.values()) for status in ('passed','failed','skipped')}, 'jvm': jvm}
(QA / 'test-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'instrumentation': [{k:v for k,v in x.items() if k!='cases'} for x in instrumentation],
                  'jvm': [{k:v for k,v in x.items() if k!='suites'} for x in jvm]}, ensure_ascii=False, indent=2))
