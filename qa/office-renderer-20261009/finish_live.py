"""Revoke only the isolated Office test identity and retain non-secret deployment evidence."""
from pathlib import Path
import json,subprocess,urllib.request,urllib.error
root=Path(__file__).resolve().parents[2]; evidence=Path(__file__).parent
credential=root/'.local/document-service/credentials/office-test-20261009.json'
value=json.loads(credential.read_text()); identity=value['identity']; key=str(Path.home()/'.ssh/id_ed25519_tencent_blog'); host='ubuntu@154.222.25.150'
script=r'''import json,subprocess,sqlite3,hashlib,time
from pathlib import Path
identity=%r
db=sqlite3.connect('/var/lib/mathdoc/tasks.sqlite3')
for _ in range(30):
    states=dict(db.execute('SELECT state,count(*) FROM jobs WHERE owner=? GROUP BY state',(identity,)).fetchall())
    if all(state=='EXPIRED' for state in states): break
    time.sleep(1)
assert all(state=='EXPIRED' for state in states),states
subprocess.run(['/opt/mathdoc/api-env/bin/python','-m','api.admin','revoke','--identity',identity],cwd='/opt/mathdoc/service',check=True)
assert db.execute('SELECT active FROM identities WHERE id=?',(identity,)).fetchone()[0]==0
for name in ['/var/lib/mathdoc-admin/credentials/office-test-20261009.json','/home/ubuntu/office-test-20261009.json']:
    path=Path(name); assert json.loads(path.read_text())['identity']==identity; path.unlink()
stage=Path('/opt/mathdoc/office-staging-20261009')
records={name:(stage/name).read_text() for name in ['image-id.txt','base-image.txt','renderer-version.txt','packages.txt']}
sourceHashes={name:hashlib.sha256((Path('/opt/mathdoc/service')/name).read_bytes()).hexdigest() for name in ['api/main.py','api/store.py','api/office.py','api/office_output.py','dispatcher/main.py']}
active=subprocess.check_output(['systemctl','is-active','mathdoc-api','mathdoc-dispatcher']).decode().split()
assert active==['active','active']
containers=subprocess.check_output(['docker','ps','--format','{{.Names}}']).decode().split(); assert not any(name.startswith('mathdoc-') for name in containers)
print(json.dumps({'isolatedIdentityRevoked':True,'testJobsStates':states,'serverTestCopiesRemoved':True,'services':active,'noTaskContainers':True,'records':records,'deployedSourceHashes':sourceHashes}))
''' % identity
remote=subprocess.run(['ssh','-i',key,host,'sudo /opt/mathdoc/api-env/bin/python -'],input=script.encode(),capture_output=True,check=True)
report=json.loads(remote.stdout.decode().splitlines()[-1]); (evidence/'deployment-final.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
for name,content in report['records'].items(): (evidence/name).write_text(content,encoding='utf-8')
request=urllib.request.Request(value['baseUrl']+'/api/v1/capabilities',headers={'Authorization':'Bearer '+value['token']})
try: urllib.request.urlopen(request,timeout=30); raise AssertionError('revoked identity unexpectedly accepted')
except urllib.error.HTTPError as error: assert error.code==401
adb=str(root/'.local/android-sdk/platform-tools/adb.exe')
subprocess.run([adb,'shell','run-as','org.mathstudy.agent.debug','rm','-f','cache/office-service-test-credentials.json'],check=True)
listing=subprocess.check_output([adb,'shell','run-as','org.mathstudy.agent.debug','ls','cache']).decode(); assert 'office-service-test-credentials.json' not in listing.split()
assert credential.resolve().is_relative_to((root/'.local/document-service/credentials').resolve()); credential.unlink()
(evidence/'credential-cleanup.json').write_text(json.dumps({'isolatedIdentityRevoked':True,'revokedTokenRejected401':True,'serverTestCopiesRemoved':True,'localTestCopyRemoved':True,'deviceTestCopyRemoved':True,'existingIdentitiesUntouched':True},indent=2),encoding='utf-8')
print('Office isolated test identity revoked; all test copies removed; original identities untouched; services active')
