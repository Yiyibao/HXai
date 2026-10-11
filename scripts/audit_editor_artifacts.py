"""Pull only the known synthetic editor evidence and verify output hashes/package preservation."""
import hashlib, io, json, subprocess, tarfile
from pathlib import Path
from zipfile import ZipFile
root = Path(__file__).resolve().parents[1]
destination = root / 'qa/core-v1/editor-artifacts'
destination.mkdir(parents=True, exist_ok=True)
archive = subprocess.run([str(root/'.local/android-sdk/platform-tools/adb.exe'), 'exec-out', 'run-as', 'org.mathstudy.agent.debug', 'tar', '-cf', '-', '-C', 'cache/core-v1-edit-evidence', '.'], check=True, capture_output=True).stdout
with tarfile.open(fileobj=io.BytesIO(archive)) as package:
    for member in package.getmembers():
        if not member.isfile(): continue
        name = Path(member.name).name
        assert member.name in (name, './'+name), member.name
        (destination/name).write_bytes(package.extractfile(member).read())
report = json.loads((destination/'results.json').read_text(encoding='utf-8'))
for item in report['files']:
    path = destination/('output-'+item['name'])
    assert hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], item['name']
for extension, modified in [('docx', 'word/document.xml'), ('xlsx', 'xl/worksheets/sheet1.xml')]:
    with ZipFile(destination/('source-original.'+extension)) as source, ZipFile(destination/('output-QA edited.'+extension)) as output:
        assert set(source.namelist()) == set(output.namelist())
        assert source.read(modified) != output.read(modified)
        assert all(source.read(name) == output.read(name) for name in source.namelist() if name != modified)
print('4 output hashes and unchanged Word/Excel package parts verified')
