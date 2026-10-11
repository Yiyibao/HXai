"""Exercise the unchanged collector with synthetic files and a mocked ADB boundary."""
import hashlib, json, os, runpy, shutil, subprocess, time
from pathlib import Path
from unittest.mock import patch

QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]
original = ROOT / 'qa/review-fixes-20261008/collect.py'
records = []
for case in ('existing-manifest-stale', 'fresh-manifest-stale'):
    folder = QA / 'collector-probes' / case
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / 'collect.py'
    shutil.copyfile(original, target)
    (folder / 'material-diagnostic.log').write_text('synthetic method start', encoding='utf-8')
    names = ['review-real-teaching.md', 'material-real-model.md', 'material-evidence/fidelity.docx',
             'material-evidence/word-vision-output.docx', 'material-evidence/scan-input.pdf',
             'material-evidence/scan-output.md']
    calls = []
    old = int(time.time()) - 86400
    manifest = []
    for name in names:
        local = folder / 'fixtures' / name
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_bytes(b'synthetic evidence from yesterday')
        os.utime(local, (old, old))
        manifest.append(dict(name=name, deviceMtime=old, bytes=local.stat().st_size,
                             sha256=hashlib.sha256(local.read_bytes()).hexdigest()))
    if case.startswith('existing'):
        (folder / 'fixture-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
    def fake_run(args, **kwargs):
        calls.append(args[3:])
        return subprocess.CompletedProcess(args, 0, str(old).encode() if 'stat' in args else b'synthetic old bytes', b'')
    error = None
    with patch('subprocess.run', fake_run):
        try:
            runpy.run_path(str(target))
        except AssertionError as exc:
            error = str(exc)
    records.append(dict(case=case, expectation='Reject yesterday evidence', rejected=error is not None,
                        status='PASS' if error else 'FAIL', error=error, adbCalls=len(calls),
                        collectorSha256=hashlib.sha256(original.read_bytes()).hexdigest()))
(QA / 'collector-probe.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
print(json.dumps(records, indent=2))
