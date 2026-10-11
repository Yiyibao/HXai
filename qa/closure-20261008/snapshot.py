import argparse, hashlib, json, shutil
from pathlib import Path
QA=Path(__file__).resolve().parent; ROOT=QA.parents[1]
p=argparse.ArgumentParser(); p.add_argument('name'); args=p.parse_args(); out=QA/args.name; out.mkdir(exist_ok=False)
paths=[]
for folder in ('client/app/src','client/ai/src','client/app/schemas','server/document-service','scripts/tests'):
    paths += [f for f in (ROOT/folder).rglob('*') if f.is_file() and f.suffix in ('.kt','.py','.xml','.json','.kts') and '__pycache__' not in f.parts]
paths += [ROOT/'scripts/collect_run_evidence.py',ROOT/'client/app/build.gradle.kts']
paths += list((QA/'android').glob('*.kt'))+list((QA/'unit').glob('*.kt'))
paths += [f for f in QA.iterdir() if f.is_file() and f.suffix in ('.py','.ps1','.gradle')]
records={}
for f in paths:
    relative=f.relative_to(ROOT); target=out/relative; target.parent.mkdir(parents=True,exist_ok=True); shutil.copyfile(f,target)
    records[relative.as_posix()]=hashlib.sha256(f.read_bytes()).hexdigest()
(out/'hashes.json').write_text(json.dumps(records,indent=2))
