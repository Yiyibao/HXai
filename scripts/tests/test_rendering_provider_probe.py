import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import urllib.error

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from probe_rendering_provider import probe


class Response:
    status = 200
    headers = {'Content-Type': 'text/event-stream'}

    def __init__(self, payloads):
        self.lines = [('data: ' + json.dumps(payload) + '\n').encode() for payload in payloads]

    def __iter__(self):
        return iter(self.lines)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class RenderingProbeTests(unittest.TestCase):
    def run_probe(self, payloads):
        with patch('urllib.request.build_opener') as factory:
            factory.return_value.open.return_value = Response(payloads)
            result = probe('https://example.test/v1', 'gpt-6-astra', 'synthetic-test-key')
            request = factory.return_value.open.call_args.args[0]
            body = json.loads(request.data)
            self.assertEqual('low', body['reasoning']['effort'])
            self.assertFalse(body['store'])
            self.assertEqual('gpt-6-astra', body['model'])
            self.assertNotIn('synthetic-test-key', json.dumps(result))
            return result

    def test_completed_marker(self):
        result = self.run_probe([{'type': 'response.output_text.delta', 'delta': 'MATH_'},
                                 {'type': 'response.completed', 'response': {'output': [
                                     {'content': [{'type': 'output_text', 'text': 'MATH_RENDERING_OK'}]}]}}])
        self.assertEqual('PASS', result['status'])
        self.assertTrue(result['completed'])

    def test_failure_body_is_not_reported(self):
        result = self.run_probe([{'type': 'response.failed', 'response': {'error': {
            'message': 'sensitive raw service body', 'code': 'unknown-provider-code'}}}])
        self.assertEqual('FAIL', result['status'])
        self.assertEqual('response.failed', result['reason'])
        self.assertNotIn('sensitive', json.dumps(result))

    def test_incomplete_is_not_pass(self):
        result = self.run_probe([{'type': 'response.output_text.delta', 'delta': 'MATH_RENDERING_OK'},
                                 {'type': 'response.incomplete'}])
        self.assertEqual('FAIL', result['status'])

    def test_http_error_is_redacted(self):
        with patch('urllib.request.build_opener') as factory:
            factory.return_value.open.side_effect = urllib.error.HTTPError('https://example.test/v1', 403, 'private-body', {}, None)
            result = probe('https://example.test/v1', 'gpt-6-astra', 'synthetic-test-key')
        self.assertEqual(403, result['http_status'])
        self.assertNotIn('private-body', json.dumps(result))

    def test_timeout_is_not_http_rejection(self):
        with patch('urllib.request.build_opener') as factory:
            factory.return_value.open.side_effect = urllib.error.URLError(TimeoutError())
            result = probe('https://example.test/v1', 'gpt-6-astra', 'synthetic-test-key')
        self.assertEqual('transport_timeout', result['reason'])


if __name__ == '__main__':
    unittest.main()
