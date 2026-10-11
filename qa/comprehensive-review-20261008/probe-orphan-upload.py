import sys, json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'server/document-service'))
from api.store import Store
original=Path.write_text
def fail_request(self,*args,**kwargs):
    if self.name=='request.json': raise OSError('synthetic write failure')
    return original(self,*args,**kwargs)
with TemporaryDirectory(dir=Path(__file__).parent) as tmp:
    store=Store(Path(tmp))
    owner,_=store.issue()
    staged=store.root/'uploads'/'synthetic'
    staged.write_bytes(b'%PDF-1.7 synthetic review data')
    options={'extension':'pdf','pages':[1,1]}
    try:
        with patch.object(Path,'write_text',fail_request):
            store.enqueue(owner,'review-write-failure','synthetic-fingerprint',options,staged)
    except OSError:
        pass
    with store.db() as db: rows=db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]
    orphans=list((store.root/'jobs').glob('*/input/source.pdf'))
    result={'scenario':'enqueue request metadata write failure','database_job_rows':rows,'orphan_source_files':len(orphans),'staged_source_exists':staged.exists(),'finding_reproduced':rows==0 and len(orphans)==1}
    Path(__file__).with_name("orphan-upload-evidence.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
    print(json.dumps(result,indent=2))
    assert result['finding_reproduced']

