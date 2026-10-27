"""Reproducible local protocol E2E: read -> exact check -> version -> crash -> reconcile.

The plan is deterministic and the inputs are synthetic. No model/API/OCR call is
made. A separate unit test uses a real child process exit; this demonstration uses
a synthetic BaseException interruption so that evidence can be retained in one run.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from .store import ModelLock, TaskStore, canonical
from .runner import LocalFiles, Runner, registry

class SimulatedProcessInterruption(BaseException): pass


def run(output: Path):
    output=Path(output).resolve()
    if output.exists() and any(output.iterdir()): raise ValueError('demo_directory_must_be_new_or_empty')
    output.mkdir(parents=True,exist_ok=True)
    store=TaskStore(output/'tasks.sqlite3');files=LocalFiles(output/'managed_files');runner=Runner(store,registry(files))
    owner='synthetic-profile';model=ModelLock('fixture-no-provider','no-model-called','high','fixture-lock-v1')
    expressions=['(x+1)^2','x^2+2*x+1','x^2+1']
    source=files.import_synthetic_text(owner,'lesson',json.dumps({'expressions':expressions}))
    task=store.enqueue(owner,'demo-request-v1','Locate the first wrong equal-expression transition and retain a report',model,
                       ['read','check','write'],['files.read','math.exact_polynomial','files.write_version'],
                       {'check':{'kind':'equals','field':'first_error_transition','value':2},
                        'write':{'kind':'present','field':'artifact_id'}})
    other=store.enqueue(owner,'other-independent-task','A synthetic ordinary greeting',model,[],[],{},require_evidence=False)
    lease=store.claim();assert lease.task_id==task
    read=runner.call(lease,'read','read_material_text',{'material_id':'lesson'})
    checked=runner.call(lease,'check','verify_equal_expression_chain',json.loads(read.result['text']))
    report='# Synthetic algebra review\n\nScope: equal-expression chains over Q[x], not general proof grading.\n\n'\
           'First incorrect transition: **2**. The preceding transition was verified by exact coefficients.\n\n'\
           'At x = 1, the left expression is 4 while the right is 2. The missing term is 2x.\n\n'\
           'No textbook page, OCR reading, language model, paid API or production service was used.\n'
    args={'family':'synthetic-algebra-report','content':report,'parent_artifact_id':None}
    def interrupt(stage):
        if stage=='after_execute': raise SimulatedProcessInterruption()
    try:runner.call(lease,'write','write_report_version',args,fault=interrupt)
    except SimulatedProcessInterruption:pass
    store=TaskStore(store.path);store.recover_interrupted();runner=Runner(store,registry(files))
    assert store.get(owner,task)['state']=='UNCERTAIN_SIDE_EFFECT' and store.claim() is None
    v1=runner.reconcile_report(owner,task,'write',files,executor_stopped=True)
    assert store.get(owner,task)['state']=='FAILED_BLOCKED'
    store.resume(owner,task,store.get(owner,task)['revision'])
    lease=store.claim();assert lease.task_id==task
    replay=runner.call(lease,'write','write_report_version',args);assert replay.result==v1
    store.complete(lease,{'report_artifact_id':v1['artifact_id'],'math_status':checked.result['status']})
    lease=store.claim();assert lease.task_id==other
    store.complete(lease,{'reply':'Hello. No tool was needed for this synthetic greeting.'})
    second=store.enqueue(owner,'demo-request-v2','Add one teaching note without overwriting the previous version',model,
                         ['write'],['files.write_version'],{'write':{'kind':'present','field':'artifact_id'}})
    lease=store.claim()
    args2={'family':'synthetic-algebra-report','content':report+'\nTeaching note: expand the middle term before simplifying.\n',
           'parent_artifact_id':v1['artifact_id']}
    v2=runner.call(lease,'write','write_report_version',args2).result;store.complete(lease,v2)
    folder=files.directory(owner,'artifacts')
    assert (folder/v1['relative_path']).read_text()==report
    assert len(list(folder.glob('*.md')))==2
    evidence={'scope':'LOCAL_PROTOCOL_E2E_ONLY','model_calls':0,'real_device_calls':0,
              'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
              'first_task':store.get(owner,task)['state'],'second_version_task':store.get(owner,second)['state'],
              'math_result':checked.result,'versions':[v1,v2],'version_files':[str(folder/v1['relative_path']),str(folder/v2['relative_path'])],
              'events':store.events(owner,task),
              'assertions':['global_slot_held_while_uncertain','report_read_back_after_interrupt',
                            'explicit_resume_required','no_duplicate_write','old_version_preserved','no_tool_for_chitchat']}
    (output/'evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
    print(json.dumps({k:evidence[k] for k in ['scope','model_calls','first_task','second_version_task','assertions']},indent=2))
    return evidence

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path)
    run(parser.parse_args().output)
