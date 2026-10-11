from pathlib import Path
import json,hashlib,xml.etree.ElementTree as ET
from pypdf import PdfReader
root=Path(__file__).resolve().parents[2]; evidence=Path(__file__).parent
def read(path): return path.read_text(encoding='utf-8-sig')
for name,expected in [('full-client-build-final.log','BUILD SUCCESSFUL'),('device-office-repository.log','OK (3 tests)'),('device-office-live.log','OK (1 test)'),('device-regression.log','OK (30 tests)'),('server-office-tests.log','46 passed'),('flutter-analyze.log','No issues found'),('flutter-tests.log','All tests passed!')]:
    assert expected in read(evidence/name),(name,expected)
deployment=json.loads(read(evidence/'deployment-final.json'))
for name,digest in deployment['deployedSourceHashes'].items(): assert hashlib.sha256((root/'server/document-service'/name).read_bytes()).hexdigest()==digest
assert json.loads(read(evidence/'credential-cleanup.json'))['revokedTokenRejected401']
tests=[ET.parse(file).getroot() for file in (root/'client/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
testcount=sum(int(t.attrib['tests']) for t in tests); failures=sum(int(t.attrib['failures'])+int(t.attrib['errors']) for t in tests); assert testcount and failures==0
lint=ET.parse(root/'client/app/build/reports/lint-results-debug.xml').getroot(); errors=[i for i in lint.findall('issue') if i.attrib.get('severity') in {'Error','Fatal'}]; assert not errors
pdfs=[]
for extension,expected in [('docx',1),('xlsx',1),('pptx',2)]:
    file=evidence/'mobile-output'/(extension+'.pdf'); reader=PdfReader(file); text='\n'.join(p.extract_text() for p in reader.pages); assert len(reader.pages)==expected
    if extension=='docx': assert 'Bold edited' in text and 'Synthetic header' in text
    if extension=='xlsx': assert '12' in text and '001' in text
    if extension=='pptx': assert 'Edited slide title' in text and 'Untouched second slide' in text
    pdfs.append({'format':extension,'pageCount':expected,'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'bytes':file.stat().st_size})
report={'appJvmTests':testcount,'appJvmFailures':failures,'lintErrors':len(errors),'lintWarnings':len(lint.findall('issue')),'deviceControlledTests':33,'deviceRealModelTests':1,'pdfs':pdfs,'deployedSourceHashesMatch':True,'x86_64':json.loads(read(evidence/'flutter-x86_64-assets.json')),'arm64':json.loads(read(evidence/'flutter-arm64-assets.json'))}
(evidence/'artifact-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ['appJvmTests','appJvmFailures','lintErrors','lintWarnings','deployedSourceHashesMatch']}))
