import base64
import copy
import time
import pytest
from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from hxai_agent.authority import NetworkAuthority, NetworkStore, raw_sha
from hxai_agent.cloud import CloudHost
from hxai_agent.store import Conflict, UncertainEffect, AuthorizationRequired, canonical, digest
from hxai_agent.vault import TaskVault

MODEL=dict(provider_id='accepted-provider',model_id='accepted-model',reasoning='HIGH',configuration_revision='frozen-config')


def signed(key,value):
    return dict(value,signature=base64.b64encode(key.sign(canonical(value).encode(),padding.PKCS1v15(),hashes.SHA256())).decode())


@pytest.fixture
def host(tmp_path):
    authority=NetworkAuthority(NetworkStore(tmp_path/'authority.sqlite3'))
    token=authority.enroll('phone',['owner'])
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    authority.pair(token,dict(publicKey=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()))
    path=tmp_path/'vault.key';TaskVault.initialize(path)
    return CloudHost(authority,TaskVault(path),tmp_path/'files'),token,key


def request(source='source'):
    return dict(owner='owner',sourceId=source,goal='Create a verified version on the paired phone',model=MODEL,
        configuration=dict(protocol='openai_chat',endpoint='https://synthetic.invalid/v1/chat/completions',wireModel='wire-original',
            baseBody={'reasoning_effort':'high'},headers={},secret='SYNTHETIC-ORIGINAL-SECRET'),
        tools=[dict(name='create_material_file',description='Trusted native version writer',parameters={'type':'object','properties':{}})],
        requireEvidence=True,sources=[],grant=dict(expiresAt=time.time()+3600,dataScope='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER'))


def worker(host, instance='worker-instance'):
    cloud,phone,key=host
    with cloud.store.transaction() as db:
        task=db.execute("SELECT id FROM tasks WHERE state='QUEUED' ORDER BY sequence LIMIT 1").fetchone()
    # A blocked queue has no worker job; still bind this fixture credential to ONE task.
    if task is None:
        with cloud.store.transaction() as db:task=db.execute('SELECT id FROM tasks ORDER BY sequence LIMIT 1').fetchone()
    actor=cloud.authority.worker_actor(task[0],instance)
    token=cloud.authority.enroll(actor,['owner'],role='worker')
    # Unit boundary fixture; actual OS exit/registration is exercised by the process smoke.
    with cloud.store.transaction() as db:
        db.execute("INSERT INTO net_processes(instance,actor,pid,birth,state) VALUES(?,?,1,'unit','RUNNING')",(instance,actor))
    selected=cloud.next(token,{'instance':instance})['job']
    return token,None if selected is None else {k:selected[k] for k in ('owner','taskId','fence','instance')}


def waiting_device(host):
    cloud,phone,key=host
    task=cloud.create(phone,'create',request())['taskId']
    token,lease=worker(host)
    cloud.publish_plan(token,dict(lease,goalRevision=1,plan={'steps':[dict(id='native-result',tool='create_material_file',purpose='Actual native version')],
        'acceptance':[dict(stepId='native-result',kind='artifact_content',format='docx',contains=['actual'])],'explanation':'Paired native execution'}))
    assert cloud.tool(token,dict(lease,callId='native-result',tool='create_material_file',arguments={'content':'actual'}))['state']=='WAITING_DEVICE'
    cloud.authority.retire_process(cloud.authority.worker_actor(task,'worker-instance'),'worker-instance',exit_code=0)
    return task,lease


def confirmed_claim(host,task,instance='phone-instance'):
    cloud,phone,key=host
    pending=cloud.device_pending(phone,dict(owner='owner',taskId=task))['request']
    cloud.device_confirm(phone,'confirm-'+instance,signed(key,dict(owner='owner',taskId=task,callId=pending['callId'],revision=pending['taskRevision'],
        inputHash=pending['inputHash'],grantEpoch=pending['grantEpoch'],foregroundNonce='foreground-'+instance)))
    return cloud.device_claim(phone,dict(owner='owner',taskId=task,callId=pending['callId'],instance=instance))


def stop_proof(host,task,lease,**changes):
    cloud,phone,key=host
    proof=dict(sourceId='source',sourceFence=3,state='INTERRUPTED',journalHash='a'*64,
        nativeCallId='cloud_'+raw_sha(canonical(task)+canonical('native-result'))[:40],callState='NOT_DISPATCHED')
    proof.update(changes)
    return signed(key,dict(owner='owner',taskId=task,callId='native-result',instance=lease['instance'],fence=lease['fence'],proof=proof))


def test_vault_cipher_cannot_move_between_owner_task_model_or_key_revision(host):
    cloud,_,_=host
    binding=dict(owner='owner',taskId='task',model=MODEL,credentialRevision=1)
    cipher=cloud.vault.seal(binding,request()['configuration'])
    assert cloud.vault.open(binding,cipher)['secret']=='SYNTHETIC-ORIGINAL-SECRET'
    for field,value in [('owner','foreign'),('taskId','other'),('model',dict(MODEL,reasoning='LOW')),('credentialRevision',2)]:
        with pytest.raises(InvalidTag):cloud.vault.open(dict(binding,**{field:value}),cipher)


def test_cloud_create_lost_ack_survives_expired_grant_without_new_task(host):
    cloud,phone,_=host
    value=request();first=cloud.create(phone,'once',value)
    expired=copy.deepcopy(value);expired['grant']['expiresAt']=0
    # Time moves past the original accepted deadline, not a modified retry intent.
    import unittest.mock
    with unittest.mock.patch('hxai_agent.cloud.time.time',return_value=value['grant']['expiresAt']+1):
        assert cloud.create(phone,'once',value)==first
    with pytest.raises(Conflict):cloud.create(phone,'once',expired)
    with cloud.store.transaction() as db:assert db.execute('SELECT count(*) FROM tasks').fetchone()[0]==1


def test_handoff_preserves_manual_input_and_device_blockers(host):
    cloud,phone,_=host
    value=request();value['initialControl']=dict(manualPause=True,inputBlocked=True,deviceBlocked=True,deviceConfirmation=True)
    task=cloud.create(phone,'with-controls',value)['taskId']
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    assert state['state']=='PAUSED_BY_USER' and state['manualPaused'] and state['inputBlocked'] and state['deviceConfirmation']
    resumed=cloud.control(phone,'explicit-resume',dict(owner='owner',taskId=task,revision=state['revision'],action='resume',payload=''))
    assert resumed['state']=='WAITING_INPUT'
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    assert not state['manualPaused'] and state['inputBlocked'] and state['deviceBlocked'] and state['deviceConfirmation']
    token,selected=worker(host);assert selected is None


def test_signed_native_history_is_retained_not_reexecuted(host):
    cloud,phone,key=host;value=request()
    result=dict(tool='create_material_file',inputHash=digest({'content':'previous'}),nativeTaskId='source',nativeFence=3,output=[],
        artifacts=[dict(id='native-version',format='docx',content='previous actual',sha256='b'*64)],verifier='ORIGINAL_NATIVE_VERIFIER')
    value['nativeHistory']=signed(key,dict(owner='owner',sourceId='source',journalHash='c'*64,
        steps=[dict(id='native_prior',tool='create_material_file',arguments={'content':'previous'},result=result)]))
    task=cloud.create(phone,'handoff',value)['taskId'];token,lease=worker(host)
    invalid=dict(steps=[],acceptance=[],explanation='Pretend previous work did not happen')
    with pytest.raises(Conflict):cloud.publish_plan(token,dict(lease,goalRevision=1,plan=invalid))
    plan=dict(steps=[dict(id='native_prior',tool='create_material_file',purpose='Retain original verified version')],
        acceptance=[dict(stepId='native_prior',kind='artifact_content',format='docx',contains=['previous actual'])],explanation='Read original result')
    cloud.publish_plan(token,dict(lease,goalRevision=1,plan=plan))
    evidence=cloud.evidence(token,lease)
    assert evidence['evidence'][0]['result']==result
    with cloud.store.transaction() as db:assert db.execute('SELECT count(*) FROM calls WHERE task_id=?',(task,)).fetchone()[0]==1


def test_foreign_native_history_signature_cannot_authorize_a_checkpoint(host):
    cloud,phone,key=host;value=request()
    foreign=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    value['nativeHistory']=signed(foreign,dict(owner='owner',sourceId='source',journalHash='a'*64,steps=[]))
    with pytest.raises(InvalidSignature):cloud.create(phone,'foreign-checkpoint',value)
    with cloud.store.transaction() as db:assert db.execute('SELECT count(*) FROM tasks').fetchone()[0]==0


def test_device_confirmation_cannot_clear_manual_pause_or_old_revision(host):
    cloud,phone,key=host;task,_=waiting_device(host)
    pending=cloud.device_pending(phone,dict(owner='owner',taskId=task))['request']
    current=cloud.store.get('owner',task)
    cloud.control(phone,'pause',dict(owner='owner',taskId=task,revision=current['revision'],action='pause',payload=''))
    proof=dict(owner='owner',taskId=task,callId=pending['callId'],revision=pending['taskRevision'],inputHash=pending['inputHash'],
        grantEpoch=pending['grantEpoch'],foregroundNonce='stale')
    with pytest.raises(Conflict):cloud.device_confirm(phone,'stale',signed(key,proof))
    proof['revision']=cloud.store.get('owner',task)['revision']
    with pytest.raises(Conflict):cloud.device_confirm(phone,'paused',signed(key,proof))
    assert cloud.store.get('owner',task)['manual_pause']==1


def test_claim_ack_is_not_a_second_device_action_permission(host):
    cloud,phone,key=host;task,_=waiting_device(host)
    first=confirmed_claim(host,task)
    assert first['dispatch'] is True
    repeat=cloud.device_claim(phone,dict(owner='owner',taskId=task,callId='native-result',instance=first['instance']))
    assert repeat['dispatch'] is False and repeat['fence']==first['fence']
    with pytest.raises(Conflict):cloud.device_recover(phone,dict(owner='owner',taskId=task,callId='native-result',instance='another-process'))


@pytest.mark.parametrize('changes',[{'callState':'UNKNOWN'},{'state':'UNCERTAIN_SIDE_EFFECT'},{'sourceFence':True},{'nativeCallId':'foreign-call'}])
def test_unknown_or_wrong_native_stop_proof_retains_global_slot(host,changes):
    cloud,phone,key=host;task,_=waiting_device(host);lease=confirmed_claim(host,task)
    with pytest.raises(UncertainEffect):cloud.device_abort(phone,stop_proof(host,task,lease,**changes))
    with cloud.store.transaction() as db:
        assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0]==task
        assert db.execute('SELECT state FROM calls WHERE task_id=?',(task,)).fetchone()[0]=='IN_FLIGHT'


def test_proven_not_dispatched_releases_only_after_stop_and_needs_fresh_foreground(host):
    cloud,phone,key=host;task,_=waiting_device(host);first=confirmed_claim(host,task)
    proof=stop_proof(host,task,first)
    assert cloud.device_abort(phone,proof)['recorded']
    assert cloud.device_abort(phone,proof)['recorded']
    with cloud.store.transaction() as db:
        assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0] is None
        assert db.execute('SELECT state FROM calls WHERE task_id=?',(task,)).fetchone()[0]=='FAILED_SAFE'
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    assert state['deviceConfirmation'] and state['deviceBlocked']
    second=confirmed_claim(host,task,'new-phone-instance')
    assert second['dispatch'] and second['fence']>first['fence']
    assert cloud.device_abort(phone,proof)['recorded']  # old ACK cannot settle this new dispatch
    with cloud.store.transaction() as db:assert db.execute('SELECT state FROM calls WHERE task_id=?',(task,)).fetchone()[0]=='IN_FLIGHT'


def test_revoked_device_can_settle_but_not_resume_or_claim(host):
    cloud,phone,key=host;task,_=waiting_device(host);lease=confirmed_claim(host,task)
    cloud.authority.revoke_actor('phone')
    with pytest.raises(AuthorizationRequired):cloud.authority.check(phone,dict(owner='owner',taskId=task,fence=lease['fence'],instance=lease['instance']))
    assert cloud.device_abort(phone,stop_proof(host,task,lease))['recorded']
    with cloud.store.transaction() as db:
        row=db.execute('SELECT authorization,state FROM tasks WHERE id=?',(task,)).fetchone()
        assert row['authorization']=='REVOKED' and row['state']=='WAITING_AUTHORIZATION'
        assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0] is None


@pytest.mark.parametrize('confirmed',[False,True])
@pytest.mark.parametrize('manual,input_blocked',[(False,False),(True,False),(False,True)])
def test_verified_key_refresh_retains_blockers_and_allows_fresh_device_confirmation(host,confirmed,manual,input_blocked):
    cloud,phone,key=host;task,_=waiting_device(host)
    before=cloud.device_pending(phone,dict(owner='owner',taskId=task))['request']
    if confirmed:
        cloud.device_confirm(phone,'old-confirmation',signed(key,dict(owner='owner',taskId=task,callId=before['callId'],
            revision=before['taskRevision'],inputHash=before['inputHash'],grantEpoch=before['grantEpoch'],foregroundNonce='before-expiry')))
    if manual:cloud.store.pause('owner',task)
    if input_blocked:
        with cloud.store.transaction() as db:
            db.execute("UPDATE net_jobs SET input_blocked=1 WHERE task_id=?",(task,))
            db.execute("UPDATE tasks SET blocked_on='WAITING_INPUT' WHERE id=?",(task,))
    cloud.store.change_authorization('owner',task,'EXPIRED')
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    cloud.replace_credential(phone,'replacement',dict(owner='owner',taskId=task,revision=state['revision'],secret='SYNTHETIC-NEW-SECRET'))
    token,lease=worker(host,'validation-instance')
    assert lease is not None
    from hxai_agent.provider import encode_request
    saved=cloud.configuration(token,lease)
    config=cloud.vault.open(saved['binding'],saved['ciphertext'])
    body=encode_request(config,[dict(role='user',content='Validate original locked model')],[])
    cloud.provider_prepare(token,'validate-once',dict(lease,role='credential_validation',body=body))
    cloud.provider_finish(token,dict(lease,requestId='validate-once',result=dict(text='OK',toolCalls=[],usage=None),rejectedStatus=None))
    cloud.apply_validation(token,lease)
    cloud.authority.retire_process(cloud.authority.worker_actor(lease['taskId'],lease['instance']),lease['instance'],exit_code=0)
    current=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    pending=cloud.device_pending(phone,dict(owner='owner',taskId=task))['request']
    assert current['authorization']=='ACTIVE' and current['model']==MODEL
    assert current['manualPaused']==manual and current['inputBlocked']==input_blocked
    assert current['deviceBlocked'] and current['deviceConfirmation']
    assert pending['state']=='WAITING' and pending['inputHash']==before['inputHash']
    assert pending['grantEpoch']==current['grantEpoch']>before['grantEpoch']
    proof=dict(owner='owner',taskId=task,callId=pending['callId'],revision=current['revision'],inputHash=pending['inputHash'],
        grantEpoch=pending['grantEpoch'],foregroundNonce='after-verified-refresh')
    if manual or input_blocked:
        with pytest.raises(Conflict):cloud.device_confirm(phone,'must-still-wait',signed(key,proof))
    else:
        assert cloud.device_confirm(phone,'fresh-confirmation',signed(key,proof))['state']=='QUEUED'
        assert cloud.device_claim(phone,dict(owner='owner',taskId=task,callId=pending['callId'],instance='new-phone'))['dispatch']
