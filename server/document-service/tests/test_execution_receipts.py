import hashlib
import json
import uuid
from fastapi.testclient import TestClient
from api.main import create_app


def test_cancel_ack_is_not_worker_stop_and_request_lookup_preserves_owner(tmp_path):
    app=create_app(tmp_path);store=app.state.store;owner,token=store.issue();_,foreign=store.issue()
    client=TestClient(app);headers={'Authorization':'Bearer '+token}
    source=tmp_path/'uploads'/str(uuid.uuid4());source.write_bytes(b'%PDF-1.4\nsynthetic')
    options=dict(sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),extension='pdf',pages=[1,1],tier='basic',parserVersion='4.0.10',adapterVersion='1')
    key='synthetic-original-request-key';row=store.enqueue(owner,key,'fingerprint',options,source)
    assert client.get('/api/v1/jobs/'+row['id'],headers=headers).json().get('executorStopped') is True
    store.claim()
    cancelled=client.post('/api/v1/jobs/'+row['id']+'/cancel',headers=headers).json()
    assert cancelled['state']=='CANCELLED' and cancelled['executorStopped'] is False
    lookup=client.get('/api/v1/requests/'+key,headers=headers).json()['job']
    assert lookup['taskId']==row['id'] and lookup['intent']==options and not lookup['executorStopped']
    assert client.get('/api/v1/requests/'+key,headers={'Authorization':'Bearer '+foreign}).json()=={'job':None}
    store.record_worker_stopped(row['id'])  # Trusted dispatcher boundary after actual wait/absence proof, never an RPC parameter.
    stopped=client.get('/api/v1/jobs/'+row['id'],headers=headers).json()
    assert stopped['executorStopped'] is True and stopped['state']=='CANCELLED'


def test_existing_terminal_rows_are_not_reinterpreted_as_stop_proofs(tmp_path):
    app=create_app(tmp_path);store=app.state.store;owner,token=store.issue()
    with store.db() as db:
        db.execute("INSERT INTO jobs(id,owner,request_key,fingerprint,options,state,created,updated,expires) VALUES(?,?,?,?,?,'CANCELLED',1,1,9999999999)",
            ('legacy',owner,'legacy-key','old','{}'))
    restarted=create_app(tmp_path)
    value=TestClient(restarted).get('/api/v1/jobs/legacy',headers={'Authorization':'Bearer '+token}).json()
    assert value['executorStopped'] is False
