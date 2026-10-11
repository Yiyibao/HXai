import importlib.util
import json
import tempfile
import unittest
import uuid
from pathlib import Path

spec = importlib.util.spec_from_file_location('collector', Path(__file__).resolve().parents[1] / 'collect_run_evidence.py')
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)

class RunEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run_id = str(uuid.uuid4())
        self.method = 'QaEvidenceRunTest.synthetic'
        self.data = b'QA synthetic current evidence'
        self.stamp = 1800000000
        self.entry = dict(name=f'qa-evidence/{self.method}/{self.run_id}/transcript.md',
                          sha256=collector.digest(self.data), bytes=len(self.data), reusedSyntheticInput=False)
        self.manifest = dict(schema=1, method=self.method, runId=self.run_id,
                             startedAt=self.stamp*1000, finishedAt=self.stamp*1000+1000, status='PASS', entries=[self.entry])
        self.context = dict(appSha256='a'*64, testSha256='b'*64,
                            runs=[dict(method=self.method, runId=self.run_id, log='run.log',
                                       notBefore=self.stamp*1000-1000, notAfter=self.stamp*1000+2000)])
        self.calls = []
        self.write_context()
    def write_context(self):
        cls, method = self.method.rsplit('.', 1)
        log = (f'INSTRUMENTATION_STATUS: class=qa.{cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 1\n'
               'INSTRUMENTATION_STATUS: qa_evidence_manifest='+json.dumps(self.manifest)+'\nINSTRUMENTATION_STATUS_CODE: 2\n'
               f'INSTRUMENTATION_STATUS: class=qa.{cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: '
               + ('0' if self.manifest['status'] == 'PASS' else '-2') + '\nINSTRUMENTATION_CODE: -1\n').encode()
        (self.root/'run.log').write_bytes(log)
        self.context['runs'][0]['logSha256'] = collector.digest(log)
        (self.root/'context.json').write_text(json.dumps(self.context), encoding='utf-8')
    def device(self, *args):
        self.calls.append(args)
        if args[:3] == ('shell','pm','path'):
            return ('package:/data/app/'+args[3]+'/base.apk\n').encode()
        if args[:2] == ('shell','sha256sum'):
            return (('b' if '.test/' in args[2] else 'a')*64+'  base.apk\n').encode()
        if 'stat' in args:
            return str(self.stamp).encode()
        if args[0] == 'exec-out':
            return self.data
        raise AssertionError(args)
    def collect(self):
        return collector.collect(self.root/'context.json', self.root/'output', self.device)
    def replace_log(self, transform):
        log = transform((self.root/'run.log').read_text()).encode()
        (self.root/'run.log').write_bytes(log)
        self.context['runs'][0]['logSha256'] = collector.digest(log)
        (self.root/'context.json').write_text(json.dumps(self.context))
    def test_manifest_then_assertion_or_cleanup_failure_rejected(self):
        for cause in ('AssertionError: assertion', 'RuntimeException: cleanup'):
            self.write_context()
            self.replace_log(lambda log: log.replace('INSTRUMENTATION_STATUS_CODE: 0',
                f'INSTRUMENTATION_STATUS: stack={cause}\nINSTRUMENTATION_STATUS_CODE: -2'))
            with self.assertRaisesRegex(ValueError, 'contradicts'):
                self.collect()
            self.assertFalse((self.root/'output/fixture-manifest.json').exists())
    def test_crash_and_missing_terminals_rejected(self):
        for transform in (
            lambda log: log.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_RESULT: shortMsg=Process crashed'),
            lambda log: log.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: 2'),
            lambda log: log.replace('INSTRUMENTATION_CODE: -1', ''),
            lambda log: log.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_STATUS: class=qa.QaEvidenceRunTest\nINSTRUMENTATION_STATUS: test=synthetic\nINSTRUMENTATION_STATUS_CODE: 0'),
        ):
            self.write_context(); self.replace_log(transform)
            with self.assertRaises(ValueError): self.collect()
    def test_mixed_methods_and_exact_class(self):
        other = ('INSTRUMENTATION_STATUS: class=qa.OtherTest\nINSTRUMENTATION_STATUS: test=synthetic\n'
                 'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=qa.OtherTest\n'
                 'INSTRUMENTATION_STATUS: test=synthetic\nINSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\n')
        self.replace_log(lambda log: other + log)
        self.assertEqual('PASS', self.collect()['records'][0]['status'])
        self.write_context()
        self.replace_log(lambda log: log.replace('class=qa.QaEvidenceRunTest', 'class=qa.WrongTest'))
        with self.assertRaisesRegex(ValueError, 'exact class/test'):
            collector.collect(self.root/'context.json', self.root/'wrong-class', self.device)
    def test_current_and_repeat_collection_recheck_device(self):
        self.assertEqual(1,len(self.collect()['records']))
        self.calls.clear()
        self.collect()
        self.assertTrue(any('stat' in call for call in self.calls))
        self.assertTrue(any(call[0]=='exec-out' for call in self.calls))
    def test_existing_manifest_does_not_bypass_stale_device(self):
        self.collect()
        before = (self.root/'output/fixture-manifest.json').read_bytes()
        self.stamp -= 86400
        with self.assertRaisesRegex(ValueError,'Stale evidence'):
            self.collect()
        self.assertEqual(before,(self.root/'output/fixture-manifest.json').read_bytes())
    def test_changed_device_and_local_bytes_are_rejected(self):
        self.collect()
        original = self.data
        self.data = b'QA changed'
        with self.assertRaisesRegex(ValueError,'Device evidence'):
            self.collect()
        self.data = original
        (self.root/'output'/self.entry['name']).write_bytes(b'QA local changed')
        with self.assertRaisesRegex(ValueError,'Existing local evidence'):
            self.collect()
    def test_other_run_wrong_apk_and_old_window_are_rejected(self):
        self.manifest['runId']=str(uuid.uuid4()); self.write_context()
        with self.assertRaisesRegex(ValueError,'Missing or duplicate'):
            self.collect()
        self.manifest['runId']=self.run_id
        self.context['appSha256']='c'*64; self.write_context()
        with self.assertRaisesRegex(ValueError,'APK pair'):
            self.collect()
        self.context['appSha256']='a'*64
        self.context['runs'][0]['notBefore']+=86400000; self.write_context()
        with self.assertRaisesRegex(ValueError,'execution window'):
            self.collect()
    def test_legacy_manifest_and_changed_log_are_rejected(self):
        (self.root/'output').mkdir()
        (self.root/'output/fixture-manifest.json').write_text(json.dumps([self.entry]))
        with self.assertRaisesRegex(ValueError, 'Legacy manifest'):
            self.collect()
        (self.root/'output/fixture-manifest.json').unlink()
        (self.root/'run.log').write_text('changed')
        with self.assertRaisesRegex(ValueError,'Run log'):
            self.collect()
    def test_cross_method_path_and_failed_status_are_not_success(self):
        self.entry['name']='qa-evidence/Other.method/'+self.run_id+'/transcript.md'; self.write_context()
        with self.assertRaisesRegex(ValueError,'outside'):
            self.collect()
        self.entry['name']=f'qa-evidence/{self.method}/{self.run_id}/transcript.md'
        self.manifest['status']='FAILED'; self.write_context()
        self.assertEqual('FAILED',self.collect()['records'][0]['status'])

if __name__=='__main__':
    unittest.main()
