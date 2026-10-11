"""Verify only exact synthetic output names from already validated method/UUID evidence."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

QA = Path(__file__).resolve().parent
ADB = QA.parents[1] / '.local/android-sdk/platform-tools/adb.exe'
collected = QA / 'delivery-collected'
manifest = json.loads((collected / 'fixture-manifest.json').read_text(encoding='utf-8'))
records = []
for item in manifest['records']:
    if not item['name'].endswith('/delivery-receipts.txt'):
        continue
    assert item['status'] == 'PASS'
    receipt_file = collected / item['name']
    assert hashlib.sha256(receipt_file.read_bytes()).hexdigest() == item['sha256']
    text = receipt_file.read_text(encoding='utf-8')
    for extension in ('md', 'docx', 'xlsx', 'pptx', 'pdf'):
        def entry(kind):
            rows = [row for row in text.splitlines() if row.startswith(extension + ' ' + kind + ' ')]
            assert len(rows) == 1
            return json.loads(rows[0].split(' ' + kind + ' ', 1)[1])
        source, view, send = entry('SOURCE'), entry('VIEW'), entry('SEND')
        assert re.fullmatch(r'QA交付-[a-f0-9-]{36}\.' + extension, source['name'])
        own_source = receipt_file.parent / f'source-{extension}.{extension}'
        exact_path = '/sdcard/Download/' + source['name']
        data = subprocess.run([str(ADB), '-s', 'emulator-5554', 'exec-out', 'cat', exact_path], check=True, capture_output=True).stdout
        destination = QA / 'exact-targets' / item['runId'] / source['name']
        destination.parent.mkdir(parents=True, exist_ok=True)
        assert not destination.exists()
        destination.write_bytes(data)
        hashes = dict(source=hashlib.sha256(own_source.read_bytes()).hexdigest(),
                      target=hashlib.sha256(data).hexdigest(), view=view['sha256'], send=send['sha256'], recorded=source['sha256'])
        records.append(dict(method=item['method'], runId=item['runId'], name=source['name'],
                            profileId=source['profileId'], materialId=source['id'],
                            status='PASS' if len(set(hashes.values())) == 1 else 'FAIL', hashes=hashes))
(QA / 'exact-delivery-hashes.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
assert len(records) == 10 and all(row['status'] == 'PASS' for row in records)
print(json.dumps(dict(checked=len(records), passed=sum(row['status'] == 'PASS' for row in records))))
