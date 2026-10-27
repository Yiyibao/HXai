import importlib.util
from pathlib import Path
import unittest
import tempfile
import zipfile
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('verify_agent_android',Path(__file__).resolve().parents[1]/'verify_agent_android.py')
driver=importlib.util.module_from_spec(spec);spec.loader.exec_module(driver)

class DiscoveryTest(unittest.TestCase):
    def test_fields_may_appear_in_either_order(self):
        packets='INSTRUMENTATION_STATUS: test=first\nINSTRUMENTATION_STATUS: class=A\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=B\nINSTRUMENTATION_STATUS: test=second\nINSTRUMENTATION_STATUS_CODE: 0\n'
        self.assertEqual(['A#first','B#second'],driver.instrumentation_methods(packets))
    def test_incomplete_packets_cannot_be_cross_paired(self):
        with self.assertRaises(ValueError):driver.instrumentation_methods('INSTRUMENTATION_STATUS: class=A\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: test=second\nINSTRUMENTATION_STATUS_CODE: 0')

    def test_frozen_apk_survives_later_build_output_replacement(self):
        with tempfile.TemporaryDirectory() as d:
            source=Path(d)/'source.apk';target=Path(d)/'selected.apk'
            with zipfile.ZipFile(source,'w') as z:z.writestr('classes.dex',b'synthetic-original')
            digest=driver.freeze_apk(source,target)
            source.write_bytes(b'later-build')
            self.assertEqual(digest,driver.hashlib.sha256(target.read_bytes()).hexdigest())
            with zipfile.ZipFile(target) as z:self.assertEqual(b'synthetic-original',z.read('classes.dex'))

    def test_concurrent_replacement_during_copy_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            source=Path(d)/'source.apk';target=Path(d)/'selected.apk'
            with zipfile.ZipFile(source,'w') as z:z.writestr('classes.dex',b'synthetic-original')
            copy=driver.shutil.copyfile
            def racing_copy(a,b):
                copy(a,b);a.write_bytes(b'concurrent-replacement')
            with patch.object(driver.shutil,'copyfile',racing_copy):
                with self.assertRaisesRegex(RuntimeError,'changed during snapshot'):driver.freeze_apk(source,target)

if __name__=='__main__':unittest.main()
