"""Synthetic finalized PASS manifest followed by a JUnit method failure."""
import importlib.util
import json
import unittest
import sys
from pathlib import Path

QA = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('existing_tests', QA.parents[1] / 'scripts/tests/test_collect_run_evidence.py')
existing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(existing)

class StatusProbe(existing.RunEvidenceTests):
    def test_junit_failure_cannot_be_collected_as_pass(self):
        log = (self.root / 'run.log').read_bytes() + b'INSTRUMENTATION_STATUS: test=synthetic\nINSTRUMENTATION_STATUS: stack=java.lang.AssertionError: QA cleanup failed\nINSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\nTests run: 1, Failures: 1\n'
        (self.root / 'run.log').write_bytes(log)
        self.context['runs'][0]['logSha256'] = existing.collector.digest(log)
        (self.root / 'context.json').write_text(json.dumps(self.context))
        (QA / 'collector-disagreement-input.log').write_bytes(log)
        result = self.collect()
        (QA / 'collector-disagreement-output.json').write_text(json.dumps(result, indent=2))
        self.assertNotEqual('PASS', result['records'][0]['status'], 'Actual JUnit FAILED must not be delivered as PASS')

if __name__ == '__main__':
    outcome = unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite([StatusProbe('test_junit_failure_cannot_be_collected_as_pass')]))
    sys.exit(0 if outcome.wasSuccessful() else 1)

