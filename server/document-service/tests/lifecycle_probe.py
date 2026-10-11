"""Tests real cancel, deletion, restart and cross-identity authorization."""
import hashlib
import json
import subprocess
import time
import uuid
from pathlib import Path

import httpx
from api.store import Store

store = Store(Path('/var/lib/mathdoc'))
owner, token = store.issue()
other, other_token = store.issue()
ready = store.root / 'worker-ready.json'
was_ready = ready.exists()
ready.write_text('{}')
headers = {'Authorization':'Bearer '+token}
checks = {}
with httpx.Client(base_url='http://127.0.0.1:8766',timeout=120) as client:
    source = Path('/tmp/math-scan.pdf').read_bytes()
    def submit(key):
        response = client.post('/api/v1/jobs',params={'extension':'pdf','start_page':1,'end_page':5},content=source,
            headers={**headers,'Idempotency-Key':key,'X-Source-Sha256':hashlib.sha256(source).hexdigest()})
        response.raise_for_status()
        return response.json()['taskId']
    def wait_state(job, wanted):
        for _ in range(60):
            state = client.get('/api/v1/jobs/'+job,headers=headers).json()['state']
            if state in wanted: return state
            time.sleep(.25)
        raise AssertionError('state_timeout')
    try:
        key=str(uuid.uuid4()); job=submit(key)
        checks['sameKeySameJob']=submit(key)==job
        forbidden={'Authorization':'Bearer '+other_token}
        checks['crossIdentityDenied']=all(client.get('/api/v1/jobs/'+job+suffix,headers=forbidden).status_code==404 for suffix in ('','/manifest','/assets/document'))
        wait_state(job,{'RUNNING'})
        assert client.post('/api/v1/jobs/'+job+'/cancel',headers=headers).json()['state']=='CANCELLED'
        time.sleep(3)
        checks['cancelStopsContainer']=subprocess.run(['docker','inspect','mathdoc-'+job],capture_output=True).returncode!=0
        checks['cancelCannotSucceed']=client.get('/api/v1/jobs/'+job,headers=headers).json()['state']=='CANCELLED'
        client.delete('/api/v1/jobs/'+job,headers=headers)
        checks['deleteActuallyClearsFiles']=wait_state(job,{'EXPIRED'})=='EXPIRED' and not (store.root/'jobs'/job).exists()
        job=submit(str(uuid.uuid4()))
        wait_state(job,{'RUNNING'})
        # Wait until the container exists, then interrupt the dispatcher.
        for _ in range(40):
            if subprocess.run(['docker','inspect','mathdoc-'+job],capture_output=True).returncode==0: break
            time.sleep(.1)
        subprocess.run(['systemctl','restart','mathdoc-dispatcher'],check=True)
        checks['restartExplicitFailure']=wait_state(job,{'FAILED'})=='FAILED'
        checks['restartStopsOldContainer']=subprocess.run(['docker','inspect','mathdoc-'+job],capture_output=True).returncode!=0
        client.delete('/api/v1/jobs/'+job,headers=headers)
        wait_state(job,{'EXPIRED'})
        checks['existingServicesActive']=subprocess.run(['systemctl','is-active','mathstudy-api','yubai-blog'],capture_output=True,text=True).stdout.splitlines()==['active','active']
        Path('/opt/mathdoc/lifecycle.json').write_text(json.dumps(checks,indent=2))
        print(json.dumps(checks))
        assert all(checks.values())
    finally:
        if not was_ready: ready.unlink(missing_ok=True)
        with store.db() as db:
            db.execute('UPDATE identities SET active=0 WHERE id IN (?,?)',(owner,other))
