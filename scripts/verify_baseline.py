"""Verify the vendored baseline against the downloaded, fixed upstream archives."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify_archive(archive, destination):
    count = 0
    with zipfile.ZipFile(archive) as bundle:
        prefix = bundle.namelist()[0].split('/')[0] + '/'
        for entry in bundle.namelist():
            if entry.endswith('/'):
                continue
            relative = entry.removeprefix(prefix)
            target = (destination / relative).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError('archive path escape')
            if target.read_bytes() != bundle.read(entry):
                raise ValueError(f'baseline mismatch: {relative}')
            count += 1
    return count


if __name__ == '__main__':
    lock = json.loads((ROOT / 'docs/upstream-lock.json').read_text())
    archive = ROOT / '.local/rikkahub-2.5.6.zip'
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == lock['archive_sha256']
    result = {'commit': lock['commit'], 'files_verified': verify_archive(archive, ROOT / 'client'),
              'submodule_files_verified': verify_archive(ROOT / '.local/material-color-utilities.zip',
                  ROOT / lock['submodules'][0]['destination'])}
    print(json.dumps(result, indent=2))
