import copy
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import import_agent_evaluation as importer
import score_b_math_evaluation as scoring
from prepare_b_math_evaluation import prepare


def native(prompt='证明原题🙂'):
    goal = json.dumps([{'type': 'text', 'text': prompt}], ensure_ascii=False)
    return {'contractVersion': 1, 'kind': 'hxai_agent_evaluation_snapshot',
            'scope': 'NATIVE_PERSISTED_TASK_AND_SELECTED_VISIBLE_ANSWER', 'endpoint': 'NOT_EXPORTED',
            'evidence': {'task': {'id': 'task', 'owner': 'owner', 'conversationId': 'conversation', 'requestId': 'main-request',
                'providerId': 'accepted-provider', 'modelId': 'accepted-model', 'reasoning': 'HIGH', 'configRevision': 'accepted-config',
                'state': 'SUCCEEDED', 'intentSha256': scoring.sha(goal.encode())},
                'plans': [{'goal': goal, 'acceptance_verified': '1'}], 'calls': [],
                'usage': {'owner': 'owner', 'taskId': 'task', 'groupBy': 'requests', 'recordCount': '1', 'summary': {'requestCount': '1'},
                    'records': [{'requestId': 'request', 'provenance': 'unknown', 'tokens': {'input': None, 'output': None}}]}},
            'answer': {'status': 'SELECTED_BRANCH_SNAPSHOT', 'messages': [{'id': 'message', 'text': '另一正确解法🙂', 'textSha256': scoring.sha('另一正确解法🙂'.encode())}]}}


def test_native_acceptance_model_and_unknown_usage_are_preserved_without_endpoint_guess():
    snapshot = native();original = copy.deepcopy(snapshot)
    answer = importer.convert(snapshot, '证明原题🙂', 'PASS')
    assert answer['response'] == '另一正确解法🙂'
    assert answer['modelSnapshot']['reasoning'] == 'HIGH'
    assert answer['modelSnapshot']['endpoint'] == 'NOT_EXPORTED_FROM_ACCEPTED_CONFIGURATION'
    assert snapshot == original


@pytest.mark.parametrize('mutation,code', [
    (lambda n: n['evidence']['task'].update(intentSha256='a'*64), 'native_prompt_mismatch'),
    (lambda n: n['evidence']['task'].update(state='UNCERTAIN_SIDE_EFFECT'), 'native_execution_not_complete'),
    (lambda n: n['evidence']['plans'][0].update(acceptance_verified='0'), 'native_execution_not_complete'),
    (lambda n: n['evidence']['calls'].append({'call_id': 'unknown', 'state': 'UNKNOWN'}), 'native_execution_not_complete'),
    (lambda n: n['answer']['messages'][0].update(text='swapped'), 'native_answer_binding_mismatch'),
    (lambda n: n['evidence']['task'].update(requestId='role:title:request'), 'auxiliary_is_not_main_chat'),
    (lambda n: n['evidence']['usage'].update(taskId='foreign'), 'native_usage_scope_mismatch'),
    (lambda n: n['evidence']['usage'].update(recordCount='0'), 'native_usage_scope_mismatch'),
])
def test_fake_success_and_wrong_bindings_rejected(mutation, code):
    snapshot = native();mutation(snapshot)
    with pytest.raises(ValueError, match='^'+code+'$'):
        importer.convert(snapshot, '证明原题🙂', 'PASS')


def test_prompt_change_and_attachment_context_cannot_be_mislabeled_blind_case():
    with pytest.raises(ValueError, match='native_prompt_mismatch'):
        importer.convert(native(), 'different prompt', 'PASS')
    snapshot = native();snapshot['evidence']['plans'][0]['goal'] = '[{"type":"image","url":"private"}]'
    with pytest.raises(ValueError, match='blind_prompt_must_be_text_only'):
        importer.convert(snapshot, '证明原题🙂', 'PASS')


def test_blocked_partial_answer_preserved_and_not_promoted_to_math_failure():
    snapshot = native();snapshot['evidence']['task']['state'] = 'UNCERTAIN_SIDE_EFFECT'
    assert importer.convert(snapshot, '证明原题🙂', 'BLOCKED')['response'] == '另一正确解法🙂'


def test_native_export_import_seal_template_preserve_all_32_cases(tmp_path):
    corpus = tmp_path / 'corpus';prepare(corpus)
    blind = json.loads((corpus / 'BLIND_PROMPTS.json').read_text(encoding='utf-8'))
    case = blind['cases'][0];snapshot = native(case['prompt'])
    native_path = tmp_path / 'native.json';digest = scoring.write_new(native_path, snapshot)
    mapping = {'contractVersion': 1, 'origin': 'SYNTHETIC', 'runId': 'synthetic-native-binding',
        'corpusSha256': blind['corpusSha256'], 'cases': [{'id': case['id'], 'snapshot': native_path.name,
            'snapshotSha256': digest, 'engineeringStatus': 'PASS'}]}
    mapping_path = tmp_path / 'mapping.json';scoring.write_new(mapping_path, mapping)
    # Import/sealing must be possible without ever opening the reference file.
    reference = corpus / 'REFERENCE_RUBRIC.json';reference.rename(corpus / 'REFERENCE_HELD_BACK.json')
    output = tmp_path / 'import';result = importer.import_snapshots(corpus, mapping_path, output)
    assert result['totalCases'] == 32 and result['importedCases'] == 1
    run = json.loads((output / 'RUN_INPUT.json').read_text(encoding='utf-8'))
    assert run['cases'][0]['receiptSha256'] == digest
    assert scoring.sha((output / '000-usage.json').read_bytes()) == run['cases'][0]['usageSha256']
    scoring.seal_answers(corpus, output / 'RUN_INPUT.json', tmp_path / 'answers')
    (corpus / 'REFERENCE_HELD_BACK.json').rename(reference)
    scoring.template(tmp_path / 'answers', corpus, tmp_path / 'judgments.json')
    assert len(json.loads((tmp_path / 'judgments.json').read_text(encoding='utf-8'))['cases']) == 32
    seal = json.loads((output / 'IMPORT_SEAL.json').read_text(encoding='utf-8'))
    assert seal['executionAttestation'] == 'NOT_ATTESTED' and seal['independentReview'] == 'NOT_EVALUATED'
    with pytest.raises(FileExistsError):
        importer.import_snapshots(corpus, mapping_path, output)
