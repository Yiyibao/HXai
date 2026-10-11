import hashlib, json, re, subprocess, xml.etree.ElementTree as ET
from pathlib import Path
QA=Path(__file__).resolve().parent
ROOT=QA.parents[1]
def read(name): return json.loads((QA/name).read_text(encoding='utf-8-sig'))
hashes=read('source-hashes.json')
delta=[]
for record in hashes:
    file=Path(record['Path'])
    current=hashlib.sha256(file.read_bytes()).hexdigest().upper() if file.exists() else None
    if current!=record['Hash']: delta.append(dict(path=str(file),before=record['Hash'],after=current))
(QA/'source-delta.json').write_text(json.dumps(delta,indent=2),encoding='utf-8')
historical=json.loads((ROOT/'qa/review-fixes-20261008/source-delta.json').read_text(encoding='utf-8-sig'))
source_compare=[dict(path=r['Path'],matchesLastRecordedAfter=hashlib.sha256(Path(r['Path']).read_bytes()).hexdigest().upper()==r['After']) for r in historical]
(QA/'fix-source-hash-check.json').write_text(json.dumps(source_compare,indent=2),encoding='utf-8')
totals={key:0 for key in ['tests','failures','errors','skipped']}
for file in (QA/'jvm').glob('TEST-*.xml'):
    root=ET.parse(file).getroot()
    for key in totals: totals[key]+=int(root.attrib.get(key,0))
lint=ET.parse(QA/'lint.xml').getroot()
lint_totals={}
for issue in lint.findall('issue'): lint_totals[issue.get('severity')]=lint_totals.get(issue.get('severity'),0)+1
android=read('android-batch.json')+read('delivery-batch.json')
for item in android:
    raw=(QA/(item['name']+'.log')).read_text(encoding='utf-8-sig')
    match=re.search(r'OK \((\d+) tests?\)',raw)
    item['tests']=int(match[1]) if match else None
    item['status']='PASS' if item['ok'] else 'FAIL'
    item['pids']=sorted(set(re.findall(r'qa_pid=(\d+)',raw)))
fixture=(QA/'fixture-id.txt').read_text(encoding='utf-8-sig').strip()
assert re.fullmatch(r'[a-f0-9-]{36}',fixture)
adb=ROOT/'.local/android-sdk/platform-tools/adb.exe'
manifest=subprocess.run([str(adb),'-s','emulator-5554','exec-out','run-as','org.mathstudy.agent.debug','cat','cache/checklist-'+fixture+'.json'],check=True,capture_output=True).stdout
(QA/'controlled-manifest.json').write_bytes(manifest)
controlled=json.loads(manifest)
assert all(json.loads(x['conversation'])['profileId']==controlled['owner'] and json.loads(x['conversation'])['title'].startswith('QA_'+fixture+'_') for x in controlled['entries'])
summary=dict(version='Unchanged final candidate APK; new independent runs only', sourceDelta=delta,
 androidLocal=dict(records=android,tests=sum(x['tests'] or 0 for x in android),skipped=0),
 jvmApp=totals,server=dict(tests=27,passed=27,skipped=0),lint=lint_totals,
 controlled=dict(fixture=fixture,owner=controlled['owner'],entries=len(controlled['entries']),deleted=sum(x['deleted'] for x in controlled['entries'])),
 delivery=dict(checked=10,passed=sum(x['status']=='PASS' for x in read('exact-delivery-hashes.json'))),
 collectorProbe=read('collector-probe.json'),skillResourceProbe=dict(tests=1,failures=1,actual='OutOfMemoryError',heapMb=64,compressedBytes=130787,expandedBytes=134217728),
 realModelCurrent='NOT_RUN',imageGeneration='MISSING_CONFIGURATION; no new probe in this independent run',
 diagnosticsRuntime='NOT_RUN: existing isolation clears an entire diagnostics directory',
 web=dict(typecheck=int((QA/'web-typecheck-exit.txt').read_text()),build=int((QA/'web-build-exit.txt').read_text())))
(QA/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:summary[k] for k in ['sourceDelta','jvmApp','lint','controlled','delivery','web']},ensure_ascii=False,indent=2))
