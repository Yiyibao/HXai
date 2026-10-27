"""Seal visible Chat answers before offline, human obligation assessment.

No provider, credentials, network, model calls or task-state writes. Hashes bind bytes,
not authenticity, authorization, chronology or independent mathematical correctness.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

MAX_BYTES = 16 * 1024 * 1024
REFERENCE_STATUS = 'AUTHOR_DERIVED_NOT_INDEPENDENTLY_REVIEWED'
ENGINEERING = ('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED')
MATHEMATICS = ('CORRECT', 'INCORRECT', 'INCOMPLETE', 'UNDETERMINED')


def require(condition, code):
    if not condition:
        raise ValueError(code)


def keys(value, expected):
    require(isinstance(value, dict) and set(value) == set(expected.split()), 'invalid_fields')


def text(value, maximum=12000):
    require(isinstance(value, str) and bool(value.strip()) and len(value) <= maximum, 'invalid_text')
    return value


def sha(data):
    return hashlib.sha256(data).hexdigest()


def hash_value(value):
    require(isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value), 'invalid_sha256')
    return value


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate_json_key')
        result[key] = value
    return result


def load(path):
    with Path(path).open('rb') as stream:
        raw = stream.read(MAX_BYTES + 1)
    require(len(raw) <= MAX_BYTES, 'file_size_limit')
    value = json.loads(raw, object_pairs_hook=unique_object,
                       parse_constant=lambda _: require(False, 'nonfinite_json'))
    require(isinstance(value, dict), 'invalid_json_object')
    return value, raw


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8')


def write_new(path, value):
    raw = encoded(value)
    with Path(path).open('xb') as stream:
        stream.write(raw)
    return sha(raw)


def corpus(directory, with_reference=False):
    directory = Path(directory)
    seal, seal_raw = load(directory / 'CORPUS_SEAL.json')
    blind, blind_raw = load(directory / 'BLIND_PROMPTS.json')
    require(type(seal.get('corpusVersion')) is int and seal['corpusVersion'] == 2
            and seal.get('status') == 'PASS' and seal.get('scope') == 'QUESTION_REFERENCE_SEPARATION_ONLY', 'unsupported_corpus')
    hash_value(seal.get('corpusSha256'))
    require(type(blind.get('corpusVersion')) is int and blind['corpusVersion'] == 2
            and blind.get('corpusSha256') == seal['corpusSha256'], 'corpus_binding_mismatch')
    artifacts = seal.get('artifacts')
    keys(artifacts, 'BLIND_PROMPTS.json REFERENCE_RUBRIC.json')
    for value in artifacts.values():
        hash_value(value)
    require(sha(blind_raw) == artifacts['BLIND_PROMPTS.json'], 'blind_digest_mismatch')
    rows = blind.get('cases')
    require(isinstance(rows, list) and 1 <= len(rows) <= 200
            and type(seal.get('cases')) is int and seal['cases'] == len(rows), 'invalid_corpus_cases')
    ids = set()
    for row in rows:
        keys(row, 'id category prompt')
        text(row['id'], 100)
        text(row['category'], 100)
        text(row['prompt'])
        require(row['id'] not in ids, 'duplicate_case_id')
        ids.add(row['id'])
    rubric = None
    if with_reference:
        rubric, raw = load(directory / 'REFERENCE_RUBRIC.json')
        require(sha(raw) == artifacts['REFERENCE_RUBRIC.json'], 'reference_digest_mismatch')
        require(type(rubric.get('corpusVersion')) is int and rubric['corpusVersion'] == 2
                and rubric.get('corpusSha256') == seal['corpusSha256']
                and rubric.get('referenceStatus') == REFERENCE_STATUS, 'reference_binding_mismatch')
        references = indexed(rubric.get('cases'), ids)
        for row in references.values():
            reference = row.get('reference')
            require(isinstance(reference, dict) and reference.get('status') == REFERENCE_STATUS, 'invalid_reference')
            text(reference.get('argument'))
            obligations = reference.get('obligations')
            require(isinstance(obligations, list) and 1 <= len(obligations) <= 100, 'invalid_obligations')
            for obligation in obligations:
                text(obligation)
        rubric = references
    return seal, blind, rubric, {'CORPUS_SEAL.json': seal_raw, 'BLIND_PROMPTS.json': blind_raw}


def indexed(rows, expected, complete=True):
    require(isinstance(rows, list) and len(rows) <= len(expected), 'invalid_case_set')
    result = {}
    for row in rows:
        require(isinstance(row, dict), 'invalid_case')
        case_id = text(row.get('id'), 100)
        require(case_id in expected and case_id not in result, 'unknown_or_duplicate_case')
        result[case_id] = row
    require(not complete or set(result) == set(expected), 'incomplete_case_set')
    return result


def answers(run, blind):
    keys(run, 'contractVersion origin runId corpusSha256 cases')
    require(type(run['contractVersion']) is int and run['contractVersion'] == 1, 'unsupported_contract')
    require(run['origin'] in ('SYNTHETIC', 'EXTERNAL_UNATTESTED'), 'invalid_origin')
    text(run['runId'], 100)
    require(run['corpusSha256'] == blind['corpusSha256'], 'run_corpus_mismatch')
    submitted = indexed(run['cases'], {row['id'] for row in blind['cases']}, complete=False)
    result = {}
    for prompt in blind['cases']:
        row = submitted.get(prompt['id'])
        prompt_hash = sha(prompt['prompt'].encode('utf-8'))
        if row is None:
            row = {'id': prompt['id'], 'promptSha256': prompt_hash, 'engineeringStatus': 'NOT_RUN',
                   'response': '', 'taskId': None, 'modelSnapshot': None, 'receiptSha256': None, 'usageSha256': None}
        keys(row, 'id promptSha256 engineeringStatus response taskId modelSnapshot receiptSha256 usageSha256')
        require(row['promptSha256'] == prompt_hash, 'prompt_digest_mismatch')
        require(row['engineeringStatus'] in ENGINEERING, 'invalid_engineering_status')
        require(isinstance(row['response'], str) and len(row['response']) <= 200000, 'invalid_response')
        if row['engineeringStatus'] == 'NOT_RUN':
            require(row['response'] == '' and all(row[k] is None for k in ('taskId', 'modelSnapshot', 'receiptSha256', 'usageSha256')), 'not_run_has_execution')
        else:
            text(row['taskId'], 200)
            snapshot = row['modelSnapshot']
            keys(snapshot, 'providerId endpoint modelId reasoning configRevision')
            for value in snapshot.values():
                text(value, 2000)
            hash_value(row['receiptSha256'])
            hash_value(row['usageSha256'])
        result[row['id']] = {**row, 'responseSha256': sha(row['response'].encode('utf-8'))}
    return result


def seal_answers(corpus_dir, run_path, output):
    seal, blind, _, files = corpus(corpus_dir)  # Deliberately never opens REFERENCE_RUBRIC.
    run, raw = load(run_path)
    rows = answers(run, blind)
    files['RUN_INPUT.json'] = raw
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    digests = {}
    for name, content in files.items():
        with (output / name).open('xb') as stream:
            stream.write(content)
        digests[name] = sha(content)
    return_hash = write_new(output / 'ANSWER_SEAL.json', {
        'contractVersion': 1, 'scope': 'OFFLINE_BYTE_BINDING_ONLY', 'origin': run['origin'],
        'runId': run['runId'], 'corpusSha256': seal['corpusSha256'], 'cases': len(rows),
        'artifacts': digests, 'referenceSha256': seal['artifacts']['REFERENCE_RUBRIC.json'],
        'executionAttestation': 'NOT_ATTESTED', 'authorization': 'NOT_GRANTED',
        'independentReview': 'NOT_EVALUATED'})
    return {'status': 'PASS', 'scope': 'ANSWER_SEAL_ONLY', 'cases': len(rows), 'answerSealSha256': return_hash}


def read_sealed(directory, corpus_dir):
    directory = Path(directory)
    seal, raw = load(directory / 'ANSWER_SEAL.json')
    require(type(seal.get('contractVersion')) is int and seal['contractVersion'] == 1
            and seal.get('scope') == 'OFFLINE_BYTE_BINDING_ONLY', 'unsupported_answer_seal')
    artifacts = seal.get('artifacts')
    keys(artifacts, 'CORPUS_SEAL.json BLIND_PROMPTS.json RUN_INPUT.json')
    original, blind, _, files = corpus(directory)
    run, run_raw = load(directory / 'RUN_INPUT.json')
    files['RUN_INPUT.json'] = run_raw
    for name, content in files.items():
        require(sha(content) == hash_value(artifacts[name]), 'sealed_artifact_changed')
    current, current_blind, references, _ = corpus(corpus_dir, with_reference=True)
    require(current['artifacts'] == original['artifacts'] and current_blind == blind
            and current['corpusSha256'] == original['corpusSha256'] == seal.get('corpusSha256')
            and current['artifacts']['REFERENCE_RUBRIC.json'] == seal.get('referenceSha256'), 'sealed_corpus_changed')
    rows = answers(run, blind)
    require(seal.get('origin') == run['origin'] and seal.get('runId') == run['runId']
            and type(seal.get('cases')) is int and seal['cases'] == len(rows), 'answer_seal_mismatch')
    return seal, sha(raw), blind, references, rows

def template(sealed_dir, corpus_dir, output):
    seal, seal_hash, blind, references, rows = read_sealed(sealed_dir, corpus_dir)
    value = {'contractVersion': 1, 'answerSealSha256': seal_hash, 'referenceSha256': seal['referenceSha256'],
             'evaluator': {'id': None, 'relationship': 'SAME_EXECUTOR'}, 'cases': []}
    for prompt in blind['cases']:
        case_id = prompt['id']
        value['cases'].append({'id': case_id, 'responseSha256': rows[case_id]['responseSha256'],
            'obligations': [{'index': i, 'text': obligation, 'status': 'UNDETERMINED', 'reason': 'UNKNOWN',
                             'evidence': None, 'rationale': 'Not assessed.', 'verification': 'Not assessed.'}
                            for i, obligation in enumerate(references[case_id]['reference']['obligations'])]})
    write_new(output, value)
    return {'status': 'PASS', 'scope': 'UNASSESSED_TEMPLATE_ONLY', 'cases': len(rows)}


def assess(obligation, expected, index, answer):
    keys(obligation, 'index text status reason evidence rationale verification')
    require(type(obligation['index']) is int and obligation['index'] == index
            and obligation['text'] == expected, 'obligation_identity_mismatch')
    text(obligation['rationale'])
    text(obligation['verification'])
    status = obligation['status']
    if status in ('MET', 'VIOLATED'):
        require(answer['engineeringStatus'] != 'NOT_RUN' and bool(answer['response'].strip()), 'no_answer_to_assess')
        require(obligation['reason'] is None, 'invalid_assessment_reason')
        evidence = obligation['evidence']
        keys(evidence, 'start end quote')
        start, end = evidence['start'], evidence['end']
        require(type(start) is int and type(end) is int and 0 <= start < end <= len(answer['response']), 'invalid_evidence_range')
        text(evidence['quote'], 200000)
        require(answer['response'][start:end] == evidence['quote'], 'evidence_quote_mismatch')
    else:
        require(status == 'UNDETERMINED' and obligation['reason'] in ('UNKNOWN', 'OMITTED')
                and obligation['evidence'] is None, 'invalid_undetermined_assessment')
        require(obligation['reason'] != 'OMITTED' or bool(answer['response'].strip()), 'no_answer_to_assess')
    return obligation


def score(sealed_dir, corpus_dir, judgment_path, output):
    seal, seal_hash, blind, references, rows = read_sealed(sealed_dir, corpus_dir)
    judgment, judgment_raw = load(judgment_path)
    keys(judgment, 'contractVersion answerSealSha256 referenceSha256 evaluator cases')
    require(type(judgment['contractVersion']) is int and judgment['contractVersion'] == 1, 'unsupported_contract')
    require(judgment['answerSealSha256'] == seal_hash and judgment['referenceSha256'] == seal['referenceSha256'], 'judgment_binding_mismatch')
    evaluator = judgment['evaluator']
    keys(evaluator, 'id relationship')
    text(evaluator['id'], 200)
    require(evaluator['relationship'] in ('SAME_EXECUTOR', 'INDEPENDENT_DECLARED'), 'invalid_evaluator')
    judgments = indexed(judgment['cases'], set(rows))
    results = []
    for prompt in blind['cases']:
        case_id = prompt['id']
        answer, decision = rows[case_id], judgments[case_id]
        keys(decision, 'id responseSha256 obligations')
        require(decision['responseSha256'] == answer['responseSha256'], 'answer_digest_mismatch')
        expected = references[case_id]['reference']['obligations']
        actual = decision['obligations']
        require(isinstance(actual, list) and len(actual) == len(expected), 'incomplete_obligations')
        findings = [assess(value, expected[i], i, answer) for i, value in enumerate(actual)]
        if any(f['status'] == 'VIOLATED' for f in findings):
            conclusion = 'INCORRECT'
        elif any(f['reason'] == 'UNKNOWN' for f in findings):
            conclusion = 'UNDETERMINED'
        elif any(f['reason'] == 'OMITTED' for f in findings):
            conclusion = 'INCOMPLETE'
        else:
            conclusion = 'CORRECT'
        results.append({'id': case_id, 'category': prompt['category'], 'promptSha256': answer['promptSha256'],
                        'responseSha256': answer['responseSha256'], 'engineeringStatus': answer['engineeringStatus'],
                        'mathematicalResult': conclusion, 'obligations': findings})
    def counts(group):
        return {'cases': len(group),
                'mathematics': {key: sum(row['mathematicalResult'] == key for row in group) for key in MATHEMATICS},
                'engineering': {key: sum(row['engineeringStatus'] == key for row in group) for key in ENGINEERING},
                'obligations': dict(Counter(f['status'] for row in group for f in row['obligations'])),
                'unresolvedReasons': dict(Counter(f['reason'] for row in group for f in row['obligations']
                                                 if f['status'] == 'UNDETERMINED'))}
    report = {'contractVersion': 1, 'status': 'PASS', 'scope': 'OFFLINE_ASSESSMENT_CONSISTENCY_ONLY',
              'origin': seal['origin'], 'runId': seal['runId'], 'corpusSha256': seal['corpusSha256'],
              'answerSealSha256': seal_hash, 'referenceSha256': seal['referenceSha256'], 'judgmentSha256': sha(judgment_raw),
              'evaluatorDeclaration': evaluator, 'independentReview': 'NOT_EVALUATED',
              'referenceStatus': REFERENCE_STATUS, 'executionAttestation': 'NOT_ATTESTED',
              'modelQuality': 'NOT_ATTESTED', 'taskCompletion': 'NOT_EVALUATED',
              'totals': counts(results), 'categories': {category: counts([r for r in results if r['category'] == category])
                                                        for category in sorted({r['category'] for r in results})},
              'cases': results}
    write_new(output, report)
    return {k: report[k] for k in ('status', 'scope', 'origin', 'totals', 'modelQuality')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('seal', 'template', 'score'):
        command = commands.add_parser(name)
        command.add_argument('--corpus', type=Path, required=True)
        command.add_argument('--out', type=Path, required=True)
        if name == 'seal':
            command.add_argument('--run', type=Path, required=True)
        else:
            command.add_argument('--sealed', type=Path, required=True)
            if name == 'score':
                command.add_argument('--judgments', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'seal':
            result = seal_answers(args.corpus, args.run, args.out)
        elif args.command == 'template':
            result = template(args.sealed, args.corpus, args.out)
        else:
            result = score(args.sealed, args.corpus, args.judgments, args.out)
    except (OSError, ValueError, TypeError, KeyError, RecursionError) as error:
        # Never print answers, evaluator free text, paths or raw parser exceptions.
        code = str(error) if type(error) is ValueError and str(error).replace('_', '').isalpha() else type(error).__name__
        print(json.dumps({'status': 'FAIL', 'stage': args.command, 'code': code}))
        return 1
    print(json.dumps(result, ensure_ascii=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
