"""Local structural checks only: these do not establish extraction accuracy."""
import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from validate_candidates import validate, CandidateError

BASE = {'schema_version': 1, 'candidates': [{
    'type': 'PREFERENCE', 'topic_key': 'teaching', 'fact_key': 'example_first',
    'content': '喜欢先看反例', 'evidence_type': 'SELF_REPORTED', 'source_ids': ['source-1']}]}


class CandidateTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(len(validate(json.dumps(BASE), {'source-1'})), 1)

    def test_empty(self):
        self.assertEqual(validate('{"schema_version":1,"candidates":[]}', set()), [])

    def test_bad_json_and_truncation(self):
        for raw in ('{broken}', json.dumps(BASE)[:-4], '```json\n{}\n```', 'null'):
            with self.subTest(raw=raw), self.assertRaises(CandidateError):
                validate(raw, {'source-1'})

    def test_invalid_fields(self):
        for field, value in [('profile_id', 'another-person'), ('source_ids', ['invented']),
                             ('source_ids', ['source-1', 'source-1']), ('source_ids', []),
                             ('source_ids', [1]), ('content', ''), ('content', 'a' * 2001),
                             ('type', 'MASTERED'), ('evidence_type', 'VERIFIED')]:
            with self.subTest(field=field, value=str(value)[:30]), self.assertRaises(CandidateError):
                doc = copy.deepcopy(BASE)
                doc['candidates'][0][field] = value
                validate(json.dumps(doc), {'source-1'})

    def test_other_person_source(self):
        with self.assertRaises(CandidateError):
            validate(json.dumps(BASE), {'another-person-source'})

    def test_duplicates_and_version(self):
        for raw in ('{"schema_version":1,"schema_version":1,"candidates":[]}',
                    '{"schema_version":true,"candidates":[]}',
                    json.dumps({'schema_version': 1, 'candidates': BASE['candidates'] * 2})):
            with self.subTest(raw=raw), self.assertRaises(CandidateError):
                validate(raw, {'source-1'})


if __name__ == '__main__':
    unittest.main()
