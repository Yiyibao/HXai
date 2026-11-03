import hashlib
import io
import json
from zipfile import ZipFile

import pytest
from fastapi.testclient import TestClient
from api.main import create_app


def source():
    stream=io.BytesIO()
    with ZipFile(stream,'w') as z:
        z.writestr('[Content_Types].xml','<Types/>')
        z.writestr('word/document.xml','<doc/>')
    return stream.getvalue()


@pytest.fixture
def fixture(tmp_path):
    app=create_app(tmp_path)
    owner,token=app.state.store.issue()
    _,other=app.state.store.issue()
    (tmp_path/'office-ready.json').write_text(json.dumps({'rendererVersion':'old-engine'}))
    return TestClient(app),app.state.store,owner,token,other,tmp_path


def test_original_request_readback_is_owner_bound_and_never_mutates(fixture):
    client,store,owner,token,other,root=fixture
    key='readback_request_0001';data=source();digest=hashlib.sha256(data).hexdigest()
    headers={'Authorization':'Bearer '+token}
    submitted=client.post('/api/v1/conversions?extension=docx',content=data,
        headers={**headers,'Idempotency-Key':key,'X-Source-Sha256':digest})
    assert submitted.status_code==202
    task=submitted.json()['taskId'];before=store.get(owner,task)
    (root/'office-ready.json').unlink()
    response=client.get('/api/v1/conversion-requests/'+key,headers=headers)
    assert response.status_code==200
    body=response.json()
    assert {k:body[k] for k in ('taskId','requestKey','operation','sourceSha256','sourceExtension','rendererVersion')}=={
        'taskId':task,'requestKey':key,'operation':'office_pdf','sourceSha256':digest,
        'sourceExtension':'docx','rendererVersion':'old-engine'}
    assert store.get(owner,task)==before
    with store.db() as db:
        assert db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==1
        assert db.execute('SELECT COUNT(*) FROM request_cancellations').fetchone()[0]==0
    assert client.get('/api/v1/conversion-requests/'+key,headers={'Authorization':'Bearer '+other}).status_code==404
    assert client.get('/api/v1/recalculation-requests/'+key,headers=headers).status_code==404
    assert client.get('/api/v1/conversion-requests/unknown_request_0001',headers=headers).status_code==404
    assert client.get('/api/v1/conversion-requests/bad',headers=headers).status_code==422
    store.revoke(owner)
    assert client.get('/api/v1/conversion-requests/'+key,headers=headers).status_code==401


def test_revocation_between_identity_and_readback_is_fenced(fixture,monkeypatch):
    client,store,owner,token,_,_=fixture
    original=store.authenticate
    def authenticate_then_revoke(value):
        result=original(value)
        store.revoke(owner)
        return result
    monkeypatch.setattr(store,'authenticate',authenticate_then_revoke)
    assert client.get('/api/v1/conversion-requests/unknown_request_0001',
        headers={'Authorization':'Bearer '+token}).status_code==401
