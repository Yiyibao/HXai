"""Storage lifecycle counterexamples use only fresh, synthetic temporary repos."""
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import project_storage as storage

SOURCE = Path(__file__).resolve().parents[2]


class StorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hxai_storage_")
        self.addCleanup(self.temp.cleanup)
        self.parent = Path(self.temp.name).resolve()
        self.root = self.parent / "space repo"
        self.root.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Synthetic Storage Test")
        self.git("config", "user.email", "fixture@example.invalid")
        self.policy = json.loads((SOURCE / ".hxai-storage-policy.json").read_text(encoding="utf-8"))
        self.policy["minimum_free_mib"] = 1
        self.write_policy()
        (self.root / "source.txt").write_text("original-source", encoding="utf-8")
        (self.root / ".gitignore").write_text(".local/\n__pycache__/\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-qm", "synthetic baseline")
        self.manager = storage.Storage(self.root)

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], capture_output=True, check=True).stdout

    def write_policy(self):
        (self.root / ".hxai-storage-policy.json").write_text(json.dumps(self.policy), encoding="utf-8")

    def new_run(self, disposable=True, retain=0):
        return self.manager.start("FIXTURE", "test", disposable, 1, retain)

    def close(self, record, **extra):
        self.manager.update(record["id"], **{"state": "CLOSED", "expires_at": 0, **extra})

    def test_nondictionary_policy_fails_closed(self):
        (self.root / '.hxai-storage-policy.json').write_text('[]', encoding='utf-8')
        with self.assertRaises(storage.StorageError):
            storage.Storage(self.root)

    @unittest.skipUnless(os.name == 'nt', 'Windows batch entrypoint')
    def test_windows_wrapper_blocks_before_java_when_capacity_exceeded(self):
        (self.root / 'client').mkdir()
        (self.root / 'scripts').mkdir()
        shutil.copyfile(SOURCE / 'client/gradlew.bat', self.root / 'client/gradlew.bat')
        shutil.copyfile(SOURCE / 'scripts/project_storage.py', self.root / 'scripts/project_storage.py')
        self.policy['workspace_limit_mib'] = 1
        self.write_policy()
        (self.root / 'large.bin').write_bytes(b'x' * (2 * storage.MIB))
        result = subprocess.run(['cmd', '/d', '/c', str(self.root / 'client/gradlew.bat'), '--version'],
                                cwd=self.root, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'workspace_limit', result.stdout + result.stderr)
        self.assertFalse((self.root / '.gradle').exists())

    def test_installed_commit_hook_rejects_generated_blob(self):
        (self.root / 'scripts').mkdir()
        shutil.copyfile(SOURCE / 'scripts/project_storage.py', self.root / 'scripts/project_storage.py')
        storage.install_hook(self.manager)
        (self.root / 'artifact.apk').write_bytes(b'not-source')
        self.git('add', 'artifact.apk')
        result = subprocess.run(['git', '-C', str(self.root), 'commit', '-m', 'must fail'], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'generated/runtime artifact staged', result.stdout + result.stderr)

    def test_readonly_report_does_not_create_run_or_registry(self):
        self.assertGreater(self.manager.report()["total_bytes"], 0)
        self.assertFalse((self.root / ".local").exists())
        self.assertFalse(self.manager.meta.exists())

    def test_gc_defaults_to_plan_then_removes_only_registered_payload(self):
        record, path = self.new_run()
        (path / "generated.bin").write_bytes(b"generated")
        self.close(record)
        result = self.manager.gc()
        self.assertEqual(result["results"][0]["status"], "CANDIDATE")
        self.assertTrue(path.exists())
        result = self.manager.gc(True)
        self.assertEqual(result["results"][0]["status"], "CLEANED")
        self.assertFalse(path.exists())
        self.assertEqual((self.root / "source.txt").read_text(), "original-source")

    def test_active_run_cannot_expire_into_cleanup(self):
        _, path = self.new_run()
        self.assertEqual(self.manager.gc(True)["results"], [])
        self.assertTrue(path.exists())
        with self.assertRaises(storage.StorageError):
            self.new_run()

    def test_non_disposable_data_is_never_cleaned(self):
        record, path = self.new_run(False)
        self.close(record)
        self.assertEqual(self.manager.gc(True)["results"], [])
        self.assertTrue(path.exists())
        with self.assertRaises(storage.StorageError):
            self.new_run()

    def test_retention_is_bounded_and_future_deadline_is_respected(self):
        record, path = self.new_run(retain=1)
        self.close(record, expires_at=time.time() + 3600)
        self.assertEqual(self.manager.gc(True)["results"], [])
        self.assertTrue(path.exists())
        with self.assertRaises(storage.StorageError):
            self.manager.start("FIXTURE", "test", True, 1, 25)

    def test_unregistered_directory_is_reported_not_adopted_or_deleted(self):
        path = self.root / ".local/hxai-runs/unregistered"
        path.mkdir(parents=True)
        (path / "keep.txt").write_text("unregistered", encoding="utf-8")
        self.assertEqual(self.manager.gc(True)["results"], [])
        self.assertTrue((path / "keep.txt").exists())

    def test_missing_marker_is_not_treated_as_finished(self):
        record, path = self.new_run()
        self.close(record)
        (path / ".hxai-run.json").unlink()
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertTrue(path.exists())

    def test_mismatched_marker_cannot_authorize_deletion(self):
        record, path = self.new_run()
        self.close(record)
        marker = path / ".hxai-run.json"
        value = json.loads(marker.read_text())
        value["nonce"] = "0" * 32
        marker.write_text(json.dumps(value), encoding="utf-8")
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertTrue(path.exists())

    def test_foreign_registry_is_rejected_without_reset(self):
        _, path = self.new_run()
        data = self.manager.load()
        data["repo_id"] = "foreign"
        self.manager.registry.write_text(json.dumps(data), encoding="utf-8")
        before = self.manager.registry.read_bytes()
        with self.assertRaises(storage.StorageError):
            self.manager.gc(True)
        self.assertEqual(before, self.manager.registry.read_bytes())
        self.assertTrue(path.exists())

    def test_duplicate_or_nonfinite_metadata_is_rejected(self):
        self.new_run()
        for value in ['{"schema":1,"schema":1}', '{"schema":NaN}']:
            self.manager.registry.write_text(value, encoding="utf-8")
            with self.assertRaises(storage.StorageError):
                self.manager.load()

    def test_path_escape_does_not_touch_outside_data(self):
        record, path = self.new_run()
        self.close(record)
        keep = self.parent / "private"
        keep.mkdir()
        (keep / "sentinel").write_text("keep", encoding="utf-8")
        data = self.manager.load()
        data["records"][record["id"]]["worktree"] = str(keep)
        storage.atomic_json(self.manager.registry, data)
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertTrue((keep / "sentinel").exists())
        self.assertTrue(path.exists())

    def test_git_tracked_files_inside_payload_are_protected(self):
        record, path = self.new_run()
        tracked = path / "source.py"
        tracked.write_text("important = True", encoding="utf-8")
        self.git("add", "-f", str(tracked))
        self.close(record)
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertTrue(tracked.exists())

    def test_nested_git_metadata_is_protected(self):
        record, path = self.new_run()
        (path / ".git").mkdir()
        self.close(record)
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertTrue((path / ".git").exists())

    def test_actual_symlink_or_windows_junction_does_not_escape(self):
        record, path = self.new_run()
        outside = self.parent / "outside"
        outside.mkdir()
        (outside / "sentinel").write_text("keep", encoding="utf-8")
        link = path / "escape"
        try:
            if os.name == "nt":
                import _winapi
                _winapi.CreateJunction(str(outside), str(link))
            else:
                link.symlink_to(outside, target_is_directory=True)
        except (OSError, AttributeError) as e:
            self.skipTest("OS does not permit link fixture: " + type(e).__name__)
        self.assertTrue(storage.linked(link))
        self.close(record)
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertEqual((outside / "sentinel").read_text(), "keep")

    def test_partial_delete_keeps_intent_and_recovers_without_false_success(self):
        record, path = self.new_run()
        (path / "left.bin").write_bytes(b"left")
        self.close(record)
        def partial(trash):
            (trash / ".hxai-run.json").unlink()
            raise OSError("synthetic interrupted delete")
        with patch.object(storage.shutil, "rmtree", side_effect=partial):
            result = self.manager.gc(True)
        self.assertEqual(result["results"][0]["status"], "BLOCKED")
        self.assertEqual(self.manager.load()["records"][record["id"]]["state"], "GC_PENDING")
        trash = self.manager.payload(record, True)
        self.assertTrue((trash / "left.bin").exists())
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "CLEANED")
        self.assertFalse(trash.exists())

    def test_ambiguous_quarantine_is_not_deleted(self):
        record, path = self.new_run()
        self.manager.payload(record, True).mkdir()
        self.close(record)
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertTrue(path.exists())

    def test_live_writer_prevents_cleanup(self):
        record, path = self.new_run()
        self.close(record, child_pid=os.getpid())
        self.assertTrue(storage.process_alive(os.getpid()))
        self.assertEqual(self.manager.gc(True)["results"][0]["status"], "BLOCKED")
        self.assertTrue(path.exists())

    def test_os_lock_prevents_overlapping_registration(self):
        with self.manager.locked():
            with self.assertRaises(storage.StorageError):
                self.new_run()
        self.assertEqual(self.manager.load()["records"], {})

    def test_budget_blocks_command_before_any_child_side_effect(self):
        self.policy["workspace_limit_mib"] = 1
        self.write_policy()
        manager = storage.Storage(self.root)
        sentinel = self.parent / "must-not-exist"
        command = [sys.executable, "-c", f"from pathlib import Path; Path({str(sentinel)!r}).write_text('bad')"]
        with self.assertRaises(storage.StorageError):
            manager.run(command, "FIXTURE", "test", True, 2, 0)
        self.assertFalse(sentinel.exists())
        self.assertFalse((self.root / ".local/hxai-runs").exists())

    def test_generated_budget_does_not_misclassify_source_package_named_build(self):
        self.policy["generated_limit_mib"] = 1
        self.write_policy()
        source = self.root / "client/app/src/main/java/build/Source.kt"
        source.parent.mkdir(parents=True)
        source.write_bytes(b"x" * (2 * storage.MIB))
        manager = storage.Storage(self.root)
        self.assertEqual(manager.check()["generated_bytes"], 0)
        target = self.root / "client/app/build/intermediates/generated.bin"
        target.parent.mkdir(parents=True)
        target.write_bytes(b"x" * (2 * storage.MIB))
        with self.assertRaises(storage.StorageError):
            manager.check()

    def test_worktree_gate_blocks_third_checkout(self):
        self.assertEqual(self.manager.check(new_worktree=True)["worktrees"], 1)
        self.git("worktree", "add", "-qb", "codex/fixture-extra", str(self.parent / "extra"))
        with self.assertRaises(storage.StorageError):
            self.manager.check(new_worktree=True)

    def test_sdk_and_avd_copies_are_rejected(self):
        sdk = self.root / ".local/android-sdk/platform-tools"
        sdk.mkdir(parents=True)
        with self.assertRaises(storage.StorageError):
            self.manager.check()
        shutil.rmtree(sdk.parent)
        (self.root / "device.avd").mkdir()
        with self.assertRaises(storage.StorageError):
            self.manager.check()

    def test_staged_small_apk_and_large_log_are_rejected_but_source_is_allowed(self):
        source = self.root / "normal.py"
        source.write_text("answer = 42", encoding="utf-8")
        self.git("add", str(source))
        self.assertEqual(self.manager.staged_problems(), [])
        artifact = self.root / "fake.apk"
        artifact.write_bytes(b"tiny")
        log = self.root / "oversized.log"
        log.write_bytes(b"x" * (257 * 1024))
        self.git("add", "-f", str(artifact), str(log))
        errors = self.manager.staged_problems()
        self.assertTrue(any("fake.apk" in e for e in errors))
        self.assertTrue(any("oversized.log" in e for e in errors))

    def test_existing_hook_is_not_overwritten(self):
        hook = self.manager.common / "hooks/pre-commit"
        hook.write_text("existing user hook", encoding="utf-8")
        with self.assertRaises(storage.StorageError):
            storage.install_hook(self.manager)
        self.assertEqual(hook.read_text(), "existing user hook")

    def test_success_and_failure_both_reclaim_disposable_output_and_keep_small_receipt(self):
        for code in (0, 7):
            command = [sys.executable, "-c", "import os,pathlib,sys; pathlib.Path(os.environ['HXAI_RUN_DIR'],'out.bin').write_bytes(b'generated'); sys.exit(" + str(code) + ")", "SYNTHETIC_SECRET_NOT_FOR_RECEIPTS"]
            with contextlib.redirect_stdout(io.StringIO()):
                result = self.manager.run(command, "FIXTURE", "test", True, 1, 0)
            self.assertEqual(result, code)
        records = sorted(self.manager.load()["records"].values(), key=lambda r: r["created_at"])
        self.assertEqual([r["exit_code"] for r in records], [0, 7])
        self.assertTrue(all(r["state"] == "CLEANED" for r in records))
        self.assertNotIn("SYNTHETIC_SECRET", self.manager.registry.read_text())
        self.assertEqual(list((self.root / ".local/hxai-runs").iterdir()), [])

    def test_receipt_count_is_bounded(self):
        self.policy["receipt_limit"] = 2
        self.write_policy()
        manager = storage.Storage(self.root)
        for _ in range(4):
            record, _ = manager.start("FIXTURE", "test", True, 1, 0)
            manager.update(record["id"], state="CLOSED", expires_at=0)
            manager.gc(True)
        self.assertEqual(len(manager.load()["records"]), 2)

    def test_log_capture_is_bounded_and_incompleteness_is_explicit(self):
        self.policy["log_limit_mib"] = 1
        self.write_policy()
        manager = storage.Storage(self.root)
        command = [sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'x'*(2*1024*1024))"]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(manager.run(command, "FIXTURE", "test", True, 1, 1), 0)
        record = next(iter(manager.load()["records"].values()))
        self.assertFalse(record["log_complete"])
        self.assertEqual(record["output_bytes"], 2 * storage.MIB)
        self.assertEqual((manager.payload(record) / "command.log").stat().st_size, storage.MIB)

    def test_oversized_run_is_reclaimed_but_cannot_report_storage_success(self):
        self.policy["run_limit_mib"] = 1
        self.write_policy()
        manager = storage.Storage(self.root)
        command = [sys.executable, "-c", "import os,pathlib; pathlib.Path(os.environ['HXAI_RUN_DIR'],'large.bin').write_bytes(b'x'*(2*1024*1024))"]
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(storage.StorageError):
            manager.run(command, "FIXTURE", "test", True, 1, 0)
        record = next(iter(manager.load()["records"].values()))
        self.assertTrue(record["storage_limit_exceeded"])
        self.assertEqual(record["exit_code"], 0)
        self.assertEqual(record["state"], "CLEANED")


if __name__ == "__main__":
    unittest.main()
