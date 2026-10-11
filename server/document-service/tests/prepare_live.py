import json
import os
import pwd
from pathlib import Path
from api.store import Store

store=Store(Path('/var/lib/mathdoc'))
owner,token=store.issue()
root=Path('/tmp/mathdoc-private')
root.mkdir(mode=0o700,exist_ok=True)
user=pwd.getpwnam('ubuntu')
os.chown(root,user.pw_uid,user.pw_gid)
file=root/'live-test.json'
fd=os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as stream:
    json.dump({'identity':owner,'baseUrl':'https://hxnf.top/documents','token':token},stream)
os.chown(file,user.pw_uid,user.pw_gid)
(store.root/'worker-ready.json').write_text(json.dumps({'phase':'live-validation'}))
print('synthetic_test_credential_prepared')
