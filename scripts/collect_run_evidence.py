"""Collect synthetic evidence bound to a method, UUID, run window and APK pair.

Usage: python scripts/collect_run_evidence.py --context run-context.json --output QA/collected
Context: appSha256, testSha256, runs[{method,runId,log,logSha256,notBefore,notAfter}].
Times use Unix milliseconds. Failed method evidence is preserved as FAILED, never PASS.
"""
import argparse
import hashlib
import json
import re
import subprocess
import uuid
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def adb(*args):
    return subprocess.run([str(ADB), '-s', 'emulator-5554', *args], capture_output=True, check=True).stdout

def installed_hash(package, device):
    paths = device('shell', 'pm', 'path', package).decode().splitlines()
    if len(paths) != 1 or not paths[0].startswith('package:/data/app/'):
        raise ValueError('Unexpected APK installation layout')
    value = device('shell', 'sha256sum', paths[0][8:]).decode().split()[0]
    if not re.fullmatch('[a-fA-F0-9]{64}', value):
        raise ValueError('Invalid APK hash')
    return value.lower()

def method_terminal(log, method):
    """Trust the runner's exact method terminal, never a caller's early manifest.

    Status 2 is an intermediate evidence bundle. Status 1 starts a method;
    0/-1/-2/-3/-4 terminate it. Short manifest classes must resolve uniquely.
    """
    blocks, fields = [], {}
    for line in log.splitlines():
        match = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)$', line)
        if match:
            fields[match[1]] = match[2].strip()
        code = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*', line)
        if code:
            if 'class' in fields and 'test' in fields:
                blocks.append((fields['class'], fields['test'], int(code[1])))
            fields = {}
    cls, test = method.rsplit('.', 1)
    candidates = {(c, t) for c, t, _ in blocks if t == test and (c == cls or ('.' not in cls and c.rsplit('.', 1)[-1] == cls))}
    if len(candidates) != 1:
        raise ValueError('Missing or ambiguous exact class/test terminal')
    identity = next(iter(candidates))
    events = [code for c, t, code in blocks if (c, t) == identity and code != 2]
    if len(events) != 2 or events[0] != 1 or events[1] not in (0, -1, -2, -3, -4):
        raise ValueError('Missing or duplicate method terminal')
    if not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', log, re.MULTILINE) or re.search(
            r'INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_RESULT: error=', log, re.IGNORECASE):
        raise ValueError('Missing or crashed invocation terminal')
    return identity, 'PASS' if events[1] == 0 else 'FAILED'

def collect(context_path, output, device=adb):
    context_path, output = Path(context_path), Path(output)
    context_bytes = context_path.read_bytes()
    context = json.loads(context_bytes.decode('utf-8-sig'))
    app = installed_hash('org.mathstudy.agent.debug', device)
    test = installed_hash('org.mathstudy.agent.debug.test', device)
    if (app, test) != (context['appSha256'].lower(), context['testSha256'].lower()):
        raise ValueError('Installed APK pair differs from this run')
    manifest_path = output / 'fixture-manifest.json'
    existing = json.loads(manifest_path.read_text(encoding='utf-8')) if manifest_path.exists() else None
    if existing is not None and (not isinstance(existing, dict) or existing.get('schema') != 2):
        raise ValueError('Legacy manifest has no method/run/APK binding')
    if existing and existing.get('contextSha256') != digest(context_bytes):
        raise ValueError('Collection belongs to another run context')
    records, seen, pending = [], set(), []
    for run in context['runs']:
        method, run_id = run['method'], run['runId']
        if not re.fullmatch(r'[A-Za-z0-9_.-]+\.[A-Za-z0-9_]+', method) or str(uuid.UUID(run_id)) != run_id:
            raise ValueError('Invalid method/run identity')
        identity = (method, run_id)
        if identity in seen:
            raise ValueError('Duplicate run identity')
        seen.add(identity)
        log_path = context_path.parent / run['log']
        if not log_path.resolve().is_relative_to(context_path.parent.resolve()):
            raise ValueError('Run log escapes its QA directory')
        log = log_path.read_bytes()
        if digest(log) != run['logSha256'].lower():
            raise ValueError('Run log was changed')
        manifests = [json.loads(value) for value in re.findall(r'^INSTRUMENTATION_STATUS: qa_evidence_manifest=(.*)$',
                     log.decode('utf-8-sig'), re.MULTILINE)]
        matching = [value for value in manifests if (value.get('method'), value.get('runId')) == identity]
        if len(matching) != 1:
            raise ValueError('Missing or duplicate method/run manifest')
        manifest = matching[0]
        started, finished = manifest['startedAt'], manifest['finishedAt']
        if not run['notBefore'] <= started <= finished <= run['notAfter']:
            raise ValueError('Run manifest is outside its execution window')
        if manifest.get('status') not in ('PASS', 'FAILED'):
            raise ValueError('Unknown method status')
        actual_method, terminal = method_terminal(log.decode('utf-8-sig'), method)
        if manifest['status'] == 'PASS' and terminal != 'PASS':
            raise ValueError('Manifest PASS contradicts actual class/test terminal')
        status = 'PASS' if manifest['status'] == terminal == 'PASS' else 'FAILED'
        if not manifest['entries']:
            raise ValueError('No evidence was produced')
        names = set()
        for entry in manifest['entries']:
            name = entry['name']
            parts = PurePosixPath(name).parts
            if str(PurePosixPath(name)) != name or parts[:3] != ('qa-evidence', method, run_id) or len(parts) <= 3 or any(p in ('.', '..') for p in parts) or '\\' in name or ':' in name:
                raise ValueError('Evidence is outside the method/run directory')
            if name in names:
                raise ValueError('Duplicate evidence path')
            names.add(name)
            remote = 'cache/' + name
            stamp = int(device('shell', 'run-as', 'org.mathstudy.agent.debug', 'stat', '-c', '%Y', remote).strip())
            if not started // 1000 <= stamp <= finished // 1000 + 1:
                raise ValueError('Stale evidence: ' + name)
            data = device('exec-out', 'run-as', 'org.mathstudy.agent.debug', 'cat', remote)
            sha = digest(data)
            if sha != entry['sha256'] or len(data) != entry['bytes']:
                raise ValueError('Device evidence differs from its finalized manifest')
            target = output / name
            if not target.resolve().is_relative_to(output.resolve()):
                raise ValueError('Local evidence path escapes collection')
            if target.exists() and digest(target.read_bytes()) != sha:
                raise ValueError('Existing local evidence was changed')
            pending.append((target, data))
            records.append(dict(entry, method=method, runId=run_id, status=status, actualClass=actual_method[0], actualTest=actual_method[1], deviceMtime=stamp,
                                startedAt=started, finishedAt=finished))
    if not records:
        raise ValueError('No runs selected')
    for target, data in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            with target.open('xb') as file:
                file.write(data)
    result = dict(schema=2, contextSha256=digest(context_bytes), appSha256=app, testSha256=test, records=records)
    output.mkdir(parents=True, exist_ok=True)
    temporary = manifest_path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(manifest_path)
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = collect(args.context, args.output)
    print(json.dumps(dict(collected=len(result['records']), statuses=sorted({r['status'] for r in result['records']}))))

if __name__ == '__main__':
    main()
