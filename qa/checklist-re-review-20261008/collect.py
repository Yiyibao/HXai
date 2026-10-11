"""Collect current-run results without counting cached or historical executions."""
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from datetime import datetime

QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'

def adb(*args):
    result = subprocess.run([str(ADB), '-s', 'emulator-5554', *args], capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout

def export(remote, target, started):
    stamp = int(adb('shell', 'run-as', 'org.mathstudy.agent.debug', 'stat', '-c', '%Y', remote).strip())
    if stamp < int(started):
        raise RuntimeError(f'Historical fixture rejected: {remote}')
    raw = adb('exec-out', 'run-as', 'org.mathstudy.agent.debug', 'cat', remote)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    return raw

runs = []
for file in (QA / 'boundary/runtime-results.json', QA / 'additional-results.json', QA / 'large-font-result.json'):
    if file.exists():
        records = json.loads(file.read_text(encoding='utf-8-sig'))
        for record in records if isinstance(records, list) else [records]:
            record['log'] = (file.parent / record['log']).relative_to(QA).as_posix()
            runs.append(record)

cases = []
for run in runs:
    fields = {}
    for line in (QA / run['log']).read_text(encoding='utf-8-sig').splitlines():
        match = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if match:
            fields[match[1]] = match[2]
        match = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if match:
            code = int(match[1])
            if code in (0, -1, -2, -3, -4) and 'test' in fields:
                cases.append({**fields, 'status': {0:'PASS', -1:'ERROR', -2:'FAIL', -3:'SKIP', -4:'SKIP'}[code], 'execution':run['name'], 'log':run['log']})
            fields = {}

jvm = {}
for module in ('app','ai','document','common','oauth','search','speech','highlight','web','workspace','material3','videogen'):
    values = {k:0 for k in ('tests','failures','errors','skipped')}
    for file in (ROOT / 'client' / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        attrs = ET.parse(file).getroot().attrib
        for key in values:
            values[key] += int(attrs.get(key, 0))
        dest = QA / 'jvm' / module / file.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, dest)
    jvm[module] = values

lint_source = ROOT / 'client/app/build/reports/lint-results-debug.xml'
lint = {}
if lint_source.exists():
    shutil.copyfile(lint_source, QA / 'lint-results.xml')
    issues = ET.parse(lint_source).getroot().findall('issue')
    lint = {severity:sum(issue.get('severity') == severity for issue in issues) for severity in ('Error','Fatal','Warning','Information')}

start = min(datetime.fromisoformat(run['started']).timestamp() for run in runs)
deliveries = []
for group, remote in [('artifact','review-delivery'),('material','material-evidence')]:
    try:
        receipt = export(f'cache/{remote}/delivery-receipts.txt', QA / 'delivery' / group / 'delivery-receipts.txt', start).decode('utf-8')
        sources = {line.split(' SOURCE ',1)[0]:json.loads(line.split(' SOURCE ',1)[1]) for line in receipt.splitlines() if ' SOURCE ' in line}
        if set(sources) != {'md','docx','xlsx','pptx','pdf'}:
            raise RuntimeError('Missing exact source receipt')
        for ext, source in sources.items():
            name = source['name']
            if not re.fullmatch(r'QA交付-[a-f0-9-]{36}\.'+ext, name):
                raise RuntimeError('Unexpected destination')
            raw = export(f'cache/{remote}/source-{ext}.{ext}', QA / 'delivery' / group / f'source.{ext}', start)
            received = adb('exec-out','cat','/sdcard/Download/'+name)
            (QA / 'delivery' / group / f'delivered.{ext}').write_bytes(received)
            view = next(json.loads(line.split(' VIEW ',1)[1]) for line in receipt.splitlines() if line.startswith(ext+' VIEW '))
            send = next(json.loads(line.split(' SEND ',1)[1]) for line in receipt.splitlines() if line.startswith(ext+' SEND '))
            hashes = {'source':hashlib.sha256(raw).hexdigest(), 'destination':hashlib.sha256(received).hexdigest(), 'VIEW':view['sha256'], 'SEND':send['sha256'], 'recorded':source['sha256']}
            deliveries.append({'group':group,'extension':ext,'exact_name':name,'hashes':hashes,'passed':len(set(hashes.values())) == 1})
    except Exception as error:
        deliveries.append({'group':group,'error':str(error),'passed':False})
(QA / 'exact-delivery-hashes.json').write_text(json.dumps(deliveries, ensure_ascii=False, indent=2), encoding='utf-8')

exports = []
for name in ('s1-real-teaching.md','review-real-teaching.md','s3-real-teaching.md','s3-real-records.json','s2-real-memory.json','s2-real-followup.md','material-real-model.md'):
    try:
        export('cache/'+name, QA / 'fixtures' / name, start)
        exports.append({'name':name,'status':'collected'})
    except Exception as error:
        exports.append({'name':name,'status':'unavailable','reason':str(error)})
for remote, name in [('cache/material-evidence/fidelity.docx','word-input.docx'),('cache/material-evidence/word-vision-output.docx','word-output.docx')]:
    try:
        export(remote, QA / 'fixtures' / name, start)
        exports.append({'name':name,'status':'collected'})
    except Exception as error:
        exports.append({'name':name,'status':'unavailable','reason':str(error)})
for name in ('filter-before','filter-after','invented-symbol','missing-artifact','docx-preview','material-text-preview','teaching-mode','teaching-large','corrupt-open','memory-edited','long-material-tail','artifact-owner-switch'):
    try:
        export(f'cache/review-{name}.png', QA / 'screenshots' / (name+'.png'), start)
        export(f'cache/review-{name}.txt', QA / 'screenshots' / (name+'.txt'), start)
    except Exception as error:
        exports.append({'name':name,'status':'unavailable','reason':str(error)})

summary = {'executions':runs,'cases':cases,'android':{state:sum(case['status']==state for case in cases) for state in ('PASS','FAIL','ERROR','SKIP')},'jvm':jvm,'lint':lint,'delivery_passed':sum(item['passed'] for item in deliveries),'exports':exports}
(QA / 'test-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2),encoding='utf-8')
print(json.dumps({key:summary[key] for key in ('android','jvm','lint','delivery_passed')},indent=2))
