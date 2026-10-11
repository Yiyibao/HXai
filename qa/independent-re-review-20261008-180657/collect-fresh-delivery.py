"""Collect only this run's exact synthetic delivery names; never reuse a manifest."""
import hashlib, json, re, subprocess
from datetime import datetime
from pathlib import Path

QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]
ADB = ROOT / '.local/android-sdk/platform-tools/adb.exe'
batch = json.loads((QA / 'delivery-batch.json').read_text(encoding='utf-8-sig'))
records = []
def adb(*args):
    return subprocess.run([str(ADB), '-s', 'emulator-5554', *args], check=True, capture_output=True).stdout
def export(remote, destination, started):
    mtime = int(adb('shell', 'run-as', 'org.mathstudy.agent.debug', 'stat', '-c', '%Y', remote).strip())
    assert mtime >= int(started), f'Stale: {remote}'
    data = adb('exec-out', 'run-as', 'org.mathstudy.agent.debug', 'cat', remote)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return data
for group, remote, method in [('material', 'material-evidence', 'delivery-material'), ('artifact', 'review-delivery', 'delivery-artifact')]:
    run = next(item for item in batch if item['name'] == method)
    assert run['ok'], f'Incomplete method: {method}'
    started = datetime.fromisoformat(run['started']).timestamp()
    receipt = export(f'cache/{remote}/delivery-receipts.txt', QA / 'delivery' / group / 'receipts.txt', started).decode()
    for extension in ('md', 'docx', 'xlsx', 'pptx', 'pdf'):
        def entry(kind):
            lines = [line for line in receipt.splitlines() if line.startswith(extension + ' ' + kind + ' ')]
            assert len(lines) == 1
            return json.loads(lines[0].split(' ' + kind + ' ', 1)[1])
        source, view, send = entry('SOURCE'), entry('VIEW'), entry('SEND')
        name = source['name']
        assert re.fullmatch(r'QA交付-[a-f0-9-]{36}\.' + extension, name)
        import uuid
        assert str(uuid.UUID(name[5:-(len(extension)+1)])) == name[5:-(len(extension)+1)]
        raw = export(f'cache/{remote}/source-{extension}.{extension}', QA / 'delivery' / group / f'source.{extension}', started)
        delivered = adb('exec-out', 'cat', '/sdcard/Download/' + name)
        (QA / 'delivery' / group / f'delivered.{extension}').write_bytes(delivered)
        hashes = dict(source=hashlib.sha256(raw).hexdigest(), target=hashlib.sha256(delivered).hexdigest(),
                      view=view['sha256'], send=send['sha256'], recorded=source['sha256'])
        records.append(dict(group=group, extension=extension, exactName=name, method=method,
                            started=run['started'], status='PASS' if len(set(hashes.values())) == 1 else 'FAIL', hashes=hashes))
(QA / 'exact-delivery-hashes.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(checked=len(records), passed=sum(x['status']=='PASS' for x in records))))
