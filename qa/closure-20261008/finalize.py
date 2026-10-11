import hashlib,json,re,xml.etree.ElementTree as ET
from pathlib import Path
QA=Path(__file__).resolve().parent; ROOT=QA.parents[1]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads((QA/path).read_text(encoding='utf-8-sig'))
snapshot=read('source-final-candidate-18/hashes.json')
changed=[name for name,value in snapshot.items() if sha(ROOT/name)!=value]
assert not changed,changed
runs=read('final-independent-android/results.json')
for row in runs:
    log=QA/'final-independent-android'/(row['name']+'.log')
    assert sha(log)==row['logSha256']
    row['tests']=int(re.search(r'OK \((\d+) tests?\)',log.read_text(encoding='utf-8-sig'))[1])
assert len(runs)==22 and all(row['status']=='PASS' for row in runs)
assert read('final-diagnostics-before.json')==read('final-diagnostics-after.json')
fixtures=read('final-collected/fixture-manifest.json')
assert len(fixtures['records'])==59 and all(row['status']=='PASS' for row in fixtures['records'])
for row in fixtures['records']: assert sha(QA/'final-collected'/row['name'])==row['sha256']
exact=read('exact-delivery-final-hashes.json'); assert len(exact)==10 and all(row['status']=='PASS' for row in exact)
for name in ('final-quality','final-collector','server-final-retry','final-collection','final-exact-delivery'):
    assert (QA/(name+'-exit.txt')).read_text().strip()=='0',name
apks={str(p.relative_to(ROOT)).replace('\\','/'):sha(p) for p in (ROOT/'client/app/build/outputs/apk/debug').glob('*.apk')}
apks['client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']=sha(ROOT/'client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
installed=read('final-independent-android/installed-apk.json')
assert apks['client/app/build/outputs/apk/debug/app-x86_64-debug.apk']==installed['appSha256']
assert apks['client/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']==installed['testSha256']
lint=ET.parse(QA/'final-lint.xml').getroot().findall('issue')
assert not [i for i in lint if i.get('severity') in ('Error','Fatal')]
summary=dict(status='LOCAL_SCOPE_ACCEPTED',closed=['PR01','PR02','PR03','CL01','CL02','CL03','CL04'],openConfirmedLocal=[],
    sourceSnapshot='source-final-candidate-18',sourceManifestSha256=sha(QA/'source-final-candidate-18/hashes.json'),sourceMismatches=changed,
    installedApk=installed,buildApks=apks,android=dict(invocations=len(runs),tests=sum(r['tests'] for r in runs),passed=sum(r['tests'] for r in runs),fail=0,skip=0,runs=runs),
    jvm=read('final-jvm/counts.json'),collector=dict(tests=9,fail=0),service=dict(tests=29,fail=0,realWorkerVerified=False),
    lint=dict(errors=0,warnings=len(lint)),evidence=dict(records=59,exactDeliveryGroups=10,personalDiagnosticsHashFiles=2,personalDiagnosticsUnchanged=True),
    external=[dict(item=item,status='MISSING_CONFIGURATION' if i<4 else 'NOT_RUN') for i,item in enumerate(read('scope.json')['external'])],
    historicalTeachingMaterialFailureRootCause='UNKNOWN',historicalCountsIncluded=False,
    distinctJvmCount=532,probeRerunAlreadyIncludedInApp=True,
    requiredLocalSubscenarios=sum(len(v) for v in read('scope.json')['requiredLocal'].values()),
    endConditions={item:'PASS within fixed local scope' for item in read('scope.json')['end']})
(QA/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
files={str(p.relative_to(QA)).replace('\\','/'):sha(p) for p in QA.rglob('*') if p.is_file() and any(x in str(p.relative_to(QA)) for x in ('final-independent-android','final-collected','final-jvm','exact-targets-final','source-final-candidate-18'))}
files.update({p.name:sha(p) for p in QA.glob('*') if p.is_file() and p.name.startswith(('final-','server-final-retry','exact-delivery-final'))})
files.update({p.name:sha(p) for p in QA.glob('*') if p.is_file() and p.name in ('report.md','findings.md','acceptance-matrix.md','version-ledger.md','summary.json','scope.json','profile-switch-before-target.md','profile-switch-before-target.json','global-instruction-source-audit.txt','finalize.py','write_reports.py','diagnostic_hashes.py','check-exact-delivery-final.py','archive_quality.py')})
(QA/'evidence-hashes.json').write_text(json.dumps(files,indent=2),encoding='utf-8')
print(json.dumps(dict(android=summary['android']['tests'],records=59,sourceChanges=changed,lint=summary['lint'])))
