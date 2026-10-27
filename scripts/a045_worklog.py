"""Append-only local evidence for OAuth and trustworthy costs; never installs dependencies."""
import hashlib, json, subprocess, sys, time, shutil
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'HXai_CHANGE_REVIEW/A04-OAUTH-A05-COST-20261022'

def snapshot():
    OUT.mkdir(exist_ok=False, parents=True)
    (OUT/'logs').mkdir()
    sources = {}
    for prefix in ('client/app/src','client/ai/src','client/flutter_ui/lib','client/flutter_ui/pigeons','client/flutter_ui/test','server/skill-sandbox'):
        for p in (ROOT/prefix).rglob('*'):
            if p.is_file() and '__pycache__' not in p.parts:
                rel=p.relative_to(ROOT)
                sources[rel.as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
                q=OUT/'before'/rel
                q.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(p,q)
    (OUT/'SOURCE_BEFORE.json').write_text(json.dumps(sources,indent=2),encoding='utf-8')
    for name,args in [('branch',['branch','--show-current']),('head',['rev-parse','HEAD']),('status',['status','--porcelain=v1']),('diff',['diff','--no-textconv','--no-ext-diff','--binary']),('staged',['diff','--cached','--binary'])]:
        (OUT/'logs'/f'before-{name}.log').write_bytes(subprocess.check_output(['git',*args],cwd=ROOT))

def run(name,args):
    cwd=ROOT
    if args[0].startswith('--cwd='): cwd=ROOT/args.pop(0)[6:]
    p=OUT/'logs'/f'{name}.log'
    if p.exists(): raise RuntimeError('Preserve old logs; choose a new name')
    source_paths=[]
    for prefix in ('client/app/src','client/ai/src','client/flutter_ui/lib','client/flutter_ui/pigeons','client/flutter_ui/test','server/skill-sandbox'):
        source_paths.extend(f for f in (ROOT/prefix).rglob('*') if f.is_file() and '__pycache__' not in f.parts)
    p.with_suffix('.SOURCE.json').write_text(json.dumps({f.relative_to(ROOT).as_posix():hashlib.sha256(f.read_bytes()).hexdigest() for f in source_paths},indent=2),encoding='utf-8')
    if (cwd/args[0]).is_file(): args[0]=str((cwd/args[0]).resolve())
    elif (ROOT/args[0]).is_file(): args[0]=str((ROOT/args[0]).resolve())
    start=time.time()
    with p.open('wb') as f: result=subprocess.run(args,cwd=cwd,stdout=f,stderr=subprocess.STDOUT)
    record=dict(name=name,command=args,cwd=str(cwd),exit_code=result.returncode,seconds=round(time.time()-start,2),log=str(p.relative_to(OUT)),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
    p.with_suffix('.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
    print(json.dumps(record),flush=True)
    print(p.read_text(encoding='utf-8',errors='replace')[-4500:],flush=True)
    return result.returncode

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8',errors='replace')
    if sys.argv[1]=='snapshot': snapshot()
    else: sys.exit(run(sys.argv[1],sys.argv[2:]))
