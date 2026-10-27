"""Qualify only the previously owned B06 private Linux daemon with synthetic Skill code."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[1]
IMAGE='sha256:69e69785e7156672be426d8e758cfe1becdcfabd7583ae9ca9a88bc41e3c7433'
WSL=['wsl.exe','--distribution','HXai-B06-Linux-20261021','--user','root','--exec']

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir',type=Path,required=True)
    args=parser.parse_args()
    out=args.run_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    owner=json.loads(subprocess.check_output(WSL+['/bin/cat','/etc/hxai-b06-owned-instance.json']))
    expected=json.loads(subprocess.check_output(WSL+['/bin/cat','/opt/hxai-b06/source/OWNERSHIP.json']))
    if owner!=expected or owner['owner_nonce']!='22d0404ee58f4d24b3cb8bd911d7d1be':raise ValueError('Foreign Linux instance')
    info=json.loads(subprocess.check_output(WSL+['/usr/bin/env','DOCKER_HOST=unix:///run/hxai-b06/docker.sock','/usr/bin/docker','info','--format','{{json .}}']))
    if 'hxai.b06.owner='+owner['owner_nonce'] not in info['Labels']:raise ValueError('Foreign daemon')
    (out/'OWNERSHIP.json').write_text(json.dumps(owner,indent=2))
    (out/'DAEMON_INFO.json').write_text(json.dumps(info,indent=2))
    import uuid
    linux='/opt/hxai-a04/qualification-'+uuid.uuid4().hex
    subprocess.run(WSL+['/bin/mkdir','-p',linux+'/source','/opt/hxai-a04/runs'],check=True)
    stream=io.BytesIO();sources={}
    with tarfile.open(fileobj=stream,mode='w') as archive:
        for p in (ROOT/'server/skill-sandbox').glob('*.py'):
            archive.add(p,arcname=p.name);sources[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
    subprocess.run(WSL+['/bin/tar','-xf','-','-C',linux+'/source'],input=stream.getvalue(),check=True)
    (out/'SOURCE_INPUTS.json').write_text(json.dumps(sources,indent=2))
    command=WSL+['/usr/bin/env','A04_SANDBOX_IMAGE='+IMAGE,'/opt/hxai-b06/api-env/bin/python',linux+'/source/test_sandbox_service.py','SandboxLinuxTest']
    with (out/'runtime.log').open('wb') as log: result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    # Only synthetic qualification state; never any B06/production jobs or credentials.
    data=subprocess.check_output(WSL+['/bin/tar','-cf','-','-C','/opt/hxai-a04','runs'])
    (out/'SYNTHETIC_STATE.tar').write_bytes(data)
    (out/'COMMAND.json').write_text(json.dumps(dict(command=command,cwd=str(ROOT),exit_code=result.returncode,image=IMAGE,linux_source=linux+'/source',archive_sha256=hashlib.sha256(data).hexdigest()),indent=2))
    print((out/'runtime.log').read_text(encoding='utf-8',errors='replace'))
    raise SystemExit(result.returncode)

if __name__=='__main__':main()
