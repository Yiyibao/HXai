import copy
import importlib.util
import json
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location('math_scoring', Path(__file__).parents[1] / 'score_b_math_evaluation.py')
scoring = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scoring)


@pytest.fixture
def evaluation(tmp_path):
    corpus = tmp_path / 'corpus'
    corpus.mkdir()
    prompts = [{'id': f'q{i}', 'category': 'algebra' if i < 3 else 'analysis', 'prompt': f'Prove claim {i}.'} for i in range(4)]
    blind = {'corpusVersion': 2, 'corpusSha256': 'a' * 64, 'cases': prompts}
    reference = {'corpusVersion': 2, 'corpusSha256': 'a' * 64, 'referenceStatus': scoring.REFERENCE_STATUS,
                 'cases': [{'id': p['id'], 'reference': {'status': scoring.REFERENCE_STATUS,
                            'argument': 'Induction is one valid method.', 'obligations': ['base case', 'all indices']}}
                           for p in prompts]}
    artifacts = {name: scoring.write_new(corpus / name, value) for name, value in
                 [('BLIND_PROMPTS.json', blind), ('REFERENCE_RUBRIC.json', reference)]}
    scoring.write_new(corpus / 'CORPUS_SEAL.json', {'status': 'PASS', 'scope': 'QUESTION_REFERENCE_SEPARATION_ONLY',
                      'corpusVersion': 2, 'corpusSha256': 'a' * 64, 'cases': 4, 'artifacts': artifacts})
    run = {'contractVersion': 1, 'origin': 'SYNTHETIC', 'runId': 'regression', 'corpusSha256': 'a' * 64, 'cases': []}
    for i, response in enumerate(['异法🙂：telescoping proves all indices.', 'A false claim.', '']):
        run['cases'].append({'id': f'q{i}', 'promptSha256': scoring.sha(prompts[i]['prompt'].encode()),
            'engineeringStatus': 'FAIL' if i == 0 else ('PASS' if i == 1 else 'BLOCKED'), 'response': response,
            'taskId': f'task-{i}', 'modelSnapshot': {'providerId': 'synthetic', 'endpoint': 'https://example.invalid',
            'modelId': 'synthetic', 'reasoning': 'none', 'configRevision': '1'},
            'receiptSha256': 'b' * 64, 'usageSha256': 'c' * 64})
    run_path = tmp_path / 'run.json'
    scoring.write_new(run_path, run)
    sealed = tmp_path / 'sealed'
    scoring.seal_answers(corpus, run_path, sealed)
    template = tmp_path / 'template.json'
    scoring.template(sealed, corpus, template)
    judgment = json.loads(template.read_text(encoding='utf-8'))
    judgment['evaluator']['id'] = 'synthetic-regression-author'
    return {'root': tmp_path, 'corpus': corpus, 'run': run, 'run_path': run_path, 'sealed': sealed, 'judgment': judgment}


def evaluate(e, judgment=None, output=None):
    path = e['root'] / 'judgment.json'
    path.write_text(json.dumps(judgment or e['judgment'], ensure_ascii=False), encoding='utf-8')
    output = output or e['root'] / 'report.json'
    scoring.score(e['sealed'], e['corpus'], path, output)
    return json.loads(output.read_text(encoding='utf-8'))


def finding(e, case=0, obligation=0, status='MET', reason=None):
    value = e['judgment']['cases'][case]['obligations'][obligation]
    value.update(status=status, reason=reason, rationale='Independent calculation for the synthetic assertion.',
                 verification='Algebraic derivation; no reference-string equality.')
    if status in ('MET', 'VIOLATED'):
        response = e['run']['cases'][case]['response']
        value['evidence'] = {'start': 0, 'end': len(response), 'quote': response}
    return value


def test_unexecuted_and_blocked_cases_remain_in_full_denominator(evaluation):
    result = evaluate(evaluation)
    assert result['totals']['cases'] == 4
    assert result['totals']['mathematics'] == {'CORRECT': 0, 'INCORRECT': 0, 'INCOMPLETE': 0, 'UNDETERMINED': 4}
    assert result['totals']['engineering'] == {'PASS': 1, 'FAIL': 1, 'NOT_RUN': 1, 'BLOCKED': 1}
    assert result['totals']['obligations'] == {'UNDETERMINED': 8}
    assert result['categories']['analysis']['engineering']['NOT_RUN'] == 1


@pytest.mark.parametrize('states,expected', [
    ([('MET', None), ('MET', None)], 'CORRECT'),
    ([('VIOLATED', None), ('UNDETERMINED', 'UNKNOWN')], 'INCORRECT'),
    ([('MET', None), ('UNDETERMINED', 'OMITTED')], 'INCOMPLETE'),
    ([('MET', None), ('UNDETERMINED', 'UNKNOWN')], 'UNDETERMINED'),
    ([('UNDETERMINED', 'OMITTED'), ('UNDETERMINED', 'UNKNOWN')], 'UNDETERMINED'),
])
def test_obligation_conclusions_do_not_follow_engineering_status(evaluation, states, expected):
    for i, (status, reason) in enumerate(states):
        finding(evaluation, obligation=i, status=status, reason=reason)
    result = evaluate(evaluation)
    assert result['cases'][0]['mathematicalResult'] == expected
    assert result['cases'][0]['engineeringStatus'] == 'FAIL'
    assert sum(result['totals']['mathematics'].values()) == 4


def test_correct_alternative_method_and_unicode_code_point_evidence(evaluation):
    for i in range(2):
        value = finding(evaluation, obligation=i)
        value['evidence'] = {'start': 4, 'end': 15, 'quote': 'telescoping'}
    result = evaluate(evaluation)
    assert result['cases'][0]['mathematicalResult'] == 'CORRECT'
    assert result['cases'][0]['obligations'][0]['evidence']['quote'] == 'telescoping'


@pytest.mark.parametrize('case', [2, 3])
def test_no_answer_cannot_be_promoted_to_mathematical_correct(evaluation, case):
    value = evaluation['judgment']['cases'][case]['obligations'][0]
    value.update(status='MET', reason=None, evidence={'start': 0, 'end': 1, 'quote': 'x'})
    with pytest.raises(ValueError, match='^no_answer_to_assess$'):
        evaluate(evaluation)
    assert not (evaluation['root'] / 'report.json').exists()


@pytest.mark.parametrize('change,code', [
    (lambda j: j['cases'].pop(), 'incomplete_case_set'),
    (lambda j: j['cases'].__setitem__(1, copy.deepcopy(j['cases'][0])), 'unknown_or_duplicate_case'),
    (lambda j: j['cases'][0]['obligations'].pop(), 'incomplete_obligations'),
    (lambda j: j['cases'][0]['obligations'][1].update(index=0), 'obligation_identity_mismatch'),
    (lambda j: j['cases'][0]['obligations'][0].update(text='easier condition'), 'obligation_identity_mismatch'),
    (lambda j: j.update(answerSealSha256='0' * 64), 'judgment_binding_mismatch'),
    (lambda j: j['cases'][0].update(responseSha256='0' * 64), 'answer_digest_mismatch'),
])
def test_omissions_or_cross_run_assessments_cannot_shrink_or_replace_obligations(evaluation, change, code):
    change(evaluation['judgment'])
    with pytest.raises(ValueError, match='^' + code + '$'):
        evaluate(evaluation)


@pytest.mark.parametrize('evidence,code', [
    ({'start': 0, 'end': 1, 'quote': 'wrong'}, 'evidence_quote_mismatch'),
    ({'start': True, 'end': 1, 'quote': '异'}, 'invalid_evidence_range'),
    ({'start': 0, 'end': 9999, 'quote': 'x'}, 'invalid_evidence_range'),
    ({'start': 1, 'end': 1, 'quote': ''}, 'invalid_evidence_range'),
])
def test_false_or_invalid_answer_locations_rejected(evaluation, evidence, code):
    finding(evaluation)['evidence'] = evidence
    with pytest.raises(ValueError, match='^' + code + '$'):
        evaluate(evaluation)


@pytest.mark.parametrize('filename', ['RUN_INPUT.json', 'BLIND_PROMPTS.json'])
def test_changed_sealed_bytes_not_assessed(evaluation, filename):
    path = evaluation['sealed'] / filename
    path.write_bytes(path.read_bytes() + b' ')
    with pytest.raises(ValueError, match='sealed_artifact_changed|blind_digest_mismatch'):
        evaluate(evaluation)


def test_reference_cannot_be_changed_after_answers_are_sealed(evaluation):
    path = evaluation['corpus'] / 'REFERENCE_RUBRIC.json'
    path.write_bytes(path.read_bytes() + b' ')
    with pytest.raises(ValueError, match='^reference_digest_mismatch$'):
        evaluate(evaluation)
    seal_path = evaluation['corpus'] / 'CORPUS_SEAL.json'
    seal = json.loads(seal_path.read_text())
    seal['artifacts']['REFERENCE_RUBRIC.json'] = scoring.sha(path.read_bytes())
    seal_path.write_text(json.dumps(seal))
    with pytest.raises(ValueError, match='^sealed_corpus_changed$'):
        evaluate(evaluation)


def test_answer_sealing_does_not_read_reference(evaluation):
    (evaluation['corpus'] / 'REFERENCE_RUBRIC.json').unlink()
    result = scoring.seal_answers(evaluation['corpus'], evaluation['run_path'], evaluation['root'] / 'no-reference-seal')
    assert result['cases'] == 4


@pytest.mark.parametrize('change,code', [
    (lambda run: run['cases'][0].update(promptSha256='0' * 64), 'prompt_digest_mismatch'),
    (lambda run: run['cases'].__setitem__(1, copy.deepcopy(run['cases'][0])), 'unknown_or_duplicate_case'),
    (lambda run: run['cases'][0].update(id='extra'), 'unknown_or_duplicate_case'),
    (lambda run: run['cases'][0].update(modelSnapshot=None), 'invalid_fields'),
    (lambda run: run['cases'][0].update(engineeringStatus='NOT_RUN'), 'not_run_has_execution'),
])
def test_invalid_exports_rejected_before_seal_creation(evaluation, change, code):
    change(evaluation['run'])
    evaluation['run_path'].write_text(json.dumps(evaluation['run']))
    target = evaluation['root'] / 'invalid-seal'
    with pytest.raises(ValueError, match='^' + code + '$'):
        scoring.seal_answers(evaluation['corpus'], evaluation['run_path'], target)
    assert not target.exists()


def test_existing_report_and_answer_input_are_preserved(evaluation):
    output = evaluation['root'] / 'existing.json'
    output.write_bytes(b'previous evaluation receipt')
    before = {p: p.read_bytes() for p in evaluation['sealed'].iterdir()}
    with pytest.raises(FileExistsError):
        evaluate(evaluation, output=output)
    assert output.read_bytes() == b'previous evaluation receipt'
    assert all(p.read_bytes() == raw for p, raw in before.items())


def test_synthetic_or_declared_independent_inputs_do_not_attest_execution(evaluation):
    evaluation['judgment']['evaluator']['relationship'] = 'INDEPENDENT_DECLARED'
    result = evaluate(evaluation)
    assert result['origin'] == 'SYNTHETIC'
    assert result['executionAttestation'] == 'NOT_ATTESTED'
    assert result['independentReview'] == 'NOT_EVALUATED'
    assert result['taskCompletion'] == 'NOT_EVALUATED'


@pytest.mark.parametrize('raw,code', [(b'{"x":1,"x":2}', 'duplicate_json_key'),
                                       (b'{"x":NaN}', 'nonfinite_json'), (b'[]', 'invalid_json_object')])
def test_ambiguous_json_not_accepted_as_evidence(tmp_path, raw, code):
    path = tmp_path / 'input.json'
    path.write_bytes(raw)
    with pytest.raises(ValueError, match='^' + code + '$'):
        scoring.load(path)
