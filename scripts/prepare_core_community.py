"""Prepare pinned public skill fixtures for local Android compatibility tests; not app assets."""
import argparse, hashlib, json, re, subprocess, urllib.request
from pathlib import Path
root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(); parser.add_argument('--device', action='store_true'); args = parser.parse_args()
manifest = json.loads((root/'qa/core-v1/community-sources.json').read_text(encoding='utf-8'))
adb = str(root/'.local/android-sdk/platform-tools/adb.exe')
for item in manifest['files']:
    relative = item['path'].removeprefix('skills/')
    assert re.fullmatch(r'[A-Za-z0-9_./-]+', relative) and '..' not in relative.split('/')
    local = root/'.local/core-v1-community'/relative
    if not local.exists():
        request = urllib.request.Request(item['url'], headers={'User-Agent':'MathAgent-compatibility-QA'})
        with urllib.request.urlopen(request, timeout=30) as response: data = response.read()
        local.parent.mkdir(parents=True, exist_ok=True); local.write_bytes(data)
    data = local.read_bytes(); assert hashlib.sha256(data).hexdigest() == item['sha256']
    if args.device:
        target = 'cache/core-v1-community/'+relative
        subprocess.run([adb,'shell','run-as','org.mathstudy.agent.debug','mkdir','-p', str(Path(target).parent).replace('\\','/')], check=True, capture_output=True)
        subprocess.run([adb,'shell',"run-as org.mathstudy.agent.debug sh -c 'cat > "+target+"'"], input=data, check=True, capture_output=True)
print('Pinned hashes verified; '+('device fixtures prepared' if args.device else 'local fixtures prepared'))
