"""Record public deployment facts and aggregate state, without credentials."""
import json
import subprocess
import urllib.request
from pathlib import Path
from api.store import Store

def command(*args):
    return subprocess.check_output(args, text=True).strip()

store = Store(Path('/var/lib/mathdoc'))
with store.db() as db:
    states = {row['state']: row['count'] for row in db.execute('SELECT state, COUNT(*) count FROM jobs GROUP BY state')}
result = {
    'gatewayPython': command('/opt/mathdoc/api-env/bin/python', '--version'),
    'workerImage': Path('/etc/mathdoc/worker.env').read_text().strip(),
    'ready': json.loads((store.root / 'worker-ready.json').read_text()),
    'services': {name: command('systemctl', 'is-active', name) for name in ['mathdoc-api', 'mathdoc-dispatcher', 'mathstudy-api', 'yubai-blog']},
    'jobStates': states,
    'remainingWorkerContainers': command('docker', 'ps', '-a', '--filter', 'name=mathdoc-', '--format', '{{.Names}}'),
    'taskOutputMounts': [line for line in Path('/proc/mounts').read_text().splitlines() if '/var/lib/mathdoc/jobs/' in line],
    'publicHealth': json.load(urllib.request.urlopen('https://hxnf.top/documents/health', timeout=15)),
    'memory': command('free', '-h'),
    'disk': command('df', '-h', '/'),
    'credentialStorage': '/var/lib/mathdoc-admin/credentials (root 0700 / files 0600)',
    'backupScopeChecked': 'existing business allowlist and configuration /etc do not include task data or admin credentials',
}
result['credentialCapabilities'] = []
for path in sorted(Path('/var/lib/mathdoc-admin/credentials').glob('learner-*.json')):
    credential = json.loads(path.read_text())
    request = urllib.request.Request(credential['baseUrl'] + '/api/v1/capabilities', headers={'Authorization': 'Bearer ' + credential['token']})
    capabilities = json.load(urllib.request.urlopen(request, timeout=15))
    result['credentialCapabilities'].append({'file': path.name, 'ready': capabilities['ready'], 'tier': capabilities['tiers']})
try:
    urllib.request.urlopen('https://hxnf.top/documents/api/v1/capabilities', timeout=15)
    result['unauthenticatedStatus'] = 200
except urllib.error.HTTPError as error:
    result['unauthenticatedStatus'] = error.code
Path('/opt/mathdoc/deployment.json').write_text(json.dumps(result, indent=2))
print('deployment_snapshot_written')
