import json
import time
from unittest.mock import patch
import pytest

from hxai_agent.store import Conflict, AuthorizationRequired
from hxai_agent.provider import encode_request
from test_cloud_device import host, request, worker


def persistent():
    value=request()
    value['grant']=dict(mode='UNTIL_REVOKED',expiresAt=None,dataScope=value['grant']['dataScope'])
    return value


def test_continuous_grant_survives_time_without_changing_scope_or_locked_model(host):
    cloud,phone,_=host
    value=persistent();task=cloud.create(phone,'continuous',value)['taskId']
    with patch('time.time',return_value=time.time()+86400*2):
        token,lease=worker(host)
        saved=cloud.configuration(token,lease)
    assert saved['snapshot']['grant']==value['grant']
    assert saved['binding']['model']==value['model']
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    assert state['grantMode']=='UNTIL_REVOKED' and state['grantExpiresAt'] is None


@pytest.mark.parametrize('grant',[
    dict(mode='UNTIL_REVOKED',expiresAt=1,dataScope='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER'),
    dict(mode='UNTIL',expiresAt=None,dataScope='THIS_GOAL_SELECTED_SOURCES_AND_PINNED_PROVIDER'),
    dict(mode='UNTIL_REVOKED',expiresAt=None,dataScope='EVERY_FILE'),
])
def test_continuous_mode_never_implicitly_expands_scope_or_weakens_legacy_expiry(host,grant):
    cloud,phone,_=host;value=request();value['grant']=grant
    with pytest.raises(ValueError):cloud.create(phone,'invalid',value)


def test_duration_update_keeps_all_independent_blockers_and_revoke_is_sticky(host):
    cloud,phone,_=host;value=request()
    value['initialControl']=dict(manualPause=True,inputBlocked=True,deviceBlocked=True,deviceConfirmation=True)
    task=cloud.create(phone,'create',value)['taskId'];before=cloud.store.get('owner',task)
    intent=dict(owner='owner',taskId=task,revision=before['revision'],grant=persistent()['grant'])
    first=cloud.update_hosting(phone,'update-once',intent)
    assert cloud.update_hosting(phone,'update-once',intent)==first
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    assert state['manualPaused'] and state['inputBlocked'] and state['deviceBlocked'] and state['deviceConfirmation']
    assert state['model']==value['model'] and state['grantEpoch']>before['auth_epoch']
    cloud.control(phone,'revoke',dict(owner='owner',taskId=task,revision=state['revision'],action='revoke',payload=''))
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    with pytest.raises(AuthorizationRequired):cloud.update_hosting(phone,'must-not-restore',dict(intent,revision=state['revision']))
    assert cloud.store.get('owner',task)['authorization']=='REVOKED'


def test_active_key_rotation_is_physically_validated_and_does_not_resume_pause(host):
    cloud,phone,_=host;value=persistent();value['initialControl']=dict(manualPause=True,inputBlocked=False,deviceBlocked=False,deviceConfirmation=False)
    task=cloud.create(phone,'create',value)['taskId'];before=cloud.store.get('owner',task)
    cloud.replace_credential(phone,'proactive-rotation',dict(owner='owner',taskId=task,revision=before['revision'],secret='SYNTHETIC-ROTATED'))
    token,lease=worker(host,'validation')
    saved=cloud.configuration(token,lease);config=cloud.vault.open(saved['binding'],saved['ciphertext'])
    assert config['secret']=='SYNTHETIC-ROTATED'
    body=encode_request(config,[dict(role='user',content='Validate pinned model')],[])
    cloud.provider_prepare(token,'physical-validation',dict(lease,role='credential_validation',body=body))
    cloud.provider_finish(token,dict(lease,requestId='physical-validation',result=dict(text='OK',toolCalls=[],usage=None),rejectedStatus=None))
    cloud.apply_validation(token,lease)
    current=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    assert current['authorization']=='ACTIVE' and current['manualPaused'] and current['model']==value['model']
    with cloud.store.transaction() as db:
        job=db.execute('SELECT * FROM net_jobs WHERE task_id=?',(task,)).fetchone()
        assert job['credential_revision']==2
        assert cloud.vault.open(cloud.binding(cloud.store.get('owner',task),job),job['config_cipher'])['secret']=='SYNTHETIC-ROTATED'


@pytest.mark.parametrize('rejected',[True,False])
def test_known_key_validation_outcome_can_recover_without_replaying_a_successful_request(host,rejected):
    cloud,phone,_=host;value=persistent();value['initialControl']=dict(manualPause=True,inputBlocked=False,deviceBlocked=False,deviceConfirmation=False)
    task=cloud.create(phone,'create',value)['taskId'];before=cloud.store.get('owner',task)
    cloud.replace_credential(phone,'first-key',dict(owner='owner',taskId=task,revision=before['revision'],secret='SYNTHETIC-FIRST-REPLACEMENT'))
    token,lease=worker(host,'first-validation')
    saved=cloud.configuration(token,lease);config=cloud.vault.open(saved['binding'],saved['ciphertext'])
    body=encode_request(config,[dict(role='user',content='Validate pinned model')],[])
    cloud.provider_prepare(token,'physical-validation',dict(lease,role='credential_validation',body=body))
    cloud.provider_finish(token,dict(lease,requestId='physical-validation',result=None if rejected else dict(text='OK',toolCalls=[],usage=None),rejectedStatus=401 if rejected else None))
    cloud.authority.retire_process(cloud.authority.worker_actor(lease['taskId'],lease['instance']),lease['instance'],exit_code=1)
    current=cloud.store.get('owner',task)
    secret='SYNTHETIC-CORRECTED-REPLACEMENT' if rejected else 'SYNTHETIC-FIRST-REPLACEMENT'
    response=cloud.replace_credential(phone,'explicit-key-recovery',dict(owner='owner',taskId=task,revision=current['revision'],secret=secret))
    child=cloud.store.get('owner',response['validationTaskId'])
    assert child['state']=='QUEUED'
    if rejected:
        old=cloud.store.get('owner',lease['taskId'])
        assert child['id']!=lease['taskId'] and json.loads(old['result_json'])['validation']=='REJECTED'
    else:
        assert child['id']==lease['taskId']
        next_token,next_lease=worker(host,'recovered-validation')
        assert cloud.provider_prepare(next_token,'physical-validation',dict(next_lease,role='credential_validation',body=body))['dispatch'] is False
        cloud.apply_validation(next_token,next_lease)
        assert cloud.store.get('owner',task)['manual_pause']==1


def test_unknown_key_validation_cannot_be_bypassed_with_another_key(host):
    cloud,phone,_=host;value=persistent();value['initialControl']=dict(manualPause=True,inputBlocked=False,deviceBlocked=False,deviceConfirmation=False)
    task=cloud.create(phone,'create',value)['taskId'];before=cloud.store.get('owner',task)
    cloud.replace_credential(phone,'first-key',dict(owner='owner',taskId=task,revision=before['revision'],secret='SYNTHETIC-FIRST-REPLACEMENT'))
    token,lease=worker(host,'unknown-validation')
    saved=cloud.configuration(token,lease);config=cloud.vault.open(saved['binding'],saved['ciphertext'])
    body=encode_request(config,[dict(role='user',content='Validate pinned model')],[])
    cloud.provider_prepare(token,'no-ack',dict(lease,role='credential_validation',body=body))
    cloud.authority.retire_process(cloud.authority.worker_actor(lease['taskId'],lease['instance']),lease['instance'],exit_code=1)
    current=cloud.store.get('owner',task)
    with pytest.raises(Conflict):cloud.replace_credential(phone,'must-not-bypass',dict(owner='owner',taskId=task,revision=current['revision'],secret='SYNTHETIC-OTHER-REPLACEMENT'))
    with cloud.store.transaction() as db:assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0]==lease['taskId']


def test_revoke_during_validation_accepts_known_rejection_but_never_installs_key(host):
    cloud,phone,_=host;value=persistent();value['initialControl']=dict(manualPause=True,inputBlocked=False,deviceBlocked=False,deviceConfirmation=False)
    task=cloud.create(phone,'create',value)['taskId'];before=cloud.store.get('owner',task)
    cloud.replace_credential(phone,'validate',dict(owner='owner',taskId=task,revision=before['revision'],secret='SYNTHETIC-LATE-KEY'))
    token,lease=worker(host,'late-validation')
    saved=cloud.configuration(token,lease);config=cloud.vault.open(saved['binding'],saved['ciphertext'])
    body=encode_request(config,[dict(role='user',content='Validate pinned model')],[])
    cloud.provider_prepare(token,'physical-validation',dict(lease,role='credential_validation',body=body))
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    cloud.control(phone,'revoke-original',dict(owner='owner',taskId=task,revision=state['revision'],action='revoke',payload=''))
    assert cloud.provider_finish(token,dict(lease,requestId='physical-validation',result=None,rejectedStatus=401))['recorded']
    cloud.authority.retire_process(cloud.authority.worker_actor(lease['taskId'],lease['instance']),lease['instance'],exit_code=1)
    with cloud.store.transaction() as db:
        assert db.execute('SELECT state FROM net_provider_calls WHERE task_id=?',(lease['taskId'],)).fetchone()[0]=='RETURNED'
        assert db.execute('SELECT task_id FROM execution_slot').fetchone()[0] is None
        assert db.execute('SELECT credential_revision FROM net_jobs WHERE task_id=?',(task,)).fetchone()[0]==1
    assert cloud.store.get('owner',task)['authorization']=='REVOKED'


def test_duration_renewal_leaves_expired_credential_and_independent_blockers_intact(host):
    cloud,phone,_=host;value=request()
    value['initialControl']=dict(manualPause=True,inputBlocked=True,deviceBlocked=True,deviceConfirmation=True)
    task=cloud.create(phone,'create',value)['taskId']
    cloud.store.change_authorization('owner',task,'EXPIRED')
    before=cloud.store.get('owner',task)
    cloud.update_hosting(phone,'renew-duration-only',dict(owner='owner',taskId=task,revision=before['revision'],grant=persistent()['grant']))
    state=cloud.authority.task(phone,dict(owner='owner',taskId=task))
    assert state['authorization']=='EXPIRED' and state['grantEpoch']>before['auth_epoch']
    assert state['manualPaused'] and state['inputBlocked'] and state['deviceBlocked'] and state['deviceConfirmation']
    assert state['model']==value['model'] and state['grantMode']=='UNTIL_REVOKED'
