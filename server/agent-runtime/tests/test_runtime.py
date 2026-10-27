import json
import os
import sqlite3
import subprocess
import sys
import threading
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pytest
from hxai_agent.store import (TaskStore,ModelLock,Lease,Receipt,Conflict,StaleLease,
                             UncertainEffect,AuthorizationRequired,digest)
from hxai_agent.runner import LocalFiles,Runner,registry
import hxai_agent.runner as runtime_runner

MODEL=ModelLock('fixture-provider','locked-model','high','configuration-1')
CAPS=['files.read','files.write_version','math.exact_polynomial']

@pytest.fixture
def store(tmp_path):return TaskStore(tmp_path/'tasks.sqlite3')

def add(s,key='req1',owner='profile-a',calls=None,caps=None,acceptance=None):
    calls=calls or ['step1']
    acceptance=acceptance or {calls[-1]:{'kind':'present','field':'artifact_id'}}
    return s.enqueue(owner,key,'synthetic goal',MODEL,calls,caps or CAPS,acceptance)

def prepare(s,lease,call='step1',effect='VERSIONED_WRITE',args=None):
    return s.prepare_call(lease,call,'synthetic-tool',args or {'text':'fixture'},'files.write_version',effect,MODEL)

def proof(result):return {'verified':True,'method':'synthetic-independent-check','result_sha256':digest(result)}

def finish(s,p,result=None):
    result=result or {'artifact_id':'synthetic-artifact'}
    s.commit_result(p,result,proof(result))

def resume(s,task,owner='profile-a',**kwargs):
    s.resume(owner,task,s.get(owner,task)['revision'],**kwargs)

@contextmanager
def directory_redirect(path, target):
    if os.name == 'nt':
        # /J is a real, privilege-free directory junction, not a file symlink.
        # https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/mklink
        subprocess.run(['cmd.exe','/d','/c','mklink','/J',str(path),str(target)],
                       capture_output=True,check=True,timeout=10)
    else:
        path.symlink_to(target,target_is_directory=True)
    try:
        assert path.is_dir()
        yield
    finally:
        if os.name == 'nt':path.rmdir()
        else:path.unlink()



def test_idempotency_binds_intent_and_owner(store):
    task=add(store);assert add(store)==task
    with pytest.raises(Conflict):store.enqueue('profile-a','req1','different',MODEL,['step1'],CAPS,{'step1':{'kind':'present','field':'artifact_id'}})
    assert add(store,owner='profile-b')!=task


def test_model_selection_is_locked_even_in_database(store):
    task=add(store);lease=store.claim()
    for model in [ModelLock('other',MODEL.model_id,MODEL.reasoning,MODEL.configuration_revision),
                  ModelLock(MODEL.provider_id,'other',MODEL.reasoning,MODEL.configuration_revision),
                  ModelLock(MODEL.provider_id,MODEL.model_id,'low',MODEL.configuration_revision),
                  ModelLock(MODEL.provider_id,MODEL.model_id,MODEL.reasoning,'other')]:
        with pytest.raises(Conflict):store.prepare_call(lease,'step1','tool',{},CAPS[0],'READ',model)
    with pytest.raises(sqlite3.IntegrityError):
        with store.transaction() as db:db.execute('UPDATE tasks SET model_json=? WHERE id=?',('{}',task))


def test_concurrent_claims_share_one_global_slot(store):
    for i in range(12):add(store,key=f'req{i}',owner=f'profile-{i%3}')
    barrier=threading.Barrier(12)
    def claim(_):barrier.wait();return store.claim()
    with ThreadPoolExecutor(max_workers=12) as pool:claims=list(pool.map(claim,range(12)))
    assert sum(c is not None for c in claims)==1 and store.claim() is None


def test_plan_order_and_input_identity(store):
    add(store,calls=['step1','step2']);lease=store.claim()
    with pytest.raises(Conflict):prepare(store,lease,'step2')
    p=prepare(store,lease)
    with pytest.raises(Conflict):prepare(store,lease,args={'text':'changed'})
    store.dispatch(p)
    with pytest.raises(UncertainEffect):store.dispatch(p)
    finish(store,p);assert isinstance(prepare(store,lease),Receipt)


def test_pause_before_dispatch_invalidates_lease(store):
    task=add(store);lease=store.claim();p=prepare(store,lease)
    store.pause(lease.owner,task)
    with pytest.raises(StaleLease):store.dispatch(p)
    assert store.get_call(lease.owner,task,'step1')['state']=='NOT_DISPATCHED'
    resume(store,task);new=store.claim();assert new.fence>lease.fence
    with pytest.raises(StaleLease):prepare(store,lease)


def test_pause_during_flight_holds_slot_until_verified(store):
    task=add(store);other=add(store,key='other');lease=store.claim();p=prepare(store,lease);store.dispatch(p)
    store.pause(lease.owner,task)
    assert store.get(lease.owner,task)['state']=='STOP_REQUESTED' and store.claim() is None
    finish(store,p)
    assert store.get(lease.owner,task)['state']=='PAUSED_BY_USER'
    assert store.claim().task_id==other


def test_cancel_during_flight_does_not_hide_receipt_or_resume(store):
    task=add(store);lease=store.claim();p=prepare(store,lease);store.dispatch(p);store.cancel(lease.owner,task)
    assert store.claim() is None
    finish(store,p)
    assert store.get(lease.owner,task)['state']=='CANCELED'
    assert store.get_call(lease.owner,task,'step1')['state']=='SUCCEEDED'
    with pytest.raises(Conflict):resume(store,task)


def test_unknown_effect_holds_slot_despite_cancel_timeout_and_restart(store):
    task=add(store);add(store,key='other');lease=store.claim();p=prepare(store,lease);store.dispatch(p)
    store.fail_call(p);store.cancel(lease.owner,task)
    with store.transaction() as db:db.execute('UPDATE tasks SET updated=0 WHERE id=?',(task,))
    store.recover_interrupted()
    assert store.claim() is None
    assert store.get(lease.owner,task)['state']=='UNCERTAIN_SIDE_EFFECT'
    with pytest.raises(UncertainEffect):store.wait(lease,'WAITING_DEVICE','offline')
    with pytest.raises(Conflict):resume(store,task)
    result={'artifact_id':'synthetic-artifact'}
    with pytest.raises(UncertainEffect):store.reconcile_applied(lease.owner,task,'step1',result,proof(result),executor_stopped=False)
    store.reconcile_applied(lease.owner,task,'step1',result,proof(result),executor_stopped=True)
    assert store.get(lease.owner,task)['state']=='CANCELED' and store.claim() is not None


def test_revoke_before_dispatch_and_stale_grant(store):
    task=add(store);lease=store.claim();p=prepare(store,lease)
    store.change_authorization(lease.owner,task,'REVOKED')
    with pytest.raises((StaleLease,AuthorizationRequired)):store.dispatch(p)
    row=store.get(lease.owner,task)
    with pytest.raises(AuthorizationRequired):store.restore_authorization(lease.owner,task,row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')
    with pytest.raises(Conflict):store.restore_authorization(lease.owner,task,row['auth_epoch']-1,CAPS,source='USER_REAUTHORIZE')
    store.restore_authorization(lease.owner,task,row['auth_epoch'],CAPS,source='USER_REAUTHORIZE')
    new=store.claim();assert new.fence!=lease.fence
    with pytest.raises(StaleLease):store.dispatch(p)


def test_revoke_inflight_can_record_result_but_not_start_next_call(store):
    task=add(store,calls=['step1','step2']);lease=store.claim();p=prepare(store,lease);store.dispatch(p)
    store.change_authorization(lease.owner,task,'REVOKED');assert store.claim() is None
    finish(store,p)
    assert store.get(lease.owner,task)['state']=='WAITING_AUTHORIZATION'
    with pytest.raises((StaleLease,AuthorizationRequired)):prepare(store,lease,'step2')


def test_expiry_can_requeue_but_user_pause_is_sticky(store):
    task=add(store);store.change_authorization('profile-a',task,'EXPIRED')
    row=store.get('profile-a',task);store.restore_authorization('profile-a',task,row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')
    assert store.get('profile-a',task)['state']=='QUEUED'
    store.pause('profile-a',task);store.change_authorization('profile-a',task,'EXPIRED')
    row=store.get('profile-a',task);store.restore_authorization('profile-a',task,row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')
    assert store.get('profile-a',task)['state']=='PAUSED_BY_USER' and store.claim() is None


def test_expiry_cannot_weaken_explicit_revocation(store):
    task=add(store);store.change_authorization('profile-a',task,'REVOKED');store.change_authorization('profile-a',task,'EXPIRED')
    assert store.get('profile-a',task)['authorization']=='REVOKED'


def test_credential_validation_cannot_increase_capabilities(store):
    task=add(store,caps=['files.read']);store.change_authorization('profile-a',task,'EXPIRED');row=store.get('profile-a',task)
    with pytest.raises(AuthorizationRequired):store.restore_authorization('profile-a',task,row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')


def test_device_takeover_needs_foreground_confirmation_and_never_preempts(store):
    task=add(store);other=add(store,key='other');lease=store.claim()
    store.wait(lease,'WAITING_DEVICE','screen_unavailable',foreground_required=True)
    assert not store.device_available('profile-a',task)
    with pytest.raises(AuthorizationRequired):resume(store,task)
    running=store.claim();assert running.task_id==other
    resume(store,task,foreground_device_confirmed=True);assert store.claim() is None
    store.pause('profile-a',other);assert store.claim().task_id==task


def test_non_takeover_device_step_requeues_but_manual_pause_does_not(store):
    task=add(store);lease=store.claim();store.wait(lease,'WAITING_DEVICE','local_file_offline')
    assert store.device_available('profile-a',task)
    lease=store.claim();store.wait(lease,'WAITING_DEVICE','local_file_offline');store.pause('profile-a',task)
    assert not store.device_available('profile-a',task)


def test_revision_fences_resume_and_replan(store):
    task=add(store);old=store.get('profile-a',task)['revision'];store.pause('profile-a',task)
    with pytest.raises(Conflict):store.resume('profile-a',task,old)
    with pytest.raises(Conflict):store.replan('profile-a',task,old,['other'],{'other':{'kind':'present','field':'artifact_id'}})


def test_completion_requires_every_receipt_and_actual_goal_predicate(store):
    task=add(store,acceptance={'step1':{'kind':'one_of','field':'status','value':['VERIFIED','DISPROVED']}})
    lease=store.claim()
    with pytest.raises(Conflict):store.complete(lease,{'claim':'done'})
    p=prepare(store,lease);store.dispatch(p);finish(store,p,{'status':'UNSUPPORTED'})
    with pytest.raises(Conflict,match='goal_acceptance'):store.complete(lease,{'claim':'proved'})
    assert store.get('profile-a',task)['state']=='RUNNING'


def test_replan_keeps_completed_receipts_and_rejects_unknown_effect(store):
    task=add(store);lease=store.claim();p=prepare(store,lease);store.dispatch(p);finish(store,p);store.pause('profile-a',task)
    row=store.get('profile-a',task)
    store.replan('profile-a',task,row['revision'],['step1','step2'],{'step2':{'kind':'present','field':'artifact_id'}})
    assert store.get_call('profile-a',task,'step1')['state']=='SUCCEEDED'
    resume(store,task);lease=store.claim();p=prepare(store,lease,'step2');store.dispatch(p);store.fail_call(p)
    with pytest.raises(Conflict):store.replan('profile-a',task,store.get('profile-a',task)['revision'],['step1'],{'step1':{'kind':'present','field':'artifact_id'}})


def test_receipt_hash_and_effect_classification(store):
    task=add(store);lease=store.claim();p=prepare(store,lease,effect='EXTERNAL');store.dispatch(p)
    result={'artifact_id':'synthetic-artifact'};bad=proof(result);bad['result_sha256']='0'*64
    with pytest.raises(ValueError):store.commit_result(p,result,bad)
    with pytest.raises(Conflict):store.fail_call(p,safe_local_read=True)
    assert store.get_call('profile-a',task,'step1')['state']=='IN_FLIGHT' and store.claim() is None


def test_owner_boundary_and_metadata_only_events(store):
    task=store.enqueue('profile-a','req1','PRIVATE-GOAL-SENTINEL',MODEL,['step1'],CAPS,{'step1':{'kind':'present','field':'artifact_id'}})
    lease=store.claim();prepare(store,lease,args={'text':'PRIVATE-BODY-SENTINEL'})
    for action in [lambda:store.get('profile-b',task),lambda:store.get_call('profile-b',task,'step1'),lambda:store.events('profile-b',task),lambda:prepare(store,Lease(task,'profile-b',lease.fence))]:
        with pytest.raises(KeyError):action()
    events=json.dumps(store.events('profile-a',task));assert 'PRIVATE-GOAL' not in events and 'PRIVATE-BODY' not in events
    with pytest.raises(ValueError):prepare(store,lease,args={'nested':{'api_key':'synthetic'}})


def test_usage_is_idempotent_no_budget_and_unknown_not_zero(store):
    task=add(store)
    store.record_usage('profile-a',task,'provider-request-1','provider_reported',10**9,10**9,0)
    store.record_usage('profile-a',task,'provider-request-1','provider_reported',10**9,10**9,0)
    store.record_usage('profile-a',task,'provider-request-2','unknown')
    with store.transaction() as db:assert db.execute('SELECT COUNT(*) FROM usage').fetchone()[0]==2
    assert store.get('profile-a',task)['state']=='QUEUED'
    with pytest.raises(ValueError):store.record_usage('profile-a',task,'bad','unknown',0)


def test_transaction_rollback_and_unknown_schema_refusal(store,tmp_path):
    task=add(store)
    with pytest.raises(RuntimeError):
        with store.transaction() as db:
            db.execute("UPDATE tasks SET goal='corrupted' WHERE id=?",(task,));raise RuntimeError('fault')
    assert store.get('profile-a',task)['goal']=='synthetic goal'
    with sqlite3.connect(tmp_path/'future.sqlite3') as db:db.execute('PRAGMA user_version=100')
    with pytest.raises(RuntimeError):TaskStore(tmp_path/'future.sqlite3')


class Crash(BaseException):pass

@pytest.mark.parametrize('stage',['after_prepare','after_dispatch','after_execute','after_verify','after_commit'])
def test_crash_boundaries_never_duplicate_report(tmp_path,stage):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s);lease=s.claim();files=LocalFiles(tmp_path/'files');r=Runner(s,registry(files))
    args={'family':'report','content':'SYNTHETIC-REPORT','parent_artifact_id':None}
    def fault(where):
        if where==stage:raise Crash()
    with pytest.raises(Crash):r.call(lease,'step1','write_report_version',args,fault=fault)
    s=TaskStore(s.path);s.recover_interrupted();r=Runner(s,registry(files))
    if stage=='after_dispatch':
        assert s.get('profile-a',task)['state']=='UNCERTAIN_SIDE_EFFECT'
        with pytest.raises(ValueError):r.reconcile_report('profile-a',task,'step1',files,executor_stopped=True)
        assert s.claim() is None
        return
    if stage in {'after_execute','after_verify'}:r.reconcile_report('profile-a',task,'step1',files,executor_stopped=True)
    resume(s,task);lease=s.claim();receipt=r.call(lease,'step1','write_report_version',args);s.complete(lease,receipt.result)
    assert len(list(files.directory('profile-a','artifacts').glob('*.md')))==1
    assert s.get('profile-a',task)['state']=='SUCCEEDED'


def test_local_read_crash_requires_resume_not_blind_automatic_replay(tmp_path):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s,acceptance={'step1':{'kind':'present','field':'sha256'}});lease=s.claim()
    f=LocalFiles(tmp_path/'files');f.import_synthetic_text('profile-a','lesson','text');r=Runner(s,registry(f))
    def fault(stage):
        if stage=='after_dispatch':raise Crash()
    with pytest.raises(Crash):r.call(lease,'step1','read_material_text',{'material_id':'lesson'},fault=fault)
    s.recover_interrupted()
    assert s.get('profile-a',task)['state']=='FAILED_BLOCKED' and s.claim() is None
    assert s.get_call('profile-a',task,'step1')['state']=='FAILED_SAFE'
    resume(s,task);assert r.call(s.claim(),'step1','read_material_text',{'material_id':'lesson'}).result['text']=='text'


def test_immutable_versions_and_profile_isolation(tmp_path):
    s=TaskStore(tmp_path/'tasks.sqlite3');f=LocalFiles(tmp_path/'files');r=Runner(s,registry(f))
    add(s,key='first');lease=s.claim();v1=r.call(lease,'step1','write_report_version',{'family':'notes','content':'one','parent_artifact_id':None});s.complete(lease,v1.result)
    add(s,key='second');lease=s.claim();v2=r.call(lease,'step1','write_report_version',{'family':'notes','content':'two','parent_artifact_id':v1.result['artifact_id']});s.complete(lease,v2.result)
    assert (f.directory('profile-a','artifacts')/v1.result['relative_path']).read_text()=='one'
    assert (f.directory('profile-a','artifacts')/v2.result['relative_path']).read_text()=='two'
    assert not list(f.directory('profile-b','artifacts').iterdir())

def test_report_publication_supports_unicode_private_root(tmp_path):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s);lease=s.claim()
    f=LocalFiles(tmp_path/'版本 publication');r=Runner(s,registry(f))
    args={'family':'unicode','content':'合成报告','parent_artifact_id':None}
    receipt=r.call(lease,'step1','write_report_version',args)
    folder=f.directory('profile-a','artifacts')
    assert (folder/receipt.result['relative_path']).read_bytes()==args['content'].encode()
    assert f.verify_report(lease,'step1',args,receipt.result)
    assert not list(folder.glob('.pending-*'))
    s.complete(lease,receipt.result);assert s.get('profile-a',task)['state']=='SUCCEEDED'


def test_native_publication_never_replaces_existing_bytes(tmp_path):
    pending=tmp_path/'.pending-native';target=tmp_path/'existing.md'
    pending.write_bytes(b'NEW');target.write_bytes(b'PRESERVE-OLD')
    with pytest.raises(FileExistsError):
        runtime_runner._publish_immutable(pending,target)
    assert target.read_bytes()==b'PRESERVE-OLD' and pending.read_bytes()==b'NEW'


def test_native_publication_propagates_missing_source_error(tmp_path):
    target=tmp_path/'absent.md'
    with pytest.raises(FileNotFoundError) as error:
        runtime_runner._publish_immutable(tmp_path/'missing-pending',target)
    if os.name == 'nt':assert error.value.winerror==2
    assert not target.exists()


def test_conflicting_artifact_bytes_remain_untouched_and_unknown(tmp_path):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s);lease=s.claim()
    f=LocalFiles(tmp_path/'files');r=Runner(s,registry(f))
    args={'family':'conflict','content':'EXPECTED','parent_artifact_id':None}
    result=f.report_result(lease,'step1',args);folder=f.directory('profile-a','artifacts')
    target=folder/result['relative_path'];target.write_bytes(b'PRESERVE-CONFLICT')
    with pytest.raises(ValueError,match='immutable_artifact_conflict'):
        r.call(lease,'step1','write_report_version',args)
    assert target.read_bytes()==b'PRESERVE-CONFLICT'
    assert s.get_call('profile-a',task,'step1')['state']=='UNKNOWN'
    assert s.get('profile-a',task)['state']=='UNCERTAIN_SIDE_EFFECT' and s.claim() is None
    with pytest.raises(ValueError,match='artifact_not_verified_do_not_replay'):
        r.reconcile_report('profile-a',task,'step1',f,executor_stopped=True)
    assert s.get_call('profile-a',task,'step1')['state']=='UNKNOWN' and s.claim() is None
    assert not list(folder.glob('.pending-*'))


@pytest.mark.parametrize('identical',[True,False])
def test_publication_race_accepts_only_identical_existing_bytes(tmp_path,monkeypatch,identical):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s);lease=s.claim()
    f=LocalFiles(tmp_path/'files');r=Runner(s,registry(f))
    args={'family':'race','content':'EXPECTED','parent_artifact_id':None}
    result=f.report_result(lease,'step1',args);folder=f.directory('profile-a','artifacts')
    winner=args['content'].encode() if identical else b'CONFLICTING-WINNER'
    publish=runtime_runner._publish_immutable
    def race(pending,target):
        with target.open('xb') as out:out.write(winner)
        publish(pending,target)  # Exercise the actual native no-replace operation.
    monkeypatch.setattr(runtime_runner,'_publish_immutable',race)
    if identical:
        receipt=r.call(lease,'step1','write_report_version',args)
        assert receipt.result==result;s.complete(lease,receipt.result)
        assert s.get_call('profile-a',task,'step1')['state']=='SUCCEEDED'
    else:
        with pytest.raises(ValueError,match='immutable_artifact_conflict'):
            r.call(lease,'step1','write_report_version',args)
        assert s.get_call('profile-a',task,'step1')['state']=='UNKNOWN' and s.claim() is None
        with pytest.raises(ValueError,match='artifact_not_verified_do_not_replay'):
            r.reconcile_report('profile-a',task,'step1',f,executor_stopped=True)
    assert (folder/result['relative_path']).read_bytes()==winner
    assert len(list(folder.glob('*.md')))==1 and not list(folder.glob('.pending-*'))


def test_error_after_native_publication_requires_readback_and_explicit_recovery(tmp_path,monkeypatch):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s);lease=s.claim()
    f=LocalFiles(tmp_path/'files');r=Runner(s,registry(f))
    args={'family':'published-error','content':'PUBLISHED-ONCE','parent_artifact_id':None}
    publish=runtime_runner._publish_immutable
    def lose_ack(pending,target):
        publish(pending,target)
        raise OSError('failure after native publication')
    with monkeypatch.context() as patch:
        patch.setattr(runtime_runner,'_publish_immutable',lose_ack)
        with pytest.raises(OSError,match='failure after native publication'):
            r.call(lease,'step1','write_report_version',args)
    result=f.report_result(lease,'step1',args);folder=f.directory('profile-a','artifacts')
    target=folder/result['relative_path'];before=target.stat()
    assert target.read_bytes()==b'PUBLISHED-ONCE' and not list(folder.glob('.pending-*'))
    assert s.get_call('profile-a',task,'step1')['state']=='UNKNOWN' and s.claim() is None
    s=TaskStore(s.path);s.recover_interrupted();r=Runner(s,registry(f))
    with pytest.raises(UncertainEffect,match='old_executor_not_confirmed_stopped'):
        r.reconcile_report('profile-a',task,'step1',f,executor_stopped=False)
    assert s.get_call('profile-a',task,'step1')['state']=='UNKNOWN' and s.claim() is None
    assert r.reconcile_report('profile-a',task,'step1',f,executor_stopped=True)==result
    assert s.get('profile-a',task)['state']=='FAILED_BLOCKED' and s.claim() is None
    def no_reexecution(*args):raise AssertionError('verified report must not be republished')
    monkeypatch.setattr(runtime_runner,'_publish_immutable',no_reexecution)
    resume(s,task);lease=s.claim();receipt=r.call(lease,'step1','write_report_version',args)
    s.complete(lease,receipt.result)
    after=target.stat()
    assert (after.st_ino,after.st_mtime_ns)==(before.st_ino,before.st_mtime_ns)
    assert target.read_bytes()==b'PUBLISHED-ONCE' and len(list(folder.glob('*.md')))==1
    assert s.get('profile-a',task)['state']=='SUCCEEDED'



def test_traversal_and_symlinks_are_rejected(tmp_path):
    f=LocalFiles(tmp_path/'files')
    with pytest.raises(ValueError):f.validate_read({'material_id':'../secret'})
    if os.name == 'nt':
        outside=tmp_path/'outside';outside.mkdir()
        sentinel=outside/'lesson.txt';sentinel.write_text('SYNTHETIC')
        materials=f.directory('profile-a','materials');materials.rmdir()
        with directory_redirect(materials,outside):
            with pytest.raises(ValueError,match='reparse_point_not_allowed'):
                f.read(Lease('task','profile-a',1),'call',{'material_id':'lesson'})
        assert sentinel.read_text()=='SYNTHETIC'
    else:
        outside=tmp_path/'sentinel';outside.write_text('SYNTHETIC')
        (f.directory('profile-a','materials')/'lesson.txt').symlink_to(outside)
        with pytest.raises(ValueError):f.read(Lease('task','profile-a',1),'call',{'material_id':'lesson'})

@pytest.mark.parametrize('segment',['owner','materials','artifacts'])
def test_directory_redirects_cannot_traverse_managed_subdirectories(tmp_path,segment):
    f=LocalFiles(tmp_path/'files');category='materials' if segment=='owner' else segment
    folder=f.directory('profile-a',category);redirect=folder.parent if segment=='owner' else folder
    if segment=='owner':folder.rmdir()
    redirect.rmdir()
    outside=tmp_path/'outside';outside.mkdir()
    sentinel=outside/'sentinel.txt';sentinel.write_bytes(b'PRESERVE-OUTSIDE')
    with directory_redirect(redirect,outside):
        with pytest.raises(ValueError):f.directory('profile-a',category)
        if category=='materials':
            with pytest.raises(ValueError):
                f.read(Lease('task','profile-a',1),'call',{'material_id':'sentinel'})
        if segment in {'owner','artifacts'}:
            with pytest.raises(ValueError):
                f.write(Lease('task','profile-a',1),'call',
                        {'family':'blocked','content':'MUST-NOT-ESCAPE','parent_artifact_id':None})
    assert list(outside.iterdir())==[sentinel] and sentinel.read_bytes()==b'PRESERVE-OUTSIDE'


@pytest.mark.parametrize('ancestor',[False,True])
def test_windows_root_reparse_traversal_is_rejected_before_resolution(tmp_path,ancestor):
    outside=tmp_path/'outside';outside.mkdir()
    redirect=tmp_path/'redirect';root=redirect/'private' if ancestor else redirect
    with directory_redirect(redirect,outside):
        if os.name == 'nt':
            with pytest.raises(ValueError,match='reparse_point_not_allowed'):LocalFiles(root)
            assert not list(outside.iterdir())
        else:
            # Preserve the existing POSIX contract: callers choose a resolved root.
            f=LocalFiles(root)
            assert f.root==(outside/'private' if ancestor else outside).resolve()



def test_real_process_exit_after_write_is_reconciled_without_reexecution(tmp_path):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s);lease=s.claim();f=LocalFiles(tmp_path/'files')
    script='''
import json,os
from pathlib import Path
from hxai_agent.store import TaskStore,Lease
from hxai_agent.runner import LocalFiles,Runner,registry
p=json.loads(os.environ['FIXTURE']);s=TaskStore(Path(p['db']));f=LocalFiles(Path(p['files']))
def crash(stage):
    if stage=='after_execute':os._exit(77)
Runner(s,registry(f)).call(Lease(p['task'],'profile-a',p['fence']),'step1','write_report_version',
 {'family':'crash','content':'DURABLE-BEFORE-EXIT','parent_artifact_id':None},fault=crash)
'''
    env=os.environ.copy();env['FIXTURE']=json.dumps({'db':str(s.path),'files':str(f.root),'task':task,'fence':lease.fence})
    child=subprocess.run([sys.executable,'-c',script],env=env,capture_output=True,text=True,timeout=10)
    assert child.returncode==77,child.stderr
    s.recover_interrupted();r=Runner(s,registry(f));result=r.reconcile_report('profile-a',task,'step1',f,executor_stopped=True)
    resume(s,task);lease=s.claim();receipt=r.call(lease,'step1','write_report_version',{'family':'crash','content':'DURABLE-BEFORE-EXIT','parent_artifact_id':None})
    assert receipt.result==result;s.complete(lease,result)
    assert len(list(f.directory('profile-a','artifacts').glob('*.md')))==1


def test_lost_commit_ack_returns_durable_receipt(tmp_path,monkeypatch):
    s=TaskStore(tmp_path/'tasks.sqlite3');task=add(s);lease=s.claim();f=LocalFiles(tmp_path/'files');r=Runner(s,registry(f))
    original=s.commit_result
    def lose(*args,**kwargs):original(*args,**kwargs);raise OSError('lost ack after commit')
    monkeypatch.setattr(s,'commit_result',lose)
    receipt=r.call(lease,'step1','write_report_version',{'family':'ack','content':'once','parent_artifact_id':None})
    assert isinstance(receipt,Receipt) and s.get_call('profile-a',task,'step1')['state']=='SUCCEEDED'
    assert len(list(f.directory('profile-a','artifacts').glob('*.md')))==1


def test_chitchat_does_not_require_a_pointless_tool(store):
    task=store.enqueue('profile-a','hello','hello',MODEL,[],[],{},require_evidence=False)
    lease=store.claim();store.complete(lease,{'reply':'hello'})
    assert store.get('profile-a',task)['state']=='SUCCEEDED'


def test_foreground_device_blocker_survives_auth_expiry_and_revalidation(store):
    task=add(store);lease=store.claim()
    store.wait(lease,'WAITING_DEVICE','takeover_pending',foreground_required=True)
    store.change_authorization('profile-a',task,'EXPIRED')
    row=store.get('profile-a',task)
    store.restore_authorization('profile-a',task,row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')
    assert store.get('profile-a',task)['state']=='WAITING_DEVICE'
    assert store.claim() is None
    assert not store.device_available('profile-a',task)
    with pytest.raises(AuthorizationRequired):resume(store,task)
    resume(store,task,foreground_device_confirmed=True)
    assert store.claim().task_id==task


def test_missing_input_blocker_survives_auth_revalidation(store):
    task=add(store);lease=store.claim();store.wait(lease,'WAITING_INPUT','need_clearer_symbol')
    store.change_authorization('profile-a',task,'EXPIRED')
    row=store.get('profile-a',task)
    store.restore_authorization('profile-a',task,row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')
    assert store.get('profile-a',task)['state']=='WAITING_INPUT'
    assert store.claim() is None


def test_non_takeover_device_event_during_expiry_does_not_grant_authorization(store):
    task=add(store);lease=store.claim();store.wait(lease,'WAITING_DEVICE','file_unavailable')
    store.change_authorization('profile-a',task,'EXPIRED')
    assert not store.device_available('profile-a',task)
    assert store.get('profile-a',task)['state']=='WAITING_AUTHORIZATION'
    assert store.claim() is None
    row=store.get('profile-a',task);store.restore_authorization('profile-a',task,row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')
    assert store.get('profile-a',task)['state']=='QUEUED'


def test_v1_migration_preserves_data_and_does_not_guess_wait_prerequisites(tmp_path):
    root=Path(__file__).resolve().parents[1]
    db_path=tmp_path/'v1.sqlite3'
    with sqlite3.connect(db_path) as db:
        db.executescript((root/'migrations/001_task_runtime.sql').read_text())
        db.execute("""INSERT INTO tasks(id,owner,request_key,intent_hash,goal,model_json,plan_json,
          acceptance_json,require_evidence,state,queue_order,authorization,capabilities_json,created,updated)
          VALUES ('old','profile-a','old-request','hash','PRESERVE-GOAL',?,'["step1"]',
          '{"step1":{"kind":"present","field":"artifact_id"}}',1,'WAITING_AUTHORIZATION',1,'EXPIRED',?,1,1)""",(MODEL.encoded(),json.dumps(CAPS)))
    s=TaskStore(db_path);row=s.get('profile-a','old')
    assert row['goal']=='PRESERVE-GOAL' and row['model_json']==MODEL.encoded()
    assert row['blocked_on']=='WAITING_INPUT' and row['reason']=='migration_requires_dependency_review'
    s.restore_authorization('profile-a','old',row['auth_epoch'],CAPS,source='VERIFIED_REPLACEMENT')
    assert s.get('profile-a','old')['state']=='WAITING_INPUT' and s.claim() is None
    with s.transaction() as db:assert db.execute('PRAGMA user_version').fetchone()[0]==2
    assert TaskStore(db_path).get('profile-a','old')['goal']=='PRESERVE-GOAL'


def test_unrelated_database_is_never_adopted_or_modified(tmp_path):
    path=tmp_path/'unrelated.sqlite3'
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE user_data(secret TEXT)');db.execute("INSERT INTO user_data VALUES ('SYNTHETIC-PRESERVE')")
    before=path.read_bytes()
    with pytest.raises(RuntimeError,match='unrelated_database'):TaskStore(path)
    assert path.read_bytes()==before


def test_usage_reconciliation_preserves_provenance_unknowns_and_owner(store):
    task=add(store);other=add(store,key='other',owner='profile-b')
    store.record_usage('profile-a',task,'late','unknown')
    store.record_usage('profile-a',task,'late','estimated',20,30)
    store.record_usage('profile-a',task,'late','provider_reported',19,28,2)
    store.record_usage('profile-a',task,'late','unknown')
    store.record_usage('profile-a',task,'unknown-remains','unknown')
    store.record_usage('profile-b',other,'private-other-owner','provider_reported',999,888)
    rows=store.usage_summary('profile-a',0,10**12)
    groups={r['provenance']:r for r in rows}
    assert groups['provider_reported']['input_tokens']==19 and groups['provider_reported']['calls']==1
    assert groups['unknown']['input_tokens'] is None and groups['unknown']['known_input_calls']==0
    assert sum(r['calls'] for r in rows)==2
    with pytest.raises(Conflict):store.record_usage('profile-a',task,'late','provider_reported',999,28,2)
