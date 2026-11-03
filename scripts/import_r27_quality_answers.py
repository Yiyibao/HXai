"""Validate exported public Chat byte bindings before using the existing offline scorer.

The input's execution-origin string is not an authentication mechanism. Device/package
and instrumentation evidence must be assessed separately. This tool never calls a model.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

from score_b_math_evaluation import load, write_new, require, corpus, answers


def import_answers(export, corpus_dir, output):
    export=Path(export)
    run,_=load(export/'RUN_OUTPUT.json')
    require(run.get('contractVersion')==1 and run.get('origin')=='ACTUAL_ORIGINAL_ANDROID_CHAT','unexpected_run_shape')
    seal,blind,_,_=corpus(corpus_dir)
    math_ids={row['id'] for row in blind['cases']}
    expected_ids=math_ids|{'AGENT_CHAT_ZERO_TOOLS','AGENT_EXACT_FACT','AGENT_FALSE_COMPLETION','AGENT_TWO_ARTIFACTS','AGENT_MISSING_INPUT'}
    rows=run.get('cases')
    require(isinstance(rows,list) and len(rows)==len(expected_ids),'incomplete_live_denominator')
    require(len({row.get('id') for row in rows})==len(rows) and {row.get('id') for row in rows}==expected_ids,'unknown_or_duplicate_case')
    result=[]
    for row in rows:
        identifier=row['id']
        require(identifier in expected_ids and '/' not in identifier and '\\' not in identifier,'unsafe_case_identity')
        require(row.get('engineeringStatus') in {'PASS','FAIL','NOT_RUN','BLOCKED'},'invalid_engineering_status')
        if row['engineeringStatus']=='NOT_RUN':
            require(row.get('response')=='','not_run_has_response')
            continue  # The old scorer restores all missing math cases as NOT_RUN.
        folder=export/identifier
        snapshot=row.get('modelSnapshot')
        require(isinstance(snapshot,dict) and snapshot.get('modelId')=='gpt-6.1-terra' and
                snapshot.get('reasoning')=='MEDIUM' and snapshot.get('endpoint')=='https://xinyue.mom/v1','pinned_model_mismatch')
        for name,key in [('RECEIPT.json','receiptSha256'),('USAGE.json','usageSha256')]:
            _,raw=load(folder/name)
            require(hashlib.sha256(raw).hexdigest()==row.get(key),'exported_receipt_or_usage_changed')
        response,_=load(folder/'RESPONSE.json')
        require(response.get('response')==row.get('response') and response.get('responseSha256')==hashlib.sha256(row['response'].encode()).hexdigest(),'exported_answer_changed')
        receipt,_=load(folder/'RECEIPT.json')
        require(receipt.get('id')==identifier and receipt.get('taskId')==row.get('taskId'),'receipt_task_mismatch')
        if row['engineeringStatus']=='PASS':
            require(receipt.get('taskState')=='SUCCEEDED' and receipt.get('acceptanceVerified') is True,
                    'inconsistent_engineering_pass')
        observations=receipt.get('coordinatorObservations',[])
        require(isinstance(observations,list) and len(observations)<=64,'invalid_coordinator_observations')
        seen=set()
        for observation in observations:
            require(isinstance(observation,dict),'invalid_coordinator_observation')
            name=observation.get('file')
            require(isinstance(name,str) and re.fullmatch(r'COORDINATOR_[0-9]{3}\.json',name) is not None
                    and name not in seen,'unsafe_coordinator_name')
            seen.add(name)
            path=folder/name
            require(not path.is_symlink() and path.resolve().parent==folder.resolve(),'unsafe_coordinator_path')
            require(path.is_file() and path.stat().st_size<=262144,'coordinator_limit_or_missing')
            require(hashlib.sha256(path.read_bytes()).hexdigest()==observation.get('sha256'),'exported_coordinator_changed')
        for artifact in receipt.get('artifacts',[]):
            name=artifact['file']
            require(Path(name).name==name and '/' not in name and '\\' not in name,'unsafe_artifact_name')
            path=folder/name
            require(path.stat().st_size<=30*1024*1024,'artifact_limit')
            require(hashlib.sha256(path.read_bytes()).hexdigest()==artifact.get('sha256'),'exported_artifact_changed')
        if identifier in math_ids:result.append(row)
    prepared={'contractVersion':1,'origin':'EXTERNAL_UNATTESTED','runId':run['runId'],
              'corpusSha256':seal['corpusSha256'],'cases':result}
    answers(prepared,blind)
    write_new(output,prepared)
    return {'status':'PASS','scope':'EXPORTED_PUBLIC_BYTE_BINDINGS_ONLY','executedMathCases':len(result),
            'mathDenominator':len(math_ids),'executionAttestation':'NOT_ATTESTED'}


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--export',type=Path,required=True)
    p.add_argument('--corpus',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();print(json.dumps(import_answers(a.export,a.corpus,a.out)))


if __name__=='__main__':main()
