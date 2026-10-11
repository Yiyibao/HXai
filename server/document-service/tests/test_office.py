import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile
import pytest
from fastapi.testclient import TestClient
from api.main import create_app
from api.office import OFFICE_OPTIONS
from api.office_output import validate_office_output
from office_worker.package import inspect_package


@pytest.fixture
def office(tmp_path):
    app = create_app(tmp_path); owner, token = app.state.store.issue(); _, other = app.state.store.issue()
    (tmp_path/'office-ready.json').write_text(json.dumps({'rendererVersion':'25.2-test'}))
    return TestClient(app), app.state.store, owner, token, other


def package(extra=None):
    output=io.BytesIO()
    with ZipFile(output,'w') as archive:
        for name,data in {'[Content_Types].xml':'<Types/>','word/document.xml':'<doc/>',**(extra or {})}.items():
            archive.writestr(name,data)
    return output.getvalue()


def submit(client, token, key='office_request_0001', extension='docx', payload=None):
    payload=package() if payload is None else payload
    return client.post('/api/v1/conversions',params={'extension':extension},content=payload,headers={
        'Authorization':'Bearer '+token,'Idempotency-Key':key,'X-Source-Sha256':hashlib.sha256(payload).hexdigest()})


def test_conversion_is_separate_ready_scoped_idempotent_and_cancellable(office):
    client,store,owner,token,other=office
    assert client.get('/api/v1/capabilities',headers={'Authorization':'Bearer '+token}).json()['officePdf']['ready']
    assert submit(client,'bad').status_code==401
    first=submit(client,token); assert first.status_code==202 and first.json()['operation']=='office_pdf'
    job=first.json()['taskId']; assert submit(client,token).json()['taskId']==job
    assert submit(client,token,payload=package({'word/extra.xml':'<other/>'})).status_code==409
    assert client.get('/api/v1/jobs/'+job,headers={'Authorization':'Bearer '+other}).status_code==404
    assert submit(client,other).json()['taskId']!=job
    store.claim(); store.cancel(owner,job); store.finish(job,'SUCCEEDED',{'assets':[]})
    assert store.get(owner,job)['state']=='CANCELLED'


def test_renderer_upgrade_returns_original_receipt_and_keeps_client_conflicts(office, monkeypatch):
    client, store, owner, token, other = office
    first = submit(client, token).json()
    assert first["rendererVersion"] == "25.2-test"
    job = first["taskId"]
    original = store.get(owner, job)
    (store.root / "office-ready.json").write_text(json.dumps({"rendererVersion": "26.0-test"}))
    recovered = submit(client, token)
    assert recovered.status_code == 202
    assert recovered.json()["taskId"] == job
    assert recovered.json()["rendererVersion"] == "25.2-test"
    persisted = store.get(owner, job)
    assert persisted["options"] == original["options"]
    assert persisted["fingerprint"] == original["fingerprint"]
    assert submit(client, token, extension="xlsx").status_code == 409
    assert submit(client, token, payload=package({"word/extra.xml": "<changed/>"})).status_code == 409
    new = submit(client, other).json()
    assert new["taskId"] != job and new["rendererVersion"] == "26.0-test"
    monkeypatch.setitem(OFFICE_OPTIONS, "includeNotes", True)
    assert submit(client, token).status_code == 409


def test_conversion_unready_and_format_are_not_fake_success(office):
    client,store,_,token,_=office
    assert submit(client,token,extension='docm').status_code==422
    assert submit(client,token,payload=b'%PDF-fake').status_code==422
    (store.root/'office-ready.json').unlink()
    assert submit(client,token).status_code==503


def test_cancel_lost_ack_and_cancel_before_enqueue_do_not_require_reupload(office):
    client,store,owner,token,_=office
    headers={'Authorization':'Bearer '+token}
    key='cancel_before_upload_001'
    assert client.post(f'/api/v1/conversion-requests/{key}/cancel',headers=headers).json()['state']=='CANCELLED'
    job=submit(client,token,key=key).json()['taskId']
    assert store.get(owner,job)['state']=='CANCELLED' and not (store.root/'jobs'/job).exists()
    key='cancel_lost_ack_0001'; job=submit(client,token,key=key).json()['taskId']
    assert client.post(f'/api/v1/conversion-requests/{key}/cancel',headers=headers).json()['taskId']==job
    assert submit(client,token,key=key).json()['state']=='CANCELLED'


@pytest.mark.parametrize('extra', [
    {'../outside.xml':'<x/>'}, {'word/vbaProject.bin':b'bad'}, {'word/embeddings/ole.bin':b'bad'},
    {'_xmlsignatures/sig.xml':'<sig/>'}, {'word/external.xml':'<!DOCTYPE x [<!ENTITY y SYSTEM "file:///etc/passwd">]><x/>'},
    {'word/_rels/document.xml.rels':'<Relationships><Relationship Type="image" TargetMode="External" Target="https://outside"/></Relationships>'},
])
def test_active_expanding_and_external_packages_rejected(tmp_path,extra):
    file=tmp_path/'input.docx'; file.write_bytes(package(extra))
    with pytest.raises(ValueError): inspect_package(file,'docx',hashlib.sha256(file.read_bytes()).hexdigest())


def test_package_accepts_real_rich_office_samples_and_checks_digest():
    samples=Path(__file__).resolve().parents[3]/'qa/p0-completion-20261009/samples'
    for extension in ('docx','xlsx','pptx'):
        source=samples/('rich-source.'+extension)
        assert inspect_package(source,extension,hashlib.sha256(source.read_bytes()).hexdigest())
        with pytest.raises(ValueError): inspect_package(source,extension,'0'*64)


def output(tmp_path):
    directory=tmp_path/'output'; directory.mkdir(); pdf=directory/'output.pdf'; pdf.write_bytes(b'%PDF-controlled-publication-fixture')
    options={'sourceSha256':'a'*64,'extension':'docx','rendererVersion':'25.2-test','renderOptions':OFFICE_OPTIONS}
    manifest={'contractVersion':1,'operation':'office_pdf','sourceSha256':'a'*64,'sourceExtension':'docx','renderer':'LibreOffice','rendererVersion':'25.2-test','renderOptions':OFFICE_OPTIONS,'pageCount':1,'warnings':[],
              'assets':[{'assetId':'pdf','path':'output.pdf','mediaType':'application/pdf','size':pdf.stat().st_size,'sha256':hashlib.sha256(pdf.read_bytes()).hexdigest()}]}
    (directory/'manifest.json').write_text(json.dumps(manifest)); return options,manifest


@pytest.mark.parametrize('mutation',['path','digest','pages','renderer','source','options'])
def test_conversion_publication_rejects_wrong_binding_and_paths(tmp_path,mutation):
    options,manifest=output(tmp_path)
    if mutation=='path': manifest['assets'][0]['path']='../elsewhere'
    elif mutation=='digest': manifest['assets'][0]['sha256']='b'*64
    elif mutation=='pages': manifest['pageCount']=201
    elif mutation=='renderer': manifest['rendererVersion']='other'
    elif mutation=='source': manifest['sourceSha256']='b'*64
    else: manifest['renderOptions']={'scope':'unknown'}
    (tmp_path/'output/manifest.json').write_text(json.dumps(manifest))
    with pytest.raises(ValueError): validate_office_output(tmp_path,options)
    assert not (tmp_path/'published').exists()


def test_conversion_publication_atomic_fixed_file(tmp_path):
    options,manifest=output(tmp_path)
    assert validate_office_output(tmp_path,options)==manifest
    assert (tmp_path/'published/output.pdf').is_file() and not (tmp_path/'publishing').exists()
