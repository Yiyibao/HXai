from pathlib import Path
import subprocess, json, urllib.request
root = Path(__file__).resolve().parents[2]
adb = str(root / '.local/android-sdk/platform-tools/adb.exe')
secret = root / '.local/document-service/credentials/p0-test-20261009.json'
value = json.loads(secret.read_text())
request = urllib.request.Request(value['baseUrl'] + '/api/v1/capabilities', headers={'Authorization': 'Bearer ' + value['token']})
capabilities = json.load(urllib.request.urlopen(request, timeout=30))
(Path(__file__).parent / 'live-capabilities.json').write_text(json.dumps(capabilities, indent=2))
print('Live capabilities verified:', capabilities['ready'], capabilities['parserVersion'])
subprocess.run([adb, 'shell', 'run-as', 'org.mathstudy.agent.debug', 'sh', '-c', '"umask 077; cat > cache/document-service-test-credentials.json"'], input=secret.read_bytes(), check=True, stdout=subprocess.DEVNULL)
subprocess.run([adb, 'push', str(Path(__file__).parent / 'samples'), '/data/local/tmp/p0-rich-input'], check=True)
subprocess.run([adb, 'shell', 'run-as', 'org.mathstudy.agent.debug', 'cp', '-r', '/data/local/tmp/p0-rich-input', 'cache/'], check=True)
print('Private test credential and synthetic samples prepared')
