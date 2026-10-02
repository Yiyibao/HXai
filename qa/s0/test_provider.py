"""Offline protocol counterexamples; never label these real-provider PASS."""
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import probe_provider as probe


class EventStream(io.BytesIO):
    headers = {'Content-Type': 'text/event-stream'}


def event(text='', finish=None):
    return ('data: ' + json.dumps({'choices': [{'delta': {'content': text}, 'finish_reason': finish}]}) + '\n\n').encode()


class ProviderTests(unittest.TestCase):
    def test_finished_incremental_stream(self):
        data = event('x' * 20) + event('y' * 20) + event(finish='stop') + b'data: [DONE]\n\n'
        with patch.object(probe, 'post', return_value=EventStream(data)):
            self.assertEqual(probe.stream_probe('unused', 'unused', 'unused')['content_chunks'], 2)

    def test_truncated_and_unfinished_stream(self):
        for data in (event('x' * 20), event('x' * 20) + event('y' * 20) + b'data: [DONE]\n\n',
                     event('x' * 20) + event('y' * 20) + event(finish='length') + b'data: [DONE]\n\n'):
            with self.subTest(data=data), patch.object(probe, 'post', return_value=EventStream(data)), self.assertRaises(probe.ProbeError):
                probe.stream_probe('unused', 'unused', 'unused')

    def test_true_tool_roundtrip(self):
        first = {'role': 'assistant', 'content': None, 'tool_calls': [{'id': 'call-1', 'type': 'function',
            'function': {'name': 's0_add', 'arguments': '{"a":17,"b":25}'}}]}
        with patch.object(probe, 'complete', side_effect=[first, {'role': 'assistant', 'content': 'S0_TOOL_OK:42'}]) as complete:
            self.assertEqual(probe.tool_probe('unused', 'unused', 'unused')['validated_result'], 42)
            sent = complete.call_args.args[2]['messages'][-1]
            self.assertEqual(sent['tool_call_id'], 'call-1')
            self.assertEqual(json.loads(sent['content']), {'sum': 42})

    def test_prose_cannot_call_tool(self):
        with patch.object(probe, 'complete', return_value={'content': 'call s0_add(17,25)'}), self.assertRaises(probe.ProbeError):
            probe.tool_probe('unused', 'unused', 'unused')

    def test_bad_tool_never_executes_followup(self):
        for function in ({'name': 'delete_files', 'arguments': '{}'}, {'name': 's0_add', 'arguments': '{"a":17,"b":26}'}):
            first = {'tool_calls': [{'type': 'function', 'id': 'call-1', 'function': function}]}
            with self.subTest(function=function), patch.object(probe, 'complete', return_value=first) as complete, self.assertRaises(probe.ProbeError):
                probe.tool_probe('unused', 'unused', 'unused')
            self.assertEqual(complete.call_count, 1)


if __name__ == '__main__':
    unittest.main()
