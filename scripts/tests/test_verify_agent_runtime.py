import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "runtime_verifier", Path(__file__).resolve().parents[1] / "verify_agent_runtime.py"
)
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)

# These are deliberately tiny real child processes, not mocked subprocess results.
DEMO_CHILD = r'''
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1])
root.mkdir()
reports = [b"synthetic report one\n", b"synthetic report two\n"]
entries, references = [], []
for index, data in enumerate(reports, 1):
    identity = "artifact" + str(index)
    path = root / (identity + ".md")
    path.write_bytes(data)
    entries.append(dict(artifact_id=identity, relative_path=path.name,
                        parent_artifact_id=None if index == 1 else "artifact1",
                        bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    references.append(str(path))
event_specs = [
    ("enqueued", 0, {}),
    ("claimed", 1, {"fence": 1}),
    ("call_prepared", 1, {"call_id": "write", "tool": "write_report_version"}),
    ("call_dispatched", 1, {"call_id": "write"}),
    ("recovered_checkpoint", 2, {"state": "UNCERTAIN_SIDE_EFFECT"}),
    ("effect_reconciled", 3, {"call_id": "write"}),
    ("resumed_by_user", 4, {}),
    ("claimed", 5, {"fence": 2}),
    ("completed", 6, {}),
]
events = [dict(sequence=index, revision=revision, owner="fixture-owner",
               task_id="fixture-task", kind=kind, metadata_json=json.dumps(metadata))
          for index, (kind, revision, metadata) in enumerate(event_specs, 1)]
evidence = dict(scope="LOCAL_PROTOCOL_E2E_ONLY", model_calls=0, real_device_calls=0,
                first_task="SUCCEEDED", second_version_task="SUCCEEDED",
                math_result=dict(status="DISPROVED", first_error_transition=2),
                assertions=["global_slot_held_while_uncertain", "report_read_back_after_interrupt",
                            "explicit_resume_required", "no_duplicate_write", "old_version_preserved",
                            "no_tool_for_chitchat"],
                versions=entries, version_files=references, events=events)
(root / "evidence.json").write_text(json.dumps(evidence), encoding="utf-8")
'''


class RuntimeVerificationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="hxai-runner-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.runtime = self.root / "runtime"
        (self.runtime / "hxai_agent").mkdir(parents=True)
        (self.runtime / "hxai_agent" / "__init__.py").write_text("", encoding="utf-8")
        (self.runtime / "hxai_agent" / "demo.py").write_text("", encoding="utf-8")
        (self.runtime / "tests").mkdir()
        (self.runtime / "tests" / "test_fixture.py").write_text("def test_local(): pass\n", encoding="utf-8")
        self.output = self.root / "results"
        self.passing = [sys.executable, "-c", "print('local child completed')"]

    def run_verifier(self, *, tests=None, demo=None, output=None, timeout=10):
        return verifier.verify(
            self.runtime, output or self.output, project_root=self.root,
            pytest_command=self.passing if tests is None else tests,
            demo_command=(lambda root: [sys.executable, "-c", DEMO_CHILD, str(root)]) if demo is None else demo,
            timeout=timeout,
        )

    def record(self, result, name):
        return next(record for record in result["records"] if record["name"] == name)

    def assert_complete_summary(self, result, exit_code):
        saved = json.loads((self.output / "TEST_RESULTS.json").read_text(encoding="utf-8"))
        self.assertEqual(result, saved)
        self.assertEqual(1, exit_code)
        self.assertEqual("FAIL", saved["status"])
        self.assertEqual({"android_app_device_release", "real_models", "production_cloud"},
                         {record["name"] for record in saved["records"] if record["status"] == "NOT_RUN"})
        self.assertIn("source_preservation", {record["name"] for record in saved["records"]})
        for name in ("runtime_math", "protocol_demo"):
            record = self.record(saved, name)
            self.assertTrue(record["started_utc"])
            self.assertTrue(record["finished_utc"])
            self.assertTrue(record["command"])
            self.assertEqual(str(self.runtime), record["cwd"])
            self.assertIn("python", record["versions"])
            for channel in ("stdout", "stderr"):
                log = record["logs"][channel]
                self.assertEqual(verifier.fingerprint(self.output / log["path"]),
                                 {key: log[key] for key in ("bytes", "sha256")})

    def test_nonzero_pytest_still_collects_successful_current_demo(self):
        result, code = self.run_verifier(tests=[sys.executable, "-c",
                                               "import sys; print('failure'); sys.exit(7)"])
        self.assert_complete_summary(result, code)
        self.assertEqual(7, self.record(result, "runtime_math")["actual_exit"])
        self.assertEqual("PASS", self.record(result, "demo_evidence")["status"])
        self.assertEqual(b"synthetic report one\n", (self.output / "demo/report_v1.md").read_bytes())

    def test_failed_demo_with_valid_evidence_cannot_be_promoted(self):
        result, code = self.run_verifier(demo=lambda root: [
            sys.executable, "-c", DEMO_CHILD + "\nraise SystemExit(9)\n", str(root)
        ])
        self.assert_complete_summary(result, code)
        self.assertEqual(9, self.record(result, "protocol_demo")["actual_exit"])
        self.assertEqual("FAIL", self.record(result, "demo_evidence")["status"])
        self.assertFalse((self.output / "demo/report_v1.md").exists())
        raw = self.record(result, "demo_evidence")["raw_evidence"]
        self.assertEqual("SUCCEEDED", json.loads((self.output / raw).read_text(encoding="utf-8"))["first_task"])

    def test_zero_exit_without_evidence_does_not_use_stale_runtime_evidence(self):
        historical = self.runtime / "evidence.json"
        historical.write_text('{"scope":"STALE"}', encoding="utf-8")
        before = historical.read_bytes()
        result, code = self.run_verifier(demo=lambda root: self.passing)
        self.assert_complete_summary(result, code)
        self.assertEqual("PASS", self.record(result, "protocol_demo")["status"])
        self.assertEqual("FAIL", self.record(result, "demo_evidence")["status"])
        self.assertEqual(before, historical.read_bytes())
        self.assertFalse((self.output / "demo").exists())

    def test_missing_executable_is_recorded_and_later_demo_runs(self):
        result, code = self.run_verifier(tests=[str(self.root / "missing-child-executable")])
        self.assert_complete_summary(result, code)
        record = self.record(result, "runtime_math")
        self.assertEqual("START_ERROR", record["failure"])
        self.assertIsNone(record["actual_exit"])
        self.assertEqual("PASS", self.record(result, "demo_evidence")["status"])

    def test_timeout_preserves_partial_utf8_output_and_continues(self):
        result, code = self.run_verifier(tests=[sys.executable, "-c",
            "import time; print('timeout α', flush=True); time.sleep(30)"], timeout=1)
        self.assert_complete_summary(result, code)
        record = self.record(result, "runtime_math")
        self.assertEqual("TIMEOUT", record["failure"])
        self.assertIn("timeout α", (self.output / record["logs"]["stdout"]["path"]).read_text(encoding="utf-8"))
        self.assertEqual("PASS", self.record(result, "demo_evidence")["status"])

    def test_demo_timeout_and_missing_executable_each_leave_evidence_failure(self):
        commands = [
            [sys.executable, "-c", "import time; time.sleep(30)"],
            [str(self.root / "missing-demo-executable")],
        ]
        for index, command in enumerate(commands):
            with self.subTest(command=command):
                self.output = self.root / ("failure-" + str(index))
                result, code = self.run_verifier(demo=lambda root: command, timeout=1)
                self.assert_complete_summary(result, code)
                self.assertEqual("FAIL", self.record(result, "protocol_demo")["status"])
                self.assertEqual("FAIL", self.record(result, "demo_evidence")["status"])

    def test_invalid_evidence_and_changed_report_each_fail_without_reports(self):
        mutations = [
            '(root / "evidence.json").write_text("not json")',
            '(root / "artifact1.md").write_bytes(b"tampered")',
            'evidence["first_task"] = "FAILED_BLOCKED"; (root / "evidence.json").write_text(json.dumps(evidence))',
            'evidence["versions"][1]["parent_artifact_id"] = None; (root / "evidence.json").write_text(json.dumps(evidence))',
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=mutation):
                self.output = self.root / ("invalid-" + str(index))
                result, code = self.run_verifier(demo=lambda root: [
                    sys.executable, "-c", DEMO_CHILD + "\n" + mutation, str(root)
                ])
                self.assert_complete_summary(result, code)
                self.assertEqual("FAIL", self.record(result, "demo_evidence")["status"])
                self.assertFalse((self.output / "demo/report_v1.md").exists())

    def test_external_evidence_path_and_hardlink_are_rejected(self):
        external = self.root / "external.md"
        external.write_bytes(b"synthetic report one\n")
        for index, mutation in enumerate((
            'evidence["version_files"][0] = sys.argv[2]; (root / "evidence.json").write_text(json.dumps(evidence))',
            'import os; (root / "artifact1.md").unlink(); os.link(sys.argv[2], root / "artifact1.md")',
        )):
            with self.subTest(mutation=mutation):
                self.output = self.root / ("escape-" + str(index))
                result, code = self.run_verifier(demo=lambda root: [
                    sys.executable, "-c", DEMO_CHILD + "\n" + mutation, str(root), str(external)
                ])
                self.assert_complete_summary(result, code)
                self.assertEqual("FAIL", self.record(result, "demo_evidence")["status"])
                self.assertFalse((self.output / "demo/report_v1.md").exists())
                self.assertEqual(b"synthetic report one\n", external.read_bytes())

    def test_incomplete_or_misbound_recovery_proof_is_not_promoted(self):
        mutations = [
            'evidence["events"] = evidence["events"][:1]',
            'evidence["events"].reverse()',
            'evidence["events"][5]["task_id"] = "other-task"',
            'evidence["events"][5]["owner"] = "other-owner"',
            'evidence["events"][5]["metadata_json"] = json.dumps({"call_id": "other-write"})',
            'evidence["events"][4]["metadata_json"] = json.dumps({"state": "RUNNING"})',
            'evidence["events"][7]["metadata_json"] = json.dumps({"fence": 1})',
            'evidence["events"][6]["revision"] = 3',
            'evidence["events"][6]["sequence"] = 6',
            'evidence["events"][6]["metadata_json"] = "null"',
        ]
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=mutation):
                self.output = self.root / ("bad-journal-" + str(index))
                result, code = self.run_verifier(demo=lambda root: [
                    sys.executable, "-c", DEMO_CHILD + "\n" + mutation
                    + '\n(root / "evidence.json").write_text(json.dumps(evidence))',
                    str(root),
                ])
                self.assert_complete_summary(result, code)
                self.assertEqual("FAIL", self.record(result, "demo_evidence")["status"])
                self.assertFalse((self.output / "demo/report_v1.md").exists())
                self.assertFalse((self.output / "demo/report_v2.md").exists())

    def test_prior_read_tool_journal_does_not_block_write_recovery(self):
        mutation = r'''
prior = [
    dict(evidence["events"][2], metadata_json=json.dumps(
        {"call_id": "read", "tool": "read_material_text"})),
    dict(evidence["events"][3], metadata_json=json.dumps({"call_id": "read"})),
]
evidence["events"][2:2] = prior
for index, event in enumerate(evidence["events"], 1):
    event["sequence"] = index
(root / "evidence.json").write_text(json.dumps(evidence))
'''
        result, code = self.run_verifier(demo=lambda root: [
            sys.executable, "-c", DEMO_CHILD + mutation, str(root),
        ])
        self.assertEqual(0, code)
        self.assertEqual("PASS", self.record(result, "demo_evidence")["status"])
        self.assertEqual(b"synthetic report one\n", (self.output / "demo/report_v1.md").read_bytes())
        self.assertEqual(b"synthetic report two\n", (self.output / "demo/report_v2.md").read_bytes())

    def test_internal_directory_link_cannot_supply_report_evidence(self):
        mutation = r'''
import os, subprocess
directory = root / "real"
directory.mkdir()
(root / "artifact1.md").rename(directory / "artifact1.md")
link = root / "linked"
if os.name == "nt":
    subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(directory)],
                   capture_output=True, check=True)
else:
    link.symlink_to(directory, target_is_directory=True)
evidence["version_files"][0] = str(link / "artifact1.md")
(root / "evidence.json").write_text(json.dumps(evidence), encoding="utf-8")
'''
        result, code = self.run_verifier(demo=lambda root: [
            sys.executable, "-c", DEMO_CHILD + mutation, str(root)
        ])
        self.assert_complete_summary(result, code)
        self.assertEqual("PASS", self.record(result, "protocol_demo")["status"])
        self.assertEqual("FAIL", self.record(result, "demo_evidence")["status"])
        self.assertIn("links", self.record(result, "demo_evidence")["error"])
        self.assertFalse((self.output / "demo/report_v1.md").exists())

    def test_existing_output_directory_and_file_preserve_historical_bytes(self):
        for path in (self.output, self.root / "old-log"):
            if path == self.output:
                path.mkdir()
                marker = path / "TEST_RESULTS.json"
            else:
                marker = path
            marker.write_bytes(b"historical evidence must remain")
            with self.assertRaisesRegex(ValueError, "fresh directory"):
                self.run_verifier(output=path)
            self.assertEqual(b"historical evidence must remain", marker.read_bytes())

    def test_original_private_artifact_and_db_paths_are_refused(self):
        originals = self.root / "HXai_Pro_Delivery"
        originals.mkdir()
        db_directory = self.root / "existing-task-data"
        db_directory.mkdir()
        (db_directory / "tasks.sqlite3").write_bytes(b"private DB fixture")
        for path in (originals / "new-run", self.runtime / "managed_files/new-run",
                     self.root / "artifacts/new-run", db_directory / "new-run"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.run_verifier(output=path)
            self.assertFalse(path.exists())
        self.assertEqual(b"private DB fixture", (db_directory / "tasks.sqlite3").read_bytes())

    def test_invalid_runtime_is_refused_before_creating_output(self):
        with self.assertRaisesRegex(ValueError, "Runtime must contain"):
            verifier.verify(self.root / "not-a-runtime", self.output, project_root=self.root)
        self.assertFalse(self.output.exists())

    def test_unrelated_ancestor_database_does_not_block_workspace_verification(self):
        unrelated = self.root / "unrelated.sqlite3"
        unrelated.write_bytes(b"unrelated user database must remain")
        result, code = verifier.verify(
            self.runtime, self.output, project_root=self.runtime,
            pytest_command=self.passing,
            demo_command=lambda root: [sys.executable, "-c", DEMO_CHILD, str(root)],
        )
        self.assertEqual(0, code)
        self.assertEqual("PASS", self.record(result, "demo_evidence")["status"])
        self.assertEqual(b"unrelated user database must remain", unrelated.read_bytes())



if __name__ == "__main__":
    unittest.main()
