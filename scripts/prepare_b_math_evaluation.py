"""Freeze B's original question bank for authorized A-line evaluation.
Only BLIND_PROMPTS.json may be supplied to the model. No provider/config/network access.
Packaging PASS is not an answer, reference-adjudication or model-quality PASS.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ('scripts/fixtures/b26_math_quality_v1.json', 'scripts/fixtures/b26_math_questions_extension_v2.json')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def prepare(output):
    cases = []
    sources = []
    ids = set()
    for relative in SOURCES:
        raw = (ROOT / relative).read_bytes()
        if len(raw) > 2_000_000:
            raise ValueError('source_size_limit')
        rows = json.loads(raw)
        if not isinstance(rows, list) or not 1 <= len(rows) <= 100:
            raise ValueError('source_case_shape')
        for row in rows:
            if not isinstance(row, dict):
                raise ValueError('case_object_required')
            for key, maximum in (('id', 100), ('category', 100), ('prompt', 12000)):
                value = row.get(key)
                if not isinstance(value, str) or not 1 <= len(value) <= maximum:
                    raise ValueError('case_identity_or_prompt_invalid')
            if row['id'] in ids:
                raise ValueError('duplicate_case_id')
            ids.add(row['id'])
            reference = row.get('reference')
            if not isinstance(reference, dict) or reference.get('status') != 'AUTHOR_DERIVED_NOT_INDEPENDENTLY_REVIEWED':
                raise ValueError('unreviewed_reference_status_required')
            obligations = reference.get('obligations')
            if not isinstance(obligations, list) or not obligations or any(not isinstance(o, str) or not o for o in obligations):
                raise ValueError('explicit_obligations_required')
            if not isinstance(reference.get('argument'), str) or not reference['argument']:
                raise ValueError('reference_argument_required')
            cases.append(row)
        sources.append({'path': relative, 'sha256': digest(raw), 'cases': len(rows)})
    corpus_hash = digest(json.dumps(cases, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8'))
    blind = {'corpusVersion': 2, 'corpusSha256': corpus_hash, 'modelExecution': 'NOT_RUN',
             'purpose': 'ORIGINAL_PUBLIC_QUESTIONS_NOT_AN_INDEPENDENT_BLIND_BENCHMARK; send only id/category/prompt, never references or certificates',
             'cases': [{k: row[k] for k in ('id', 'category', 'prompt')} for row in cases]}
    rubric = {'corpusVersion': 2, 'corpusSha256': corpus_hash,
              'referenceStatus': 'AUTHOR_DERIVED_NOT_INDEPENDENTLY_REVIEWED',
              'scoring': 'After sealing actual answers: each obligation MET/VIOLATED/UNDETERMINED; overall CORRECT/INCORRECT/INCOMPLETE/UNDETERMINED. No engine-hit-rate substitution.',
              'cases': [{'id': row['id'], 'reference': row['reference']} for row in cases]}
    output.mkdir(parents=True, exist_ok=False)
    artifacts = {}
    for name, data in (('BLIND_PROMPTS.json', blind), ('REFERENCE_RUBRIC.json', rubric)):
        encoded = json.dumps(data, ensure_ascii=False, indent=2).encode('utf-8')
        with (output / name).open('xb') as stream:
            stream.write(encoded)
        artifacts[name] = digest(encoded)
    seal = {'status': 'PASS', 'scope': 'QUESTION_REFERENCE_SEPARATION_ONLY', 'corpusVersion': 2,
            'cases': len(cases), 'corpusSha256': corpus_hash, 'sources': sources, 'artifacts': artifacts,
            'realModelCalls': 0, 'modelQuality': 'NOT_EVALUATED', 'independentReview': 'NOT_EVALUATED',
            'authorization': 'WAITING_EXPLICIT_PROVIDER_MODEL_REASONING_SCOPE_AUTHORIZATION; this command grants nothing'}
    with (output / 'CORPUS_SEAL.json').open('x', encoding='utf-8') as stream:
        json.dump(seal, stream, ensure_ascii=False, indent=2)
    return seal


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True, help='New local output directory; never overwritten')
    args = parser.parse_args()
    try:
        seal = prepare(args.out)
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(json.dumps({'status': 'FAIL', 'stage': 'question_packaging', 'type': type(error).__name__}))
        return 1
    print(json.dumps({k: seal[k] for k in ('status', 'scope', 'cases', 'corpusSha256', 'realModelCalls', 'modelQuality')}))
    return 0


if __name__ == '__main__':
    sys.exit(main())
