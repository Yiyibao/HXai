"""Responses protocol counterexamples; not real provider or Android evidence."""
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from probe_responses import ResponsesProbe, ProbeError


class EventStream(io.BytesIO):
    headers = {'Content-Type': 'text/event-stream'}


def event(kind, **values):
    return ('data: ' + json.dumps({'type': kind, **values}) + '\n\n').encode()


def response(text):
    return {'id': 'r1', 'status': 'completed', 'output': [
        {'type': 'message', 'content': [{'type': 'output_text', 'text': text}]}]}


class ResponsesTests(unittest.TestCase):
    def setUp(self):
        self.probe = ResponsesProbe('https://example.invalid/v1', 'synthetic', 'synthetic')

    def test_complete_matches_deltas(self):
        data = event('response.created', response={'id': 'r1'})
        data += event('response.output_text.delta', delta='x' * 20)
        data += event('response.output_text.delta', delta='y' * 20)
        data += event('response.completed', response=response('x' * 20 + 'y' * 20))
        with patch.object(self.probe, 'request', return_value=EventStream(data)):
            self.assertTrue(self.probe.stream_probe()['completion_matches_deltas'])

    def test_truncated_mismatched_and_failed(self):
        prefix = event('response.created', response={'id': 'r1'})
        prefix += event('response.output_text.delta', delta='x' * 20)
        prefix += event('response.output_text.delta', delta='y' * 20)
        for ending in (b'', event('response.completed', response=response('wrong')),
                       event('response.failed'), event('response.incomplete')):
            with self.subTest(ending=ending), patch.object(self.probe, 'request',
                    return_value=EventStream(prefix + ending)), self.assertRaises(ProbeError):
                self.probe.stream_probe()

    def test_tool_preserves_stateless_reasoning_and_call_id(self):
        reasoning = {'type': 'reasoning', 'encrypted_content': 'synthetic-reasoning'}
        call = {'type': 'function_call', 'call_id': 'call1', 'name': 's0_add',
                'arguments': '{"a":17,"b":25}'}
        with patch.object(self.probe, 'complete', side_effect=[
                {'output': [reasoning, call]}, response('S0_TOOL_OK:42')]) as complete:
            self.assertEqual(self.probe.tool_probe()['validated_result'], 42)
            replay = complete.call_args.args[0]
            self.assertIn(reasoning, replay)
            self.assertEqual(replay[-1]['call_id'], 'call1')
            self.assertEqual(json.loads(replay[-1]['output']), {'sum': 42})

    def test_invalid_tool_no_followup(self):
        for call in ({'type': 'message', 'content': 'call s0_add(17,25)'},
                     {'type': 'function_call', 'call_id': 'call1', 'name': 'delete_files', 'arguments': '{}'},
                     {'type': 'function_call', 'call_id': 'call1', 'name': 's0_add', 'arguments': '{"a":17,"b":26}'},
                     {'type': 'function_call', 'call_id': '', 'name': 's0_add', 'arguments': '{"a":17,"b":25}'}):
            with self.subTest(call=call), patch.object(self.probe, 'complete',
                    return_value={'output': [call]}) as complete, self.assertRaises(ProbeError):
                self.probe.tool_probe()
            self.assertEqual(complete.call_count, 1)

    def test_store_false_and_no_previous_id(self):
        with patch.object(self.probe.opener, 'open', return_value=EventStream(b'')) as opened:
            self.probe.request([{'role': 'user', 'content': 'synthetic'}])
        body = json.loads(opened.call_args.args[0].data)
        self.assertFalse(body['store'])
        self.assertEqual(body['reasoning']['effort'], 'medium')
        self.assertNotIn('previous_response_id', body)

    def test_real_candidate_shape_rejects_wrong_profile_source(self):
        candidate = {'schema_version': 1, 'candidates': [{'type': 'PREFERENCE',
            'topic_key': 'teaching', 'fact_key': 'counterexample_first',
            'content': '喜欢先看反例', 'evidence_type': 'SELF_REPORTED', 'source_ids': ['m1']}]}
        with patch.object(self.probe, 'complete', return_value=response(json.dumps(candidate))):
            self.assertTrue(self.probe.extraction_probe()['cross_profile_source_rejected'])


if __name__ == '__main__':
    unittest.main()
