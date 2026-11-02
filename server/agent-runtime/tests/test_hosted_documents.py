import base64
import hashlib
import json
import sqlite3
import pytest
from cryptography.hazmat.primitives import serialization

from hxai_agent.authority import NetworkStore
from hxai_agent.store import AuthorizationRequired,Conflict,TaskStore
from hxai_agent.documents import CHUNK
from test_cloud_device import host,request,worker


def document_request(source):
    value=request();value['sources']=[source]
    value['documentService']=dict(endpoint='https://synthetic.invalid/documents',secret='SYNTHETIC_DOCUMENT_SERVICE_CREDENTIAL',consent=True)
    return value


def test_source_chunk_lost_ack_conflict_and_foreign_actor_cannot_rebind_version(host):
    cloud,phone,key=host
    data=b'%PDF-1.4\n'+b'a'*(CHUNK*2)
    sha=hashlib.sha256(data).hexdigest()
    def chunk(offset,payload=None):
        return dict(owner='owner',sourceId='source-version',sha256=sha,extension='pdf',size=len(data),offset=offset,
            bytes=base64.b64encode(payload if payload is not None else data[offset:offset+CHUNK]).decode(),consent=True)
    first=cloud.documents.upload(phone,'first',chunk(0))
    assert first==cloud.documents.upload(phone,'first',chunk(0))
    # New transport command after a missing receipt must still preserve exact bytes.
    assert cloud.documents.upload(phone,'readback-first',chunk(0))==first
    with pytest.raises(Conflict):cloud.documents.upload(phone,'conflicting-prefix',chunk(0,b'b'*CHUNK))
    for offset in range(CHUNK,len(data),CHUNK):last=cloud.documents.upload(phone,'part-'+str(offset),chunk(offset))
    assert last['complete']
    source=dict(id='source-version',name='Selected immutable source.pdf',sha256=sha,extension='pdf',size=len(data),uploadId=last['uploadId'])
    task=cloud.create(phone,'original',document_request(source))['taskId']
    token,lease=worker(host)
    saved=cloud.configuration(token,lease)['snapshot'];record=saved['sources'][0]
    path=cloud.files.directory('owner','materials')/(record['storageId']+'.pdf')
    assert path.read_bytes()==data
    assert 'SYNTHETIC_DOCUMENT_SERVICE_CREDENTIAL' not in json.dumps(saved)
    foreign=cloud.authority.enroll('foreign-device',['owner'])
    cloud.authority.pair(foreign,dict(publicKey=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()))
    with pytest.raises(AuthorizationRequired):cloud.create(foreign,'foreign',document_request(source))
    assert path.read_bytes()==data


def test_source_without_separate_document_consent_cannot_enable_service_tools(host):
    cloud,phone,_=host
    value=request();value['documentService']=dict(endpoint='https://synthetic.invalid',secret='SYNTHETIC_DOCUMENT_SERVICE_CREDENTIAL',consent=False)
    with pytest.raises(ValueError):cloud.create(phone,'missing-document-consent',value)
    task=cloud.create(phone,'text-only',request())['taskId'];token,lease=worker(host)
    names={t['name'] for t in cloud.configuration(token,lease)['tools']}
    assert not {'parse_document','render_office_pdf','recalculate_xlsx'} & names
    assert not any(c.startswith('documents.') for c in json.loads(cloud.store.get('owner',task)['capabilities_json']))


def test_network_schema3_upgrade_preserves_unknown_slot_model_and_ciphertext(host):
    cloud,phone,_=host;task=cloud.create(phone,'original',request())['taskId'];token,lease=worker(host)
    from hxai_agent.store import Lease,ModelLock
    registered=cloud.tools['write_report_version']
    cloud.publish_plan(token,dict(lease,goalRevision=1,plan=dict(
        steps=[dict(id='uncertain',tool='write_report_version',purpose='Known interrupted version write')],
        acceptance=[dict(stepId='uncertain',kind='artifact_content',format='md',contains=['Synthetic'])],explanation='Durable plan before dispatch')))
    permit=cloud.store.prepare_call(Lease(task,'owner',lease['fence']),'uncertain','write_report_version',
        dict(family='unknown-version',content='Synthetic interrupted output',parent_artifact_id=None),registered.capability,registered.effect,ModelLock(**request()['model']))
    cloud.store.dispatch(permit);cloud.store.fail_call(permit)
    before=cloud.store.get('owner',task);call=cloud.store.get_call('owner',task,'uncertain')
    with cloud.store.transaction() as db:
        cipher=db.execute('SELECT config_cipher FROM net_jobs WHERE task_id=?',(task,)).fetchone()[0]
        db.execute('DROP TABLE net_document_calls');db.execute('PRAGMA user_version=3')
    migrated=NetworkStore(cloud.store.path)
    assert migrated.get('owner',task)==before and migrated.get_call('owner',task,'uncertain')==call
    with migrated.transaction() as db:
        assert db.execute('SELECT config_cipher FROM net_jobs WHERE task_id=?',(task,)).fetchone()[0]==cipher
        assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0]==task
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        db.execute('PRAGMA user_version=5')
    with pytest.raises(RuntimeError,match='unsupported_schema'):NetworkStore(cloud.store.path)
    with sqlite3.connect(cloud.store.path) as db:assert db.execute('PRAGMA user_version').fetchone()[0]==5
    with pytest.raises(RuntimeError):TaskStore(cloud.store.path)


def test_old_task_source_id_does_not_grant_a_new_task_permission_to_read_it(host):
    cloud,phone,_=host;first=request('first-source-task')
    text='SYNTHETIC_PRIVATE_ONLY_FIRST_TASK'
    first['sources']=[dict(id='first-material',text=text,sha256=hashlib.sha256(text.encode()).hexdigest())]
    task=cloud.create(phone,'first-task',first)['taskId'];token,lease=worker(host,'first-worker')
    source=cloud.configuration(token,lease)['snapshot']['sources'][0]['id']
    plan=dict(steps=[dict(id='read-source',tool='read_material_text',purpose='Authorized original source')],
        acceptance=[dict(stepId='read-source',kind='tool_verified')],explanation='Read only the explicitly selected version')
    cloud.publish_plan(token,dict(lease,goalRevision=1,plan=plan))
    cloud.tool(token,dict(lease,callId='read-source',tool='read_material_text',arguments=dict(material_id=source)))
    evidence=cloud.evidence(token,lease)
    cloud.complete(token,dict(lease,answer='Original selected source verified',semantic=dict(accepted=True,goalRevision=1,evidenceHash=evidence['evidenceHash'])))
    cloud.authority.retire_process(cloud.authority.worker_actor(task,lease['instance']),lease['instance'],exit_code=0)
    second=cloud.create(phone,'second-task',request('second-source-task'))['taskId']
    token,lease=worker(host,'second-worker')
    cloud.publish_plan(token,dict(lease,goalRevision=1,plan=plan))
    with pytest.raises(AuthorizationRequired):
        cloud.tool(token,dict(lease,callId='read-source',tool='read_material_text',arguments=dict(material_id=source)))
    with cloud.store.transaction() as db:
        assert db.execute('SELECT count(*) FROM calls WHERE task_id=?',(second,)).fetchone()[0]==0


@pytest.mark.parametrize('blank',[False,True])
def test_missing_text_is_a_blank_page_proof_only_when_original_coverage_says_blank(blank):
    from hxai_agent.documents import parsed_content
    document=dict(blocks=[],sourceSha256='a'*64,processedPages=[3],blankPages=[3] if blank else [],
        failedPages=[],parserVersion='4.0.10',resultRevision=1)
    receipt=json.loads(parsed_content(document))
    assert (receipt['kind']=='VERIFIED_BLANK_PAGE_RECEIPT') is blank
    assert receipt['physicalPages']==[3] and receipt['blankPages']==([3] if blank else [])
