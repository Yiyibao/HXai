"""Local evidence recorder. No installations, network, signing or data cleanup."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'HXai_CHANGE_REVIEW/A04-GENERAL-MCP-SKILLS-20261022'

def snapshot():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'logs').mkdir(exist_ok=True)
    paths = []
    for prefix in ('client/app/src', 'client/flutter_ui/lib', 'client/flutter_ui/pigeons', 'client/flutter_ui/test', 'server/skill-sandbox'):
        paths.extend(p for p in (ROOT / prefix).rglob('*') if p.is_file())
    sources = {str(p.relative_to(ROOT)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    target = OUT / 'SOURCE_BEFORE.json'
    if target.exists():
        raise SystemExit('Initial snapshot exists; preserved')
    target.write_text(json.dumps(sources, indent=2), encoding='utf-8')
    for name, args in [('branch', ['branch', '--show-current']), ('head', ['rev-parse', 'HEAD']), ('status', ['status', '--porcelain=v1']), ('diff', ['diff', '--no-textconv', '--no-ext-diff', '--binary']), ('staged', ['diff', '--cached', '--binary'])]:
        (OUT / 'logs' / ('before-' + name + '.log')).write_bytes(subprocess.check_output(['git', *args], cwd=ROOT))

def run(name, args, cwd=ROOT):
    folder = OUT / 'logs'
    folder.mkdir(parents=True, exist_ok=True)
    log = folder / (name + '.log')
    if log.exists():
        raise SystemExit('Log exists; choose a new name')
    start = time.time()
    with log.open('wb') as stream:
        result = subprocess.run(args, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
    record = dict(name=name, command=args, cwd=str(cwd), exit_code=result.returncode, seconds=round(time.time()-start, 2), log=str(log.relative_to(OUT)), sha256=hashlib.sha256(log.read_bytes()).hexdigest())
    (folder / (name + '.json')).write_text(json.dumps(record, indent=2), encoding='utf-8')
    print(json.dumps(record), flush=True)
    print(log.read_text(encoding='utf-8', errors='replace')[-5000:], flush=True)
    return result.returncode

if __name__ == '__main__':
    if sys.argv[1] == 'snapshot':
        snapshot()
    else:
        raise SystemExit(run(sys.argv[1], sys.argv[2:]))
