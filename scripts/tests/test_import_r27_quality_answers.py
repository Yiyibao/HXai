import copy
import importlib.util
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0,str(Path(__file__).parents[1]))
from prepare_r27_live_quality import build, AGENT_CASES
from score_b_math_evaluation import sha, write_new

spec=importlib.util.spec_from_file_location('quality_import',Path(__file__).parents[1]/'import_r27_quality_answers.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


@pytest.fixture
def exported(tmp_path):
    inputs=tmp_path/'inputs';build(inputs)
    blind=json.loads((inputs/'corpus/BLIND_PROMPTS.json').read_text(encoding='utf-8'))
    root=tmp_path/'export';root.mkdir()
    rows=[dict(id=r['id'],engineeringStatus='NOT_RUN',response='',reason='Synthetic input only') for r in blind['cases']+AGENT_CASES]
    first=rows[0];folder=root/first['id'];folder.mkdir()
    first.pop('reason')
    receipt={'id':first['id'],'taskId':'synthetic-task','artifacts':[]}
    usage={'provenance':'unknown'}
    rh=write_new(folder/'RECEIPT.json',receipt);uh=write_new(folder/'USAGE.json',usage)
    response='Synthetic original visible answer.'
    write_new(folder/'RESPONSE.json',{'response':response,'responseSha256':sha(response.encode())})
    first.update(promptSha256=sha(blind['cases'][0]['prompt'].encode()),engineeringStatus='FAIL',response=response,taskId='synthetic-task',
        modelSnapshot={'providerId':'synthetic-provider','endpoint':'https://xinyue.mom/v1','modelId':'gpt-6.1-terra','reasoning':'MEDIUM','configRevision':'synthetic-revision'},
        receiptSha256=rh,usageSha256=uh)
    run={'contractVersion':1,'origin':'ACTUAL_ORIGINAL_ANDROID_CHAT','runId':'synthetic-claimed-origin-not-attested','cases':rows}
    write_new(root/'RUN_OUTPUT.json',run)
    return root,inputs/'corpus',run,folder


def test_bound_bytes_import_without_treating_declared_origin_as_authentication(exported,tmp_path):
    root,corpus,_,_=exported
    result=module.import_answers(root,corpus,tmp_path/'run.json')
    assert result['executionAttestation']=='NOT_ATTESTED'
    data=json.loads((tmp_path/'run.json').read_text())
    assert data['origin']=='EXTERNAL_UNATTESTED' and len(data['cases'])==1
    assert data['cases'][0]['engineeringStatus']=='FAIL'


@pytest.mark.parametrize('file',['RESPONSE.json','RECEIPT.json','USAGE.json'])
def test_modified_answer_or_receipt_is_rejected_before_publishing(exported,tmp_path,file):
    root,corpus,_,folder=exported
    data=json.loads((folder/file).read_text())
    if file=='RESPONSE.json':data['response']='Rewritten answer'
    else:data['changed']=True
    (folder/file).write_text(json.dumps(data))
    with pytest.raises(ValueError,match='exported_'):
        module.import_answers(root,corpus,tmp_path/'run.json')
    assert not (tmp_path/'run.json').exists()


def test_wrong_selected_model_cannot_be_silently_imported(exported,tmp_path):
    root,corpus,run,_=exported
    run['cases'][0]['modelSnapshot']['modelId']='gpt-5.5'
    (root/'RUN_OUTPUT.json').write_text(json.dumps(run))
    with pytest.raises(ValueError,match='pinned_model_mismatch'):
        module.import_answers(root,corpus,tmp_path/'run.json')


def test_missing_unrun_case_cannot_shrink_the_live_denominator(exported,tmp_path):
    root,corpus,run,_=exported
    run['cases'].pop()
    (root/'RUN_OUTPUT.json').write_text(json.dumps(run))
    with pytest.raises(ValueError,match='incomplete_live_denominator'):
        module.import_answers(root,corpus,tmp_path/'run.json')


def test_declared_pass_cannot_override_a_non_succeeded_task_receipt(exported,tmp_path):
    root,corpus,run,folder=exported
    receipt=json.loads((folder/'RECEIPT.json').read_text())
    receipt.update(taskState='COMPLETED_UNVERIFIED',acceptanceVerified=False)
    (folder/'RECEIPT.json').unlink()
    run['cases'][0].update(engineeringStatus='PASS',receiptSha256=write_new(folder/'RECEIPT.json',receipt))
    (root/'RUN_OUTPUT.json').write_text(json.dumps(run))
    with pytest.raises(ValueError,match='inconsistent_engineering_pass'):
        module.import_answers(root,corpus,tmp_path/'run.json')
    assert not (tmp_path/'run.json').exists()


def test_changed_coordinator_message_is_rejected_before_import(exported,tmp_path):
    root,corpus,run,folder=exported
    observation=folder/'COORDINATOR_001.json'
    original=write_new(observation,{'phase':'semantic_acceptance','responseText':'Synthetic public reason'})
    receipt=json.loads((folder/'RECEIPT.json').read_text())
    receipt['coordinatorObservations']=[{'file':observation.name,'sha256':original}]
    (folder/'RECEIPT.json').unlink()
    run['cases'][0]['receiptSha256']=write_new(folder/'RECEIPT.json',receipt)
    (root/'RUN_OUTPUT.json').write_text(json.dumps(run))
    observation.write_text('{"changed":true}')
    with pytest.raises(ValueError,match='exported_coordinator_changed'):
        module.import_answers(root,corpus,tmp_path/'run.json')
    assert not (tmp_path/'run.json').exists()


@pytest.mark.parametrize('name',['COORDINATOR_001.json','../COORDINATOR_001.json'])
def test_coordinator_binding_accepts_only_safe_bound_files(exported,tmp_path,name):
    root,corpus,run,folder=exported
    digest=write_new(folder/'COORDINATOR_001.json',{'responseText':'Synthetic public reason'})
    receipt=json.loads((folder/'RECEIPT.json').read_text())
    receipt['coordinatorObservations']=[{'file':name,'sha256':digest}]
    (folder/'RECEIPT.json').unlink()
    run['cases'][0]['receiptSha256']=write_new(folder/'RECEIPT.json',receipt)
    (root/'RUN_OUTPUT.json').write_text(json.dumps(run))
    if '/' in name:
        with pytest.raises(ValueError,match='unsafe_coordinator_name'):
            module.import_answers(root,corpus,tmp_path/'run.json')
        assert not (tmp_path/'run.json').exists()
    else:
        assert module.import_answers(root,corpus,tmp_path/'run.json')['status']=='PASS'
