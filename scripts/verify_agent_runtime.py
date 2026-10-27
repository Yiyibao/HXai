"""Verify the trusted-local runtime without changing historical delivery evidence.

Usage: python scripts/verify_agent_runtime.py --output qa/runtime-new-run
Only local protocol/process-crash evidence is produced; no installs or model calls.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRIVATE_NAMES = frozenset({
    "managed_files", "artifacts", "materials", "private", ".private",
    "task-db", "task_db", "task-dbs", "task_dbs",
})
REQUIRED_ASSERTIONS = frozenset({
    "global_slot_held_while_uncertain", "report_read_back_after_interrupt",
    "explicit_resume_required", "no_duplicate_write", "old_version_preserved",
    "no_tool_for_chitchat",
})


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fingerprint(path):
    data = Path(path).read_bytes()
    return {"bytes": len(data), "sha256": digest(data)}


def write_json(path, value):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def protected_path(path, original):
    """Check lexical and resolved ancestors, including junction destinations."""
    absolute = Path(os.path.abspath(path))
    resolved = absolute.resolve()
    workspace = original.parent.resolve()
    for candidate in (absolute, resolved):
        if candidate.is_relative_to(original.resolve()):
            raise ValueError("Path is inside the immutable original HXai_Pro_Delivery")
        for ancestor in (candidate, *candidate.parents):
            if ancestor.name.casefold() in PRIVATE_NAMES:
                raise ValueError("Path is inside a private runtime DB/artifact directory")
            if ancestor.is_relative_to(workspace) and ancestor.is_dir() and any(
                entry.suffix.casefold() in {".sqlite", ".sqlite3", ".db"} and entry.is_file()
                for entry in ancestor.iterdir()
            ):
                raise ValueError("Path is inside a directory containing a private task database")
    return resolved


def prepare_output(output, runtime, project_root=ROOT):
    original = Path(project_root) / "HXai_Pro_Delivery"
    output = Path(output)
    if os.path.lexists(output):
        raise ValueError("Output must be a fresh directory; existing paths are never overwritten")
    output = protected_path(output, original)
    runtime = protected_path(runtime, original)
    if not (runtime / "hxai_agent" / "__init__.py").is_file() or not (runtime / "tests").is_dir():
        raise ValueError("Runtime must contain hxai_agent/__init__.py and tests/")
    if not (runtime / "hxai_agent" / "demo.py").is_file() or not any((runtime / "tests").glob("test_*.py")):
        raise ValueError("Runtime must contain the demo and runnable test modules")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.mkdir()  # Atomic refusal if another invocation claimed the directory.
    return output, runtime


def source_hashes(runtime):
    result = {}
    for directory in ("hxai_agent", "tests", "migrations"):
        for path in sorted((runtime / directory).rglob("*")):
            if path.is_file() and path.suffix in {".py", ".sql"}:
                result[path.relative_to(runtime).as_posix()] = fingerprint(path)
    return result


def versions():
    try:
        pytest = importlib.metadata.version("pytest")
    except importlib.metadata.PackageNotFoundError:
        pytest = "UNAVAILABLE"
    return {"python": sys.version, "python_executable": sys.executable,
            "pytest": pytest, "sqlite": sqlite3.sqlite_version,
            "platform": platform.platform()}


def run_process(name, command, cwd, output, env, timeout, provenance):
    """Record one real child, even when it cannot start or exceeds its deadline."""
    command = [str(part) for part in command]
    record = {"name": name, "command": command, "cwd": str(cwd),
              "started_utc": utc_now(), "timeout_seconds": timeout,
              "expected_exit": 0, "actual_exit": None, "versions": provenance,
              "environment": {key: env.get(key) for key in (
                  "PYTHONDONTWRITEBYTECODE", "PYTHONUTF8", "PYTHONIOENCODING",
                  "PYTEST_DISABLE_PLUGIN_AUTOLOAD", "PYTHONPATH")}}
    executable = shutil.which(command[0], path=env.get("PATH"))
    record["executable"] = executable
    if executable:
        try:
            record["executable_fingerprint"] = fingerprint(executable)
        except OSError as error:
            record["executable_fingerprint_error"] = str(error)
    started = time.monotonic()
    stdout, stderr = b"", b""
    try:
        child = subprocess.run(command, cwd=cwd, env=env, capture_output=True, timeout=timeout)
        stdout, stderr = child.stdout, child.stderr
        record.update(actual_exit=child.returncode, status="PASS" if child.returncode == 0 else "FAIL")
        if child.returncode != 0:
            record["failure"] = "NONZERO_EXIT"
    except subprocess.TimeoutExpired as error:
        stdout, stderr = error.stdout or b"", error.stderr or b""
        record.update(status="FAIL", failure="TIMEOUT", error=str(error))
    except OSError as error:
        record.update(status="FAIL", failure="START_ERROR", error=str(error))
    record.update(finished_utc=utc_now(), seconds=round(time.monotonic() - started, 3))
    record["logs"] = {}
    for channel, data in (("stdout", stdout), ("stderr", stderr)):
        path = output / "logs" / (name + "." + channel + ".log")
        with path.open("xb") as stream:
            stream.write(data)
        record["logs"][channel] = {"path": path.relative_to(output).as_posix(),
                                   "encoding": "utf-8", **fingerprint(path)}
    return record


def read_internal_file(path, root):
    """Reject links/reparse points and external references before reading bytes.

    The local account is trusted; this is not a malicious-process race sandbox.
    """
    root = root.resolve(strict=True)
    path = Path(path)
    if not path.is_absolute():
        path = root / path
    path = Path(os.path.abspath(path))
    if not path.is_relative_to(root) or not path.resolve(strict=True).is_relative_to(root):
        raise ValueError("Evidence reference escapes the fresh demo root")
    for candidate in (path, *path.parents):
        info = candidate.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400):
            raise ValueError("Evidence references may not traverse links or reparse points")
        if candidate == root:
            break
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise ValueError("Evidence must be a single-link regular file")
    return path, path.read_bytes()


def validate_recovery_events(events):
    """Require a single-task journal chain, not producer assertion strings."""
    expected = [
        ("enqueued", {}),
        ("claimed", {}),
        ("call_prepared", {"call_id": "write", "tool": "write_report_version"}),
        ("call_dispatched", {"call_id": "write"}),
        ("recovered_checkpoint", {"state": "UNCERTAIN_SIDE_EFFECT"}),
        ("effect_reconciled", {"call_id": "write"}),
        ("resumed_by_user", {}),
        ("claimed", {}),
        ("completed", {}),
    ]
    if not isinstance(events, list) or not events or not isinstance(events[0], dict):
        raise ValueError("Demo is missing recovery events")
    identity = (events[0].get("owner"), events[0].get("task_id"))
    if not all(isinstance(value, str) and value for value in identity):
        raise ValueError("Recovery events require owner and task identities")
    previous_sequence, previous_revision, matched = 0, -1, []
    for event in events:
        if not isinstance(event, dict) or (event.get("owner"), event.get("task_id")) != identity:
            raise ValueError("Recovery events cross task or owner boundaries")
        sequence, revision = event.get("sequence"), event.get("revision")
        if (type(sequence) is not int or sequence <= previous_sequence
                or type(revision) is not int or revision < previous_revision):
            raise ValueError("Recovery events are not in journal order")
        previous_sequence, previous_revision = sequence, revision
        metadata = json.loads(event.get("metadata_json", ""))
        if not isinstance(metadata, dict):
            raise ValueError("Invalid recovery event metadata")
        if len(matched) < len(expected):
            kind, required = expected[len(matched)]
            if (event.get("kind") == kind
                    and all(metadata.get(key) == value for key, value in required.items())):
                matched.append((event, metadata))
    if (len(matched) != len(expected) or events[0].get("kind") != "enqueued"
            or events[-1].get("kind") != "completed"):
        raise ValueError("Demo is missing the complete recovery event chain")
    first_fence, resumed_fence = matched[1][1].get("fence"), matched[7][1].get("fence")
    if (type(first_fence) is not int or first_fence < 1
            or type(resumed_fence) is not int or resumed_fence <= first_fence):
        raise ValueError("Recovery did not establish a newer execution fence")
    recovery_revisions = [event["revision"] for event, _ in matched[4:]]
    if any(after <= before for before, after in zip(recovery_revisions, recovery_revisions[1:])):
        raise ValueError("Recovery transitions did not advance the task revision")


def collect_demo(demo_root, output, process):
    """Validate current-run evidence and independently read back both reports."""
    result = {"name": "demo_evidence", "status": "FAIL", "artifacts": []}
    try:
        _, raw = read_internal_file(demo_root / "evidence.json", demo_root)
        raw_path = output / "logs" / "protocol_demo.evidence.raw.json"
        with raw_path.open("xb") as stream:
            stream.write(raw)
        result["raw_evidence"] = raw_path.relative_to(output).as_posix()
        result["evidence_fingerprint"] = {"bytes": len(raw), "sha256": digest(raw)}
        evidence = json.loads(raw.decode("utf-8"))
        if not isinstance(evidence, dict):
            raise ValueError("Demo evidence must be an object")
        if process["status"] != "PASS":
            raise ValueError("Failed demo evidence is diagnostic only, never successful evidence")
        if (evidence.get("scope") != "LOCAL_PROTOCOL_E2E_ONLY"
                or evidence.get("model_calls") != 0 or evidence.get("real_device_calls") != 0
                or evidence.get("first_task") != "SUCCEEDED"
                or evidence.get("second_version_task") != "SUCCEEDED"):
            raise ValueError("Demo scope or task completion predicates are invalid")
        assertions = evidence.get("assertions")
        if not isinstance(assertions, list) or not REQUIRED_ASSERTIONS <= set(assertions):
            raise ValueError("Demo is missing protocol assertions")
        validate_recovery_events(evidence.get("events"))
        math = evidence.get("math_result")
        if not isinstance(math, dict) or math.get("status") != "DISPROVED" or math.get("first_error_transition") != 2:
            raise ValueError("Demo did not identify the expected algebra transition")
        references, entries = evidence.get("version_files"), evidence.get("versions")
        if not isinstance(references, list) or not isinstance(entries, list) or len(references) != 2 or len(entries) != 2:
            raise ValueError("Demo must reference exactly two immutable report versions")
        pending, seen = [], set()
        for index, (reference, entry) in enumerate(zip(references, entries), 1):
            if not isinstance(reference, str) or not isinstance(entry, dict):
                raise ValueError("Invalid artifact reference")
            source, data = read_internal_file(reference, demo_root)
            observed = {"bytes": len(data), "sha256": digest(data)}
            if observed != {key: entry.get(key) for key in observed}:
                raise ValueError("Artifact bytes/hash differ from the demo receipt")
            if source in seen or source.name != entry.get("relative_path"):
                raise ValueError("Duplicate or mismatched artifact reference")
            seen.add(source)
            if not isinstance(entry.get("artifact_id"), str) or source.name != entry["artifact_id"] + ".md":
                raise ValueError("Artifact identity does not match its file")
            if entry.get("parent_artifact_id") != (None if index == 1 else entries[0].get("artifact_id")):
                raise ValueError("Report versions do not preserve the parent chain")
            pending.append((index, source, data, observed))
        destination = output / "demo"
        destination.mkdir()
        for index, source, data, observed in pending:
            target = destination / ("report_v" + str(index) + ".md")
            with target.open("xb") as stream:
                stream.write(data)
            if target.read_bytes() != data or fingerprint(target) != observed:
                raise ValueError("Copied report failed independent bytes/hash readback")
            result["artifacts"].append({"source": str(source), "path": target.relative_to(output).as_posix(), **observed})
        evidence["version_files"] = [entry["path"] for entry in result["artifacts"]]
        write_json(destination / "evidence.json", evidence)
        result.update(status="PASS", evidence="demo/evidence.json")
    except (OSError, ValueError, TypeError, KeyError) as error:
        result["error"] = str(error)
    return result


def verify(runtime, output, *, project_root=ROOT, timeout=180,
           python_executable=sys.executable, pytest_command=None, demo_command=None):
    """Return the summary and exit code; command overrides support real failure fixtures."""
    if timeout <= 0:
        raise ValueError("Timeout must be positive")
    output, runtime = prepare_output(output, runtime, project_root)
    summary = {"schema": 1, "feature_id": "PRO-COMPAT-20261012",
               "started_utc": utc_now(), "runtime": str(runtime), "output": str(output),
               "scope": "TRUSTED_LOCAL_PROTOCOL_AND_PROCESS_CRASH_ONLY",
               "power_loss_guarantee": "NOT_EVALUATED", "records": [],
               "runner": {"path": str(Path(__file__).resolve()), **fingerprint(__file__)},
               "versions": versions()}
    output.joinpath("logs").mkdir()
    try:
        summary["sources_before"] = source_hashes(runtime)
        env = os.environ.copy()
        env.update(PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1", PYTHONIOENCODING="utf-8",
                   PYTEST_DISABLE_PLUGIN_AUTOLOAD="1", PYTHONPATH=str(runtime))
        # Both subprocesses run against this source tree, with all writes in a fresh temp root.
        with tempfile.TemporaryDirectory(prefix="hxai-runtime-verify-") as temporary:
            temporary = Path(temporary).resolve()
            tests = pytest_command if pytest_command is not None else [
                python_executable, "-m", "pytest", "-q", "-p", "no:cacheprovider",
                "--basetemp", str(temporary / "pytest"), "tests",
            ]
            summary["records"].append(run_process("runtime_math", tests, runtime, output, env, timeout, summary["versions"]))
            demo_root = temporary / "demo"
            demo = demo_command(demo_root) if demo_command is not None else [
                python_executable, "-m", "hxai_agent.demo", "--output", str(demo_root),
            ]
            process = run_process("protocol_demo", demo, runtime, output, env, timeout, summary["versions"])
            summary["records"].append(process)
            # Always inspect the fresh root, even after failed/missing/timed-out children.
            summary["records"].append(collect_demo(demo_root, output, process))
        summary["sources_after"] = source_hashes(runtime)
        summary["records"].append({"name": "source_preservation", "status": "PASS" if
                                   summary["sources_before"] == summary["sources_after"] else "FAIL"})
    except Exception as error:
        summary["records"].append({"name": "runner", "status": "FAIL", "error": str(error), "type": type(error).__name__})
    finally:
        for name, reason in (
            ("android_app_device_release", "No Android/App/device/build operations in this local protocol command."),
            ("real_models", "No model or paid API calls are authorized or exercised."),
            ("production_cloud", "No production, deployment, network or cloud operations are exercised."),
        ):
            summary["records"].append({"name": name, "status": "NOT_RUN", "reason": reason})
        summary["finished_utc"] = utc_now()
        summary["status"] = "FAIL" if any(record["status"] == "FAIL" for record in summary["records"]) else "PASS_SCOPED"
        write_json(output / "TEST_RESULTS.json", summary)
    return summary, 1 if summary["status"] == "FAIL" else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=ROOT / "server" / "agent-runtime")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=180, help="Deadline in seconds for each child (default: 180)")
    args = parser.parse_args(argv)
    try:
        summary, exit_code = verify(args.runtime, args.output, timeout=args.timeout)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(json.dumps({"status": summary["status"], "results": str(args.output / "TEST_RESULTS.json")}, ensure_ascii=False))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
