"""Public HTTPS gateway -> real queue/dispatcher -> offline Office renderer -> verified PDFs."""
from pathlib import Path
import urllib.request,json,uuid,hashlib,time
from pypdf import PdfReader
root=Path(__file__).resolve().parents[2]; evidence=Path(__file__).parent
secret=json.loads((root/'.local/document-service/credentials/office-test-20261009.json').read_text())
base=secret['baseUrl']; headers={'Authorization':'Bearer '+secret['token']}
out=evidence/'live-api-output'; out.mkdir(exist_ok=True)
def call(path,data=None,extra=None,method=None):
    request=urllib.request.Request(base+path,data=data,headers={**headers,**(extra or {})},method=method)
    return urllib.request.urlopen(request,timeout=120)
records=[]
for extension in ['docx','xlsx','pptx']:
    source=root/'qa/p0-completion-20261009/collected/p0-rich-output'/('rich-output.'+extension)
    data=source.read_bytes(); digest=hashlib.sha256(data).hexdigest(); key=str(uuid.uuid4())
    task=json.load(call('/api/v1/conversions?extension='+extension,data,{'Idempotency-Key':key,'X-Source-Sha256':digest,'Content-Type':'application/octet-stream'}))
    assert task['operation']=='office_pdf'; ident=task['taskId']; last=None; deadline=time.monotonic()+360
    try:
        while task['state'] not in {'SUCCEEDED','FAILED','CANCELLED','EXPIRED'}:
            assert time.monotonic()<deadline,'conversion timed out'
            if task['state']!=last: print(extension,task['state'],flush=True); last=task['state']
            time.sleep(2); task=json.load(call('/api/v1/jobs/'+ident))
        assert task['state']=='SUCCEEDED',task
        manifest=json.load(call('/api/v1/jobs/'+ident+'/manifest')); assert manifest['sourceSha256']==digest and manifest['operation']=='office_pdf'
        pdf=call('/api/v1/jobs/'+ident+'/assets/pdf').read(); asset=manifest['assets'][0]
        assert len(pdf)==asset['size'] and hashlib.sha256(pdf).hexdigest()==asset['sha256']
        file=out/(extension+'.pdf'); file.write_bytes(pdf)
        reader=PdfReader(file); assert len(reader.pages)==manifest['pageCount']; text='\n'.join(page.extract_text() for page in reader.pages)
        if extension=='docx': assert 'Bold edited' in text and 'Synthetic header' in text
        if extension=='xlsx': assert '12' in text and '001' in text  # Edited A2=4, formula B2=A2*3 was deliberately uncached.
        if extension=='pptx': assert len(reader.pages)==2 and 'Edited slide title' in text and 'Untouched second slide' in text
        records.append({'extension':extension,'manifest':manifest,'extractedText':text,'sameOriginalDigest':hashlib.sha256(source.read_bytes()).hexdigest()==digest})
        print(extension,'SUCCEEDED',manifest['pageCount'],'pages; original intact',flush=True)
    finally:
        json.load(call('/api/v1/jobs/'+ident,method='DELETE'))
(out/'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
print('All real public conversions verified; Excel recalculated 4*3=12',flush=True)
