import base64
import json
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
import pytest
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from hxai_agent.authority import NetworkAuthority, NetworkStore
from hxai_agent.store import TaskStore, ModelLock, Conflict, UncertainEffect, AuthorizationRequired, canonical

MODEL={'provider_id':'synthetic-provider','model_id':'pinned-model','reasoning':'HIGH','configuration_revision':'frozen-v1'}


@pytest.fixture
def authority(tmp_path):
    return NetworkAuthority(NetworkStore(tmp_path/'authority.sqlite3'))


def device(authority,name='device',owners=('owner',)):
    token=authority.enroll(name,list(owners))
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    pem=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    authority.pair(token,{'publicKey':pem})
    return token,key


def accept(authority,token,key='accept',source='source',owner='owner'):
    value={'owner':owner,'sourceId':source,'goal':'Native gate without user text','inputHash':'a'*64,'model':MODEL,'grantEpoch':1}
    return authority.native_accept(token,key,value)['taskId']


def claim(authority,token,task,instance='instance',owner='owner'):
    return authority.claim(token,{'owner':owner,'taskId':task,'instance':instance},mode='native')


def stop(authority,token,key,task,fence,*,source='source',instance='instance',state='PAUSED_BY_USER'):
    value={'owner':'owner','taskId':task,'fence':fence,'instance':instance,
           'proof':{'sourceId':source,'sourceFence':3,'state':state,'journalHash':'b'*64}}
    value['signature']=base64.b64encode(key.sign(canonical(value).encode(),padding.PKCS1v15(),hashes.SHA256())).decode()
    return authority.native_retire(token,value)


def test_two_devices_share_one_slot_and_fences_never_go_back(authority):
    one,k1=device(authority,'one');two,k2=device(authority,'two')
    t1=accept(authority,one);t2=accept(authority,two)
    barrier=threading.Barrier(2)
    def take(item):
        barrier.wait();return claim(authority,*item)
    with ThreadPoolExecutor(2) as pool:
        results=list(pool.map(take,[(one,t1),(two,t2)]))
    assert [r['state'] for r in results].count('RUNNING')==1
    first=results[0]
    assert first['state']=='RUNNING'  # global FIFO, not whichever device wins the network race
    stop(authority,one,k1,t1,first['fence'])
    second=claim(authority,two,t2)
    assert second['state']=='RUNNING' and second['fence']>first['fence']
    with pytest.raises(Conflict):
        authority.check(one,{'owner':'owner','taskId':t1,'fence':first['fence'],'instance':'instance'})


def test_claim_ack_does_not_grant_another_executor_instance(authority):
    token,key=device(authority);task=accept(authority,token)
    first=claim(authority,token,task)
    assert claim(authority,token,task)==first
    other=claim(authority,token,task,'other-process')
    assert other['state']=='QUEUED'
    with pytest.raises(Conflict):
        authority.check(token,{'owner':'owner','taskId':task,'fence':first['fence'],'instance':'other-process'})


def test_accept_ack_is_bound_to_full_intent_and_owner_scope(authority):
    token,key=device(authority)
    first=accept(authority,token)
    assert accept(authority,token)==first
    with pytest.raises(Conflict):accept(authority,token,source='changed')
    with pytest.raises(AuthorizationRequired):accept(authority,token,key='cross',owner='foreign')
    with pytest.raises(AuthorizationRequired):accept(authority,'x'*43,key='bad')


def test_unsigned_stop_or_unknown_journal_never_releases_slot(authority):
    token,key=device(authority);task=accept(authority,token);lease=claim(authority,token,task)
    with pytest.raises(ValueError):
        authority.native_retire(token,{'owner':'owner','taskId':task,'fence':lease['fence'],'instance':'instance','stopped':True})
    with pytest.raises(UncertainEffect):stop(authority,token,key,task,lease['fence'],state='UNCERTAIN_SIDE_EFFECT')
    other=accept(authority,token,key='other',source='other')
    assert claim(authority,token,other)['state']=='QUEUED'
    with pytest.raises(UncertainEffect):authority.store.recover_interrupted()
    # A real authority restart does not infer stop from time, a heartbeat, or an empty model journal.
    reopened=NetworkAuthority(NetworkStore(authority.store.path))
    assert claim(reopened,token,other)['state']=='QUEUED'


def test_foreign_signing_key_is_not_a_stop_proof(authority):
    token,key=device(authority);_,foreign=device(authority,'foreign')
    task=accept(authority,token);lease=claim(authority,token,task)
    with pytest.raises(InvalidSignature):stop(authority,token,foreign,task,lease['fence'])
    assert claim(authority,token,task)==lease


def test_revoke_blocks_dispatch_but_allows_bound_signed_stop_settlement(authority):
    token,key=device(authority);task=accept(authority,token);lease=claim(authority,token,task)
    authority.revoke_actor('device')
    with pytest.raises(AuthorizationRequired):
        authority.check(token,{'owner':'owner','taskId':task,'fence':lease['fence'],'instance':'instance'})
    assert stop(authority,token,key,task,lease['fence'])['state']=='STOPPED'
    assert stop(authority,token,key,task,lease['fence'])['state']=='STOPPED'
    with pytest.raises(AuthorizationRequired):accept(authority,token,key='new',source='new')


def test_changed_stop_ack_does_not_rewrite_retired_execution(authority):
    token,key=device(authority);task=accept(authority,token);lease=claim(authority,token,task)
    stop(authority,token,key,task,lease['fence'])
    with pytest.raises(Conflict):stop(authority,token,key,task,lease['fence'],state='CANCELED')


def test_pairing_cannot_replace_an_existing_device_key(authority):
    token,key=device(authority)
    other=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    pem=other.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    with pytest.raises(Conflict):authority.pair(token,{'publicKey':pem})


def test_network_migration_preserves_old_task_model_calls_and_usage(tmp_path):
    path=tmp_path/'old.sqlite3';old=TaskStore(path)
    task=old.enqueue('owner','old','Keep this task',ModelLock(**MODEL),[],[],{},require_evidence=False)
    old.record_usage('owner',task,'paid-unknown','unknown')
    before=old.get('owner',task)
    migrated=NetworkStore(path)
    assert migrated.get('owner',task)==before
    with migrated.transaction() as db:
        assert db.execute('PRAGMA user_version').fetchone()[0]==4
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        assert db.execute('SELECT provenance,input_tokens FROM usage WHERE request_id=?',('paid-unknown',)).fetchone()[:]==('unknown',None)
    with pytest.raises(RuntimeError):TaskStore(path)


def test_nested_authorization_failure_rolls_back_whole_outer_transaction(authority):
    with pytest.raises(RuntimeError):
        with authority.store.transaction() as db:
            token=authority.enroll('transaction',['owner'])
            with authority.request(token,roles=('pairing',)):
                raise RuntimeError('synthetic transaction failure')
    with authority.store.transaction() as db:
        assert db.execute('SELECT count(*) FROM net_actors').fetchone()[0]==0
