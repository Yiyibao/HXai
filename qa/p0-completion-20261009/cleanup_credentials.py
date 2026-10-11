"""Revoke only the explicitly issued P0 identity; never print the bearer token."""
from pathlib import Path
import json,subprocess
root=Path(__file__).resolve().parents[2]
credential=root/'.local/document-service/credentials/p0-test-20261009.json'
value=json.loads(credential.read_text()); identity=value['identity']
key=str(Path.home()/'.ssh/id_ed25519_tencent_blog')
code='''import json,subprocess,sqlite3
identity=%r
subprocess.run(['/opt/mathdoc/api-env/bin/python','-m','api.admin','revoke','--identity',identity],cwd='/opt/mathdoc/service',check=True)
db=sqlite3.connect('/var/lib/mathdoc/tasks.sqlite3')
assert db.execute('SELECT active FROM identities WHERE id=?',(identity,)).fetchone()[0]==0
from pathlib import Path
for name in ['/var/lib/mathdoc-admin/credentials/p0-test-20261009.json','/home/ubuntu/p0-test-20261009.json']:
    p=Path(name)
    assert json.loads(p.read_text())['identity']==identity
    p.unlink()
print('isolated_identity_revoked_and_test_copies_removed')
''' % identity
subprocess.run(['ssh','-i',key,'ubuntu@154.222.25.150','sudo /opt/mathdoc/api-env/bin/python -'],input=code,text=True,check=True)
adb=str(root/'.local/android-sdk/platform-tools/adb.exe')
subprocess.run([adb,'shell','run-as','org.mathstudy.agent.debug','rm','-f','cache/document-service-test-credentials.json'],check=True)
assert not subprocess.run([adb,'shell','run-as','org.mathstudy.agent.debug','test','-e','cache/document-service-test-credentials.json']).returncode==0
assert credential.resolve().is_relative_to((root/'.local/document-service/credentials').resolve())
credential.unlink()
(Path(__file__).parent/'credential-cleanup.json').write_text(json.dumps({'isolated_identity_revoked':True,'server_test_copies_removed':True,'local_test_copy_removed':True,'device_test_copy_removed':True,'existing_identities_untouched':True},indent=2))
print('Temporary test credentials revoked and removed; existing identities unchanged')
