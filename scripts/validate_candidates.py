"""S0 reference validator; not yet connected to Android persistence.

source_ids reference an immutable, application-issued source allowlist for one job.
The later commit transaction must recheck ownership, source revisions and delete_epoch.
"""
import json
from pathlib import Path

CONTRACT = json.loads((Path(__file__).resolve().parents[1] / 'qa/s0/candidate-contract.json').read_text())


class CandidateError(ValueError):
    pass


def validate(raw, allowed_source_ids):
    if len(raw) > 100_000:
        raise CandidateError('response_too_large')
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise CandidateError('duplicate_key')
            result[key] = value
        return result
    try:
        document = json.loads(raw, object_pairs_hook=unique_object)
    except json.JSONDecodeError:
        raise CandidateError('invalid_json') from None
    if not isinstance(document, dict) or set(document) != {'schema_version', 'candidates'}:
        raise CandidateError('invalid_envelope')
    if type(document['schema_version']) is not int or document['schema_version'] != 1:
        raise CandidateError('unsupported_version')
    candidates = document['candidates']
    if not isinstance(candidates, list) or len(candidates) > CONTRACT['max_candidates']:
        raise CandidateError('invalid_candidates')
    seen = set()
    for candidate in candidates:
        if not isinstance(candidate, dict) or set(candidate) != set(CONTRACT['required_candidate_fields']):
            raise CandidateError('invalid_fields')
        for key in ('type', 'topic_key', 'fact_key', 'content', 'evidence_type'):
            value = candidate[key]
            if not isinstance(value, str) or not value.strip():
                raise CandidateError('invalid_string')
        if candidate['type'] not in CONTRACT['types'] or candidate['evidence_type'] not in CONTRACT['evidence_types']:
            raise CandidateError('invalid_enum')
        if len(candidate['content']) > CONTRACT['max_content_chars'] or any(len(candidate[k]) > 128 for k in ('topic_key', 'fact_key')):
            raise CandidateError('field_too_large')
        sources = candidate['source_ids']
        if not isinstance(sources, list) or not 1 <= len(sources) <= 20 or any(not isinstance(s, str) for s in sources):
            raise CandidateError('invalid_sources')
        if len(set(sources)) != len(sources) or not set(sources) <= set(allowed_source_ids):
            raise CandidateError('unknown_or_duplicate_source')
        key = (candidate['topic_key'], candidate['fact_key'])
        if key in seen:
            raise CandidateError('duplicate_fact')
        seen.add(key)
    return candidates
