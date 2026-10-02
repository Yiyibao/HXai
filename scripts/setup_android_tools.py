"""Download official Windows command-line tools into ignored .local/android-sdk.

Does not change global PATH or install platform/build-tools packages.
"""
import argparse
import hashlib
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / '.local/android-sdk'


def download_package(package, folder):
    for archive in package.find('archives'):
        host = archive.findtext('host-os')
        if host not in (None, 'windows'):
            continue
        complete = archive.find('complete')
        url = complete.findtext('url')
        expected = complete.findtext('checksum')
        target = ROOT / '.local' / url
        if not target.exists() or hashlib.sha1(target.read_bytes()).hexdigest() != expected:
            with urllib.request.urlopen('https://dl.google.com/android/repository/' + url, timeout=60) as response:
                with target.open('wb') as output:
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
        if hashlib.sha1(target.read_bytes()).hexdigest() != expected:
            raise ValueError('official archive checksum mismatch')
        with zipfile.ZipFile(target) as bundle:
            for entry in bundle.namelist():
                relative = entry
                if folder.startswith('cmdline-tools/'):
                    relative = entry.removeprefix('cmdline-tools/')
                destination = (SDK / folder / relative if folder.startswith('cmdline-tools/') else SDK / relative).resolve()
                if not destination.is_relative_to(SDK.resolve()):
                    raise ValueError('archive path escape')
                if not entry.endswith('/'):
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    content = bundle.read(entry)
                    if not destination.exists() or destination.read_bytes() != content:
                        destination.write_bytes(content)
        print(package.attrib['path'] + ': downloaded and verified', flush=True)
        return
    raise ValueError('Windows archive missing')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--command-line-version', default='latest')
    args = parser.parse_args()
    data = urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=60).read()
    (ROOT / '.local/android-repository.xml').write_bytes(data)
    packages = {e.attrib['path']: e for e in ET.fromstring(data) if e.tag.endswith('remotePackage')}
    download_package(packages['platform-tools'], 'platform-tools')
    download_package(packages['cmdline-tools;' + args.command_line_version], 'cmdline-tools/' + args.command_line_version)
    print('Tools installed in project-local SDK; see qa/s0/README.md for platform installation.')
