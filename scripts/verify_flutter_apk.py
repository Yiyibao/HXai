"""Verify the installed host build contains the current Flutter module assets."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--apk', type=Path, default=root / 'client/app/build/outputs/apk/debug/app-x86_64-debug.apk')
args = parser.parse_args()
apk = args.apk.resolve()
aar = root / 'client/flutter_ui/build/host/outputs/repo/me/rerere/rikkahub/flutter_ui/flutter_debug/1.0/flutter_debug-1.0.aar'
digest = lambda value: hashlib.sha256(value).hexdigest()
with ZipFile(apk) as host, ZipFile(aar) as module:
    assets = [name for name in module.namelist() if name.startswith('assets/flutter_assets/') and not name.endswith('/')]
    assert assets, 'No Flutter assets in module'
    mismatches = [name for name in assets if name not in host.namelist() or digest(host.read(name)) != digest(module.read(name))]
    report = {'apk': str(apk), 'apk_sha256': digest(apk.read_bytes()), 'aar_sha256': digest(aar.read_bytes()),
              'native_abis': sorted({name.split('/')[1] for name in host.namelist() if name.startswith('lib/') and name.endswith('.so')}),
              'assets_checked': len(assets), 'mismatches': mismatches,
              'sources': {str(path.relative_to(root)): digest(path.read_bytes()) for path in sorted((root/'client/flutter_ui/lib').rglob('*.dart'))}}
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
assert not mismatches, f'Stale Flutter assets: {mismatches}'
print(f'{len(assets)} Flutter assets match; APK SHA-256 {report["apk_sha256"]}')
