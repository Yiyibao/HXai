"""Fetch the fixed S0 NDK from Google's repository with resumable verified parts.

Run after accepting the SDK licenses with sdkmanager. Only project-local files.
"""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import re
import shutil
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / '.local/android-sdk'
VERSION = '28.2.13676358'
PART_SIZE = 16 * 1024 * 1024


def main():
    repo = ET.parse(ROOT / '.local/android-repository.xml').getroot()
    package = next(e for e in repo if e.attrib.get('path') == 'ndk;' + VERSION)
    license_id = package.find('uses-license').attrib['ref']
    accepted = SDK / 'licenses' / license_id
    # sdkmanager canonicalizes license text according to its repository schema.
    # Read its recorded acceptance rather than manufacturing an acceptance hash.
    if not accepted.exists() or not re.search(r'(?m)^[0-9a-f]{40}$', accepted.read_text()):
        raise ValueError('Accept the official SDK license with sdkmanager before installation.')
    archive = next(e.find('complete') for e in package.find('archives') if e.findtext('host-os') == 'windows')
    url = 'https://dl.google.com/android/repository/' + archive.findtext('url')
    size, digest = int(archive.findtext('size')), archive.findtext('checksum')
    folder = ROOT / '.local/ndk-parts'
    folder.mkdir(parents=True, exist_ok=True)

    def fetch(index):
        start, end = index * PART_SIZE, min(size, (index + 1) * PART_SIZE) - 1
        part = folder / f'{index:04d}.part'
        if part.exists() and part.stat().st_size == end - start + 1:
            return part
        for attempt in range(3):
            try:
                request = urllib.request.Request(url, headers={'Range': f'bytes={start}-{end}'})
                with urllib.request.urlopen(request, timeout=45) as response:
                    if response.status != 206 or not response.headers.get('Content-Range', '').startswith(f'bytes {start}-{end}/'):
                        raise ValueError('server does not support verified ranges')
                    with part.open('wb') as output:
                        shutil.copyfileobj(response, output, 1024 * 1024)
                if part.stat().st_size != end - start + 1:
                    raise ValueError('incomplete part')
                return part
            except (OSError, ValueError):
                if attempt == 2:
                    raise
                time.sleep(1)

    count = (size + PART_SIZE - 1) // PART_SIZE
    with ThreadPoolExecutor(max_workers=6) as executor:
        parts = list(executor.map(fetch, range(count)))
    local_archive = ROOT / '.local/android-ndk-r28c-windows.zip'
    actual = hashlib.sha1()
    with local_archive.open('wb') as output:
        for part in parts:
            with part.open('rb') as source:
                while chunk := source.read(1024 * 1024):
                    output.write(chunk)
                    actual.update(chunk)
    if actual.hexdigest() != digest:
        raise ValueError('official NDK archive checksum mismatch; do not install')
    destination = (SDK / 'ndk' / VERSION).resolve()
    with zipfile.ZipFile(local_archive) as bundle:
        prefix = bundle.namelist()[0].split('/')[0] + '/'
        for entry in bundle.namelist():
            if entry.endswith('/'):
                continue
            target = (destination / entry.removeprefix(prefix)).resolve()
            if not target.is_relative_to(destination):
                raise ValueError('archive path escape')
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(entry) as source, target.open('wb') as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
    print(f'NDK {VERSION} installed from official verified archive ({size} bytes)', flush=True)


if __name__ == '__main__':
    main()
