"""Bind explicit local native snapshots to the existing blind math corpus.

No model execution, credentials, reference answers or task writes. Exported hashes
are byte bindings, not provider/authorization/independent-review attestations.
"""
import argparse
import json
from pathlib import Path
import sys

import score_b_math_evaluation as scoring


def prompt_text(goal):
    if not goal.lstrip().startswith('['):
        return goal
    parts = json.loads(goal, object_pairs_hook=scoring.unique_object,
                       parse_constant=lambda _: scoring.require(False, 'nonfinite_json'))
    scoring.require(isinstance(parts, list) and parts and all(isinstance(p, dict) and p.get('type') == 'text'
                    and isinstance(p.get('text'), str) for p in parts), 'blind_prompt_must_be_text_only')
    return '\n'.join(p['text'] for p in parts)


def convert(snapshot, expected_prompt, engineering):
    scoring.require(snapshot.get('contractVersion') == 1 and type(snapshot.get('contractVersion')) is int
                    and snapshot.get('kind') == 'hxai_agent_evaluation_snapshot'
                    and snapshot.get('scope') == 'NATIVE_PERSISTED_TASK_AND_SELECTED_VISIBLE_ANSWER', 'unsupported_native_snapshot')
    evidence = snapshot['evidence']
    task = evidence['task']
    scoring.require(isinstance(task, dict) and isinstance(evidence.get('plans'), list)
                    and len(evidence['plans']) == 1, 'missing_native_goal')
    goal = evidence['plans'][0]['goal']
    scoring.require(prompt_text(goal) == expected_prompt and task['intentSha256'] == scoring.sha(goal.encode('utf-8')),
                    'native_prompt_mismatch')
    scoring.require(not task['requestId'].startswith('role:'), 'auxiliary_is_not_main_chat')
    for key in ('id', 'owner', 'conversationId', 'providerId', 'modelId', 'reasoning', 'configRevision'):
        scoring.text(task[key], 2000)
    answer = snapshot['answer']
    messages = answer['messages']
    scoring.require(isinstance(messages, list) and len(messages) <= 200, 'invalid_native_answer')
    ids = set()
    for message in messages:
        scoring.text(message['id'], 200)
        scoring.require(message['id'] not in ids and isinstance(message['text'], str)
                        and message['textSha256'] == scoring.sha(message['text'].encode('utf-8')), 'native_answer_binding_mismatch')
        ids.add(message['id'])
    response = '\n'.join(m['text'] for m in messages if m['text'])
    scoring.require(len(response) <= 200000, 'invalid_response')
    scoring.require(engineering in ('PASS', 'FAIL', 'BLOCKED'), 'invalid_engineering_status')
    calls = evidence['calls']
    scoring.require(isinstance(calls, list) and len(calls) <= 1000, 'invalid_native_calls')
    scoring.require(len({c['call_id'] for c in calls}) == len(calls), 'duplicate_native_call')
    usage = evidence['usage']
    scoring.require(usage['owner'] == task['owner'] and usage['taskId'] == task['id']
                    and usage['groupBy'] == 'requests' and int(usage['recordCount']) == len(usage['records'])
                    and int(usage['summary']['requestCount']) == len(usage['records']), 'native_usage_scope_mismatch')
    if engineering == 'PASS':
        scoring.require(task['state'] == 'SUCCEEDED' and evidence['plans'][0]['acceptance_verified'] == '1'
                        and answer['status'] == 'SELECTED_BRANCH_SNAPSHOT' and response.strip()
                        and all(c['state'] not in ('IN_FLIGHT', 'UNKNOWN', 'PREPARED') for c in calls),
                        'native_execution_not_complete')
    # The native export deliberately does not decrypt configuration or include an endpoint.
    # Keep unavailable metadata explicit, never fill it from live Settings.
    return {'response': response, 'taskId': task['id'], 'modelSnapshot': {
        'providerId': task['providerId'], 'endpoint': 'NOT_EXPORTED_FROM_ACCEPTED_CONFIGURATION',
        'modelId': task['modelId'], 'reasoning': task['reasoning'], 'configRevision': task['configRevision']}}


def import_snapshots(corpus_dir, mapping_path, output):
    _, blind, _, _ = scoring.corpus(corpus_dir)  # Does not open REFERENCE_RUBRIC.json.
    mapping, mapping_raw = scoring.load(mapping_path)
    scoring.keys(mapping, 'contractVersion origin runId corpusSha256 cases')
    scoring.require(type(mapping['contractVersion']) is int and mapping['contractVersion'] == 1, 'unsupported_mapping')
    scoring.require(mapping['origin'] in ('SYNTHETIC', 'EXTERNAL_UNATTESTED'), 'invalid_origin')
    scoring.text(mapping['runId'], 100)
    scoring.require(mapping['corpusSha256'] == blind['corpusSha256'], 'run_corpus_mismatch')
    prompts = {p['id']: p for p in blind['cases']}
    rows = scoring.indexed(mapping['cases'], set(prompts), complete=False)
    run = {**{k: mapping[k] for k in ('contractVersion', 'origin', 'runId', 'corpusSha256')}, 'cases': []}
    copied = []
    task_ids, owners, selections = set(), set(), set()
    total_bytes = 0
    for ordinal, (case_id, row) in enumerate(rows.items()):
        scoring.keys(row, 'id snapshot snapshotSha256 engineeringStatus')
        scoring.text(row['snapshot'], 500)
        relative = Path(row['snapshot'])
        root = Path(mapping_path).resolve().parent
        path = root / relative
        scoring.require(not relative.is_absolute() and '..' not in relative.parts and not path.is_symlink()
                        and path.resolve().is_relative_to(root), 'invalid_snapshot_path')
        snapshot, raw = scoring.load(path)
        total_bytes += len(raw)
        scoring.require(total_bytes <= 64 * 1024 * 1024, 'snapshot_batch_size_limit')
        scoring.require(scoring.sha(raw) == scoring.hash_value(row['snapshotSha256']), 'snapshot_digest_mismatch')
        data = convert(snapshot, prompts[case_id]['prompt'], row['engineeringStatus'])
        task = snapshot['evidence']['task']
        scoring.require(data['taskId'] not in task_ids, 'task_reused_for_different_case')
        task_ids.add(data['taskId']); owners.add(task['owner'])
        selections.add(tuple(data['modelSnapshot'][k] for k in ('providerId', 'modelId', 'reasoning')))
        scoring.require(len(owners) == 1 and len(selections) == 1, 'mixed_owner_or_locked_model')
        usage_raw = scoring.encoded(snapshot['evidence']['usage'])
        run['cases'].append({'id': case_id, 'promptSha256': scoring.sha(prompts[case_id]['prompt'].encode('utf-8')),
            'engineeringStatus': row['engineeringStatus'], **data, 'receiptSha256': scoring.sha(raw), 'usageSha256': scoring.sha(usage_raw)})
        copied.append((f'{ordinal:03d}-snapshot.json', raw))
        copied.append((f'{ordinal:03d}-usage.json', usage_raw))
    scoring.answers(run, blind)  # All missing cases remain NOT_RUN in the existing sealing flow.
    output = Path(output); output.mkdir(parents=True, exist_ok=False)
    bindings = {}
    for name, raw in [('MAPPING_INPUT.json', mapping_raw), *copied]:
        with (output / name).open('xb') as stream:
            stream.write(raw)
        bindings[name] = scoring.sha(raw)
    bindings['RUN_INPUT.json'] = scoring.write_new(output / 'RUN_INPUT.json', run)
    scoring.write_new(output / 'IMPORT_SEAL.json', {'contractVersion': 1, 'scope': 'OFFLINE_NATIVE_SNAPSHOT_BYTE_BINDING_ONLY',
        'corpusSha256': blind['corpusSha256'], 'importedCases': len(rows), 'totalCases': len(prompts), 'artifacts': bindings,
        'executionAttestation': 'NOT_ATTESTED', 'modelQuality': 'NOT_EVALUATED', 'independentReview': 'NOT_EVALUATED',
        'endpoint': 'NOT_EXPORTED', 'toolOutputs': 'HASH_REFERENCES_ONLY'})
    return {'status': 'PASS', 'scope': 'IMPORT_ONLY', 'importedCases': len(rows), 'totalCases': len(prompts)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(import_snapshots(args.corpus, args.mapping, args.out)))
    except (OSError, ValueError, KeyError, TypeError, RecursionError):
        print(json.dumps({'status': 'FAIL', 'errorCode': 'invalid_native_evaluation_input'}), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
