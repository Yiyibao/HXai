"""Record a reviewed local command, raw output, exits and touched-source hashes."""
import argparse, hashlib, json, os, platform, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--feature', required=True)
parser.add_argument('--name', required=True)
parser.add_argument('--cwd', type=Path, required=True)
parser.add_argument('--timeout', type=int, default=1800)
parser.add_argument('command', nargs=argparse.REMAINDER)
args = parser.parse_args()
command = args.command[1:] if args.command[:1] == ['--'] else args.command
directory = ROOT / 'HXai_CHANGE_REVIEW' / args.feature
directory.mkdir(parents=True, exist_ok=True)
log = directory / (args.name + '.log')
record_file = directory / (args.name + '.json')
if log.exists() or record_file.exists():
    raise SystemExit('Refusing to overwrite earlier evidence')
def git(*command):
    return subprocess.check_output(['git', '-C', str(ROOT), *command], text=True, encoding='utf-8').strip()
paths = set(git('diff', '--name-only').splitlines()) | set(git('ls-files', '--others', '--exclude-standard', '--', 'client', 'server', 'scripts/record_phase2_run.py').splitlines())
hashes = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in sorted(paths) if (ROOT / p).is_file()}
start = time.time()
record = dict(command=command, cwd=str(args.cwd.resolve()), head=git('rev-parse', 'HEAD'), branch=git('branch', '--show-current'),
              python=sys.version, platform=platform.platform(), started_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), source_sha256=hashes)
exit_code = None
with log.open('w', encoding='utf-8') as output:
    output.write(json.dumps({k: record[k] for k in ['command', 'cwd', 'head', 'started_utc']}) + '\n')
    output.flush()
    try:
        result = subprocess.run(command, cwd=args.cwd, stdout=output, stderr=subprocess.STDOUT, timeout=args.timeout)
        exit_code = result.returncode
    except subprocess.TimeoutExpired:
        output.write('\nTIMEOUT: execution status must be checked, never infer no side effects.\n')
    except OSError as error:
        output.write('\n' + type(error).__name__ + ': command unavailable\n')
record.update(exit_code=exit_code, seconds=round(time.time() - start, 3), status='PASS' if exit_code == 0 else 'FAIL',
              log=str(log.relative_to(ROOT)), log_sha256=hashlib.sha256(log.read_bytes()).hexdigest())
record_file.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
print(args.name, record['status'], 'exit=', exit_code)
raise SystemExit(0 if exit_code == 0 else 1)
