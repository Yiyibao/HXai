"""Run unchanged Pro references in a fresh owned Linux directory; no installs/daemon changes."""
from pathlib import Path
import hashlib,io,json,subprocess,tarfile,uuid,argparse
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--run-dir',type=Path,required=True);args=p.parse_args()
OUT=args.run_dir.resolve()
WSL=['wsl.exe','--distribution','HXai-B06-Linux-20261021','--user','root','--exec']
OUT.mkdir(exist_ok=False)
owner=json.loads(subprocess.check_output(WSL+['/bin/cat','/etc/hxai-b06-owned-instance.json']))
assert owner['owner_nonce']=='22d0404ee58f4d24b3cb8bd911d7d1be'
(OUT/'OWNERSHIP.json').write_text(json.dumps(owner,indent=2),encoding='utf-8')
linux='/opt/hxai-a045/pro-'+uuid.uuid4().hex
subprocess.run(WSL+['/bin/mkdir','-p',linux],check=True)
data=io.BytesIO();sources={}
with tarfile.open(fileobj=data,mode='w') as tar:
    for p in (ROOT/'HXai_Pro_Delivery/07_CODE').rglob('*'):
        if p.is_file() and '__pycache__' not in p.parts and '.pytest_cache' not in p.parts:
            rel=p.relative_to(ROOT/'HXai_Pro_Delivery').as_posix()
            sources[rel]=hashlib.sha256(p.read_bytes()).hexdigest();tar.add(p,arcname=rel)
    metadata=ROOT/'HXai_Pro_Delivery/evidence/source_snapshot.json'
    sources['evidence/source_snapshot.json']=hashlib.sha256(metadata.read_bytes()).hexdigest()
    tar.add(metadata,arcname='evidence/source_snapshot.json')
(OUT/'SOURCE_INPUTS.json').write_text(json.dumps(sources,indent=2),encoding='utf-8')
subprocess.run(WSL+['/bin/tar','-xf','-','-C',linux],input=data.getvalue(),check=True)
command=WSL+['/opt/hxai-b06/api-env/bin/python',linux+'/07_CODE/run_verification.py']
with (OUT/'runtime.log').open('wb') as log:
    result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
evidence=subprocess.check_output(WSL+['/bin/tar','-cf','-','-C',linux,'evidence'])
(OUT/'EVIDENCE.tar').write_bytes(evidence)
runs=subprocess.check_output(WSL+['/bin/cat',linux+'/evidence/test_runs.json'])
(OUT/'TEST_RUNS.json').write_bytes(runs)
(OUT/'COMMAND.json').write_text(json.dumps(dict(command=command,exit_code=result.returncode,linux_source=linux,evidence_sha256=hashlib.sha256(evidence).hexdigest()),indent=2),encoding='utf-8')
print((OUT/'runtime.log').read_text(encoding='utf-8',errors='replace'))
raise SystemExit(result.returncode)
