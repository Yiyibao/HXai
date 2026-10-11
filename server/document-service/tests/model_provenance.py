import hashlib
import json
from pathlib import Path

manifest=json.loads(Path('/tmp/mathdoc-model-manifest.json').read_text())
root=Path('/var/lib/mathdoc-models/MinerU-4_models_onnx')
files=[]
for item in manifest['files']:
    path=root/item['path']
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        while data:=stream.read(1024*1024): digest.update(data)
    actual=digest.hexdigest()
    assert path.stat().st_size==item['size'] and actual==item['sha256'],item['path']
    files.append({'path':item['path'],'size':path.stat().st_size,'sha256':actual})
commits=sorted({p.read_text().splitlines()[0] for p in root.rglob('*.metadata')})
report={'repository':manifest['repository'],'revision':'358310b4f64b95f9fefc372ad899356e4111f376','downloadMetadataCommits':commits,'allFilesMatchOfficialManifest':True,'files':files}
Path('/opt/mathdoc/model-provenance.json').write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='files'}))
