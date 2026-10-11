import hashlib
import json
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta
from pathlib import Path

QA = Path(__file__).resolve().parent
def read(name):
    return json.loads((QA / name).read_text(encoding='utf-8-sig'))
def totals(folder):
    result = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
    for file in (QA / folder).glob('TEST-*.xml'):
        root = ET.parse(file).getroot(); result['suites'] += 1
        for key in ('tests', 'failures', 'errors', 'skipped'):
            result[key] += int(root.get(key, 0))
    return result
core = read('acceptance-android/android-results.json')
for run in core:
    log = (QA / 'acceptance-android' / (run['name'] + '.log')).read_text(encoding='utf-8-sig')
    run['tests'] = int(re.search(r'OK \((\d+) tests?\)', log).group(1))
    run['logSha256'] = hashlib.sha256((QA / 'acceptance-android' / (run['name'] + '.log')).read_bytes()).hexdigest()
issues = ET.parse(QA / 'lint-current.xml').getroot().findall('issue')
server = ET.parse(QA / 'server-host.xml').getroot()
server_cases = server.findall('.//testcase')
inputs = read('input-hashes-after.json')
summary = dict(
    generatedAt=datetime.now(timezone(timedelta(hours=8))).isoformat(),
    classification={'production':[{'id':'PR01','priority':'P1','status':'FAIL'}],
                    'test_or_evidence':[{'id':'PR02','priority':'P2','status':'FAIL'}, {'id':'PR03','priority':'P2','status':'FAIL'}]},
    productionChanged=False, originalInputFilesChecked=len(inputs), originalInputHashChanges=[v for v in inputs if not v['Matches']],
    jvmCore=totals('jvm-core'), androidCore={'invocations':len(core),'pass':sum(v['tests'] for v in core if v['ok']), 'runs':core},
    delivery={'methods':1, 'runs':read('delivery-runs/results.json'), 'exactGroups':read('exact-delivery-hashes.json')},
    server={'tests':len(server_cases),'fail':len(server.findall('.//failure')),'error':len(server.findall('.//error')),'skip':len(server.findall('.//skipped')),'platformLockDockerWorkerSubstituted':True},
    collectorBaseline={'pass':6,'firstAttemptEnvironmentErrors':12},
    collectorDisagreementProbe={'tests':1,'fail':1,'mockDevice':True,'result':'PASS manifest plus JUnit FAILED was accepted as PASS'},
    jvmProbesFirst=totals('probe-xml-first'), jvmQueueProbeFixtureCorrected=totals('probe-xml'),
    androidProbesFirst=read('probe-android-results-first.json'), androidProbesFixtureCorrected=read('probe-android-results-fixture-fixed.json'),
    lint={'errors':sum(v.get('severity') in ('Error','Fatal') for v in issues),'warnings':sum(v.get('severity')=='Warning' for v in issues),'cachedAnalysis':True},
    installedFinal=read('installed-apk-final.json'), apks=read('apk-final.json'),
    remoteRequests=False, realImagePositive='MISSING_CONFIGURATION / NOT_RUN (not inspected or configured credentials)',
    notRun=['all transaction fault positions', 'actual factory execute function invocation counter', 'all editing UI and real title/suggestion model callbacks',
            'deleted stale save after independent process restart', 'nested/broken skill symlink and cleanup/replace I/O failures',
            'cross-session attachment device negative and I/O deletion retry', 'delivery cancel/corrupt/profile-switch/target-read failure',
            'real Docker/mount/worker and remote teaching/material/image/GitHub', 'all non-app JVM modules/Web/full migration/backup/other platforms'],
    historicalBatchesNotCombined=['44 Android','18 Android','first real 2','independent re-review 180657','independent fixes 182645','coverage completion 192645 JVM328/Android21/delivery2/server28/collector6/lint0+42'],
    committed=False,pushed=False,deployed=False)
(QA/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
files = [p for p in QA.rglob('*') if p.is_file() and 'temp' not in p.parts and not any(s.startswith('server-temp') for s in p.parts) and p.name != 'evidence-hashes.json' and p.suffix != '.pyc']
(QA/'evidence-hashes.json').write_text(json.dumps([dict(path=str(p.relative_to(QA)),sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size) for p in files], indent=2), encoding='utf-8')
print(json.dumps(dict(jvm=summary['jvmCore'],android=summary['androidCore']['pass'],server=summary['server'],lint=summary['lint'],inputChanges=len(summary['originalInputHashChanges']))))
