"""Transport the reviewed minimal source and fail-closed operator over SSH stdin."""
import base64
import json
import subprocess
import sys
from prepare import HERE, SSH, build

online, candidates, patch, manifest = build()
reviewed = json.loads((HERE / 'SOURCE_MANIFEST.json').read_text())
assert manifest == reviewed, 'reviewed deployment changed'
assert (HERE / 'FIXES.patch').read_text(encoding='utf-8') == patch, 'reviewed patch changed'
payload = {'manifest':manifest, 'candidates':{rel:base64.b64encode(data).decode() for rel,data in candidates.items()}}
encoded = base64.b64encode(json.dumps(payload).encode()).decode()
script = (HERE / 'remote_deploy.py').read_text(encoding='utf-8')
assert script.count('# PAYLOAD_INJECTION') == 1
script = script.replace('# PAYLOAD_INJECTION', 'PAYLOAD = json.loads(base64.b64decode(' + repr(encoded) + '))')
compile(script, 'remote_deploy.py', 'exec')
process = subprocess.Popen(SSH + ['sudo -n python3 -B -'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
with (HERE / 'DEPLOYMENT.log').open('w', encoding='utf-8', newline='\n') as log:
    output, errors = process.communicate(script.encode(), timeout=420)
    # The remote operator emits exclusively allowlisted, secret-free JSON events.
    # SSH diagnostics never receive credentials (all synthetic secrets stay remote).
    text = output.decode('utf-8')
    log.write(text)
    print(text, end='')
    if errors:
        log.write(errors.decode('utf-8'))
        print(errors.decode('utf-8'), file=sys.stderr, end='')
for line in text.splitlines():
    record = json.loads(line)
    if record.get('event') == 'result':
        (HERE / 'TEST_RESULTS.json').write_text(json.dumps(record, indent=2)+'\n')
sys.exit(process.returncode)
