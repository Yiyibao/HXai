"""Bounded local development storage. Not an App task executor or arbitrary-path cleaner.

Only this tool's completed, explicitly disposable run payloads can be reclaimed.
Legacy build directories, SDKs, devices, user data and Git source are report-only.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import time
import uuid

MIB = 1024 * 1024
RUN_ID = re.compile(r"[a-f0-9]{32}\Z")
GENERATED = re.compile(r"(?:^|/)client/(?:[^/]+/)?build/|(?:^|/)(?:node_modules|\.gradle|\.dart_tool|\.kotlin|__pycache__|\.pytest_cache)/|^\.local/hxai-runs/")
STAGED_FORBIDDEN = {".apk", ".aab", ".aar", ".jar", ".zip", ".7z", ".vhdx", ".img", ".qcow2", ".jks", ".keystore", ".sqlite", ".sqlite3", ".db"}
STATES = {"ACTIVE", "BLOCKED", "CLOSED", "GC_PENDING", "CLEANED"}


class StorageError(RuntimeError):
    pass


def git(root, *args, data=None):
    r = subprocess.run(["git", "-C", str(root), *args], input=data, capture_output=True)
    if r.returncode:
        raise StorageError("Git operation failed: " + " ".join(args[:2]))
    return r.stdout


def linked(path):
    try:
        s = path.lstat()
    except FileNotFoundError:
        return False
    return stat.S_ISLNK(s.st_mode) or bool(getattr(s, "st_file_attributes", 0) & 0x400)


def read_json(path, limit=1024 * 1024):
    def pairs(values):
        result = {}
        for k, v in values:
            if k in result:
                raise StorageError("Duplicate JSON key")
            result[k] = v
        return result
    if linked(path) or path.stat().st_size > limit:
        raise StorageError("Unsafe metadata file")
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(StorageError("Nonfinite JSON")))
    except (ValueError, UnicodeError) as e:
        raise StorageError("Invalid metadata JSON") from e


def atomic_json(path, value):
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    with temporary.open("x", encoding="utf-8", newline="\n") as f:
        json.dump(value, f, ensure_ascii=True, sort_keys=True, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temporary, path)


def scan(root, strict=False):
    total = generated = files = 0
    links, avds = [], []
    stack = [root]
    while stack:
        parent = stack.pop()
        if linked(parent):
            if strict:
                raise StorageError("Reparse point/symlink in disposable payload")
            links.append(str(parent))
            continue
        with os.scandir(parent) as entries:
            for e in entries:
                p = Path(e.path)
                if linked(p):
                    if strict:
                        raise StorageError("Reparse point/symlink in disposable payload")
                    links.append(str(p))
                    continue
                if strict and e.name == ".git":
                    raise StorageError("Nested Git metadata is protected")
                if e.is_dir(follow_symlinks=False):
                    if e.name.endswith(".avd"):
                        avds.append(str(p))
                    stack.append(p)
                elif e.is_file(follow_symlinks=False):
                    size = e.stat(follow_symlinks=False).st_size
                    total += size
                    files += 1
                    if GENERATED.search(p.relative_to(root).as_posix()):
                        generated += size
    return {"bytes": total, "generated_bytes": generated, "files": files,
            "links_skipped": links, "project_avds": avds}


class RepoLock:
    def __init__(self, path):
        self.path = path

    def __enter__(self):
        if linked(self.path):
            raise StorageError("Linked lock file")
        self.file = self.path.open("a+b")
        if self.file.tell() == 0:
            self.file.write(b"0")
            self.file.flush()
        try:
            self.file.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as e:
            self.file.close()
            raise StorageError("Storage registry busy; retry after the current operation") from e
        return self

    def __exit__(self, *_):
        self.file.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(self.file.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(self.file, fcntl.LOCK_UN)
        self.file.close()


def process_alive(pid):
    if type(pid) is not int or pid <= 0:
        raise StorageError("Invalid writer PID")
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            if ctypes.get_last_error() == 87:
                return False
            raise StorageError("Writer process state cannot be verified")
        try:
            code = wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                raise StorageError("Writer process state cannot be verified")
            return code.value == 259
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError as e:
        raise StorageError("Writer process state cannot be verified") from e


class Storage:
    def __init__(self, root):
        self.root = Path(root).resolve(strict=True)
        actual = Path(git(self.root, "rev-parse", "--show-toplevel").decode().strip()).resolve()
        if actual != self.root:
            raise StorageError("--root must be the repository root")
        common = Path(git(self.root, "rev-parse", "--git-common-dir").decode().strip())
        self.common = (self.root / common).resolve()
        self.repo_id = hashlib.sha256(os.path.normcase(str(self.common)).encode()).hexdigest()
        self.meta = self.common / "hxai-storage"
        self.registry = self.meta / "registry.json"
        self.policy = read_json(self.root / ".hxai-storage-policy.json")
        if not isinstance(self.policy, dict) or self.policy.get("schema") != 1:
            raise StorageError("Unsupported policy schema")
        keys = {"workspace_limit_mib", "all_worktrees_limit_mib", "generated_limit_mib", "run_limit_mib",
                "minimum_free_mib", "max_worktrees", "max_active_runs", "max_retained_runs",
                "max_retention_hours", "receipt_limit", "log_limit_mib", "staged_file_limit_mib", "staged_log_limit_kib"}
        if any(type(self.policy.get(k)) is not int or self.policy[k] <= 0 for k in keys):
            raise StorageError("Policy limits must be positive integers")
        exceptions = self.policy.get("staged_file_exceptions")
        if not isinstance(exceptions, list) or any(not isinstance(x, str) or ".." in Path(x).parts or Path(x).is_absolute() for x in exceptions):
            raise StorageError("Invalid exact staged-file exceptions")

    def roots(self):
        raw = git(self.root, "worktree", "list", "--porcelain").decode("utf-8")
        return [Path(l[9:]).resolve() for l in raw.splitlines() if l.startswith("worktree ")]

    @contextlib.contextmanager
    def locked(self):
        if linked(self.meta):
            raise StorageError("Linked registry directory")
        self.meta.mkdir(exist_ok=True)
        with RepoLock(self.meta / "lock"):
            yield

    def load(self):
        if linked(self.meta):
            raise StorageError("Linked registry directory")
        if linked(self.registry):
            raise StorageError("Linked registry file")
        if not self.registry.exists():
            return {"schema": 1, "repo_id": self.repo_id, "records": {}}
        data = read_json(self.registry)
        if not isinstance(data, dict) or data.get("schema") != 1 or data.get("repo_id") != self.repo_id or not isinstance(data.get("records"), dict):
            raise StorageError("Unknown/foreign registry; no reset or cleanup")
        for key, record in data["records"].items():
            if not RUN_ID.fullmatch(key) or not isinstance(record, dict) or record.get("id") != key or record.get("state") not in STATES:
                raise StorageError("Invalid registry record")
            if type(record.get("disposable")) is not bool or record.get("kind") not in {"build", "test", "visual"}:
                raise StorageError("Invalid disposal record")
            if not isinstance(record.get("nonce"), str) or not RUN_ID.fullmatch(record["nonce"]) or not isinstance(record.get("worktree"), str):
                raise StorageError("Invalid run identity")
            if record.get("child_pid") is not None and (type(record["child_pid"]) is not int or record["child_pid"] <= 0):
                raise StorageError("Invalid writer PID")
            for name in ["created_at", "expires_at"]:
                value = record.get(name)
                if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                    raise StorageError("Invalid record time")
        return data

    def save(self, data):
        cleaned = sorted((r for r in data["records"].values() if r["state"] == "CLEANED"), key=lambda r: r["created_at"], reverse=True)
        for record in cleaned[self.policy["receipt_limit"]:]:
            del data["records"][record["id"]]
        atomic_json(self.registry, data)

    def payload(self, record, trash=False):
        worktree = Path(record["worktree"])
        if worktree != worktree.resolve() or worktree not in self.roots():
            raise StorageError("Run belongs to an unknown/relocated worktree")
        run_id = record["id"]
        if not RUN_ID.fullmatch(run_id):
            raise StorageError("Invalid run ID")
        path = worktree / ".local" / "hxai-runs" / ((".gc-" if trash else "") + run_id)
        for p in [worktree / ".local", worktree / ".local/hxai-runs", path]:
            if linked(p) or p.resolve() != p:
                raise StorageError("Linked or escaped payload path")
        if not path.is_relative_to(worktree) or path == worktree:
            raise StorageError("Payload outside workspace")
        return path

    def validate_payload(self, record, path, pending=False):
        scan(path, strict=True)
        relative = path.relative_to(Path(record["worktree"])).as_posix()
        if git(record["worktree"], "ls-files", "-z", "--", relative):
            raise StorageError("Tracked files are protected")
        marker = path / ".hxai-run.json"
        if marker.exists():
            expected = {k: record[k] for k in ["id", "nonce", "kind", "disposable"]}
            expected["repo_id"] = self.repo_id
            if read_json(marker, 8192) != expected:
                raise StorageError("Payload marker mismatch")
        elif not pending:
            raise StorageError("Missing payload marker")

    def report(self):
        roots = self.roots()
        details = []
        for root in roots:
            if not root.is_dir():
                raise StorageError("Missing worktree; repair registration before building")
            details.append({"root": str(root), **scan(root)})
        forbidden = []
        for root in roots:
            for p in [root / ".local/android-sdk", root / ".local/flutter", *list((root / ".local").glob("gradle-*"))]:
                if (p / "bin/flutter.bat").exists() or (p / "bin/flutter").exists() or (p / "platform-tools").is_dir() or (p / "bin/gradle.bat").exists() or (p / "bin/gradle").exists():
                    forbidden.append(str(p))
        return {"worktrees": details, "total_bytes": sum(x["bytes"] for x in details),
                "generated_bytes": sum(x["generated_bytes"] for x in details), "forbidden_tool_copies": forbidden,
                "free_bytes": shutil.disk_usage(self.root).free, "registry": self.load()}

    def check(self, reserve_mib=0, staged=False, new_worktree=False):
        if type(reserve_mib) is not int or not 0 <= reserve_mib <= self.policy["run_limit_mib"]:
            raise StorageError("Invalid or excessive reservation")
        report = self.report()
        problems = []
        reserve = reserve_mib * MIB
        for tree in report["worktrees"]:
            extra = reserve if Path(tree["root"]) == self.root else 0
            if tree["bytes"] + extra > self.policy["workspace_limit_mib"] * MIB:
                problems.append("workspace_limit: " + tree["root"])
            if tree["project_avds"]:
                problems.append("AVD data must live in the shared external test-device pool")
        if report["total_bytes"] + reserve > self.policy["all_worktrees_limit_mib"] * MIB:
            problems.append("all_worktrees_limit")
        if report["generated_bytes"] + reserve > self.policy["generated_limit_mib"] * MIB:
            problems.append("generated_limit")
        if report["free_bytes"] < (self.policy["minimum_free_mib"] + reserve_mib) * MIB:
            problems.append("minimum_free_space")
        if len(report["worktrees"]) + int(new_worktree) > self.policy["max_worktrees"]:
            problems.append("too_many_worktrees")
        if report["forbidden_tool_copies"]:
            problems.append("SDK/Flutter/Gradle copies inside project")
        if staged:
            problems.extend(self.staged_problems())
        if problems:
            raise StorageError("Storage gate BLOCK: " + "; ".join(problems))
        return {"status": "PASS", "logical_bytes": report["total_bytes"], "generated_bytes": report["generated_bytes"], "worktrees": len(report["worktrees"])}

    def staged_problems(self):
        names = git(self.root, "diff", "--cached", "--name-only", "-z", "--diff-filter=ACMRT").split(b"\0")
        errors = []
        for raw in filter(None, names):
            name = raw.decode("utf-8")
            if name in self.policy["staged_file_exceptions"]:
                continue
            path = Path(name)
            if path.suffix.lower() in STAGED_FORBIDDEN or ".local" in path.parts or GENERATED.search(name) or ".android" in path.parts:
                errors.append("generated/runtime artifact staged: " + name)
            size = int(git(self.root, "cat-file", "-s", ":" + name))
            limit = self.policy["staged_file_limit_mib"] * MIB
            if path.suffix.lower() in {".log", ".sarif"}:
                limit = self.policy["staged_log_limit_kib"] * 1024
            if size > limit:
                errors.append("oversized staged file: " + name)
        return errors

    def start(self, feature, kind, disposable, reserve_mib, retain_hours):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", feature) or kind not in {"build", "test", "visual"}:
            raise StorageError("Invalid feature or run kind")
        if type(disposable) is not bool or not 0 <= retain_hours <= self.policy["max_retention_hours"]:
            raise StorageError("Invalid disposal/retention")
        with self.locked():
            data = self.load()
            if sum(r["state"] in {"ACTIVE", "BLOCKED", "GC_PENDING"} for r in data["records"].values()) >= self.policy["max_active_runs"]:
                raise StorageError("An active/blocked run needs settlement; no automatic timeout release")
            if sum(r["state"] == "CLOSED" for r in data["records"].values()) >= self.policy["max_retained_runs"]:
                raise StorageError("Retained run limit; inspect gc before starting another")
            self.check(reserve_mib)
            run_id = uuid.uuid4().hex
            record = {"id": run_id, "nonce": uuid.uuid4().hex, "feature": feature, "kind": kind,
                      "worktree": str(self.root), "disposable": disposable, "state": "ACTIVE",
                      "created_at": time.time(), "expires_at": 0, "reserve_mib": reserve_mib,
                      "retain_hours": retain_hours, "child_pid": None}
            path = self.payload(record)
            path.mkdir(parents=True, exist_ok=False)
            atomic_json(path / ".hxai-run.json", {**{k: record[k] for k in ["id", "nonce", "kind", "disposable"]}, "repo_id": self.repo_id})
            data["records"][run_id] = record
            self.save(data)
            return record, path

    def update(self, run_id, **changes):
        with self.locked():
            data = self.load()
            data["records"][run_id].update(changes)
            self.save(data)

    def gc(self, apply=False):
        with self.locked() if apply else contextlib.nullcontext():
            data = self.load()
            results = []
            for record in list(data["records"].values()):
                pending = record["state"] == "GC_PENDING"
                if not record["disposable"] or (not pending and (record["state"] != "CLOSED" or record["expires_at"] > time.time())):
                    continue
                result = {"id": record["id"], "status": "CANDIDATE"}
                try:
                    pid = record.get("child_pid")
                    if pid is not None and process_alive(pid):
                        raise StorageError("Writer PID still exists; no timeout cleanup")
                    original, trash = self.payload(record), self.payload(record, True)
                    if original.exists() and trash.exists():
                        raise StorageError("Ambiguous original/quarantine directories")
                    path = trash if trash.exists() else original
                    if not path.exists():
                        if not pending:
                            raise StorageError("Unexplained missing payload")
                        result["bytes"] = 0
                    else:
                        self.validate_payload(record, path, pending and path == trash)
                        result["bytes"] = scan(path, strict=True)["bytes"]
                    if apply:
                        record["state"] = "GC_PENDING"
                        self.save(data)
                        if original.exists():
                            os.replace(original, trash)
                        if trash.exists():
                            self.validate_payload(record, trash, True)
                            shutil.rmtree(trash)
                        if trash.exists() or original.exists():
                            raise StorageError("Payload remains after cleanup")
                        record["state"] = "CLEANED"
                        record["cleaned_at"] = time.time()
                        self.save(data)
                        result["status"] = "CLEANED"
                except (OSError, StorageError) as e:
                    result.update(status="BLOCKED", reason=str(e))
                results.append(result)
            return {"apply": apply, "results": results}

    def run(self, command, feature, kind, disposable, reserve_mib, retain_hours):
        if not command:
            raise StorageError("Missing foreground development command")
        # This cleanup has narrower authority than any general filesystem command.
        cleanup = self.gc(apply=True)
        if any(r["status"] == "BLOCKED" for r in cleanup["results"]):
            raise StorageError("Unsettled cleanup; inspect gc report")
        record, path = self.start(feature, kind, disposable, reserve_mib, retain_hours)
        env = os.environ.copy()
        temporary = path / "tmp"
        temporary.mkdir()
        env.update(HXAI_RUN_DIR=str(path), HXAI_QA_OUTPUT=str(path / "visual"),
                   TMP=str(temporary), TEMP=str(temporary), TMPDIR=str(temporary))
        digest, received, stored = hashlib.sha256(), 0, 0
        pid = None
        try:
            with (path / "command.log").open("xb") as log:
                child = subprocess.Popen(command, cwd=self.root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                pid = child.pid
                self.update(record["id"], child_pid=pid,
                            argv_sha256=hashlib.sha256(json.dumps(command).encode()).hexdigest(),
                            executable=Path(command[0]).name, source_head=git(self.root, "rev-parse", "HEAD").decode().strip())
                while chunk := child.stdout.read(65536):
                    received += len(chunk)
                    digest.update(chunk)
                    room = max(0, self.policy["log_limit_mib"] * MIB - stored)
                    log.write(chunk[:room])
                    stored += min(len(chunk), room)
                    if hasattr(sys.stdout, "buffer"):
                        sys.stdout.buffer.write(chunk)
                        sys.stdout.buffer.flush()
                code = child.wait()
                child.stdout.close()
            payload_bytes = scan(path, strict=True)["bytes"]
            violation = payload_bytes > self.policy["run_limit_mib"] * MIB
            try:
                self.check()
            except StorageError:
                violation = True
            self.update(record["id"], state="CLOSED", exit_code=code,
                        expires_at=time.time() + retain_hours * 3600, output_sha256=digest.hexdigest(),
                        output_bytes=received, log_complete=stored == received,
                        payload_bytes_before_cleanup=payload_bytes, storage_limit_exceeded=violation)
        except BaseException:
            self.update(record["id"], state="BLOCKED", child_pid=pid, reason="Foreground command interrupted or spawn uncertain; inspect writers before recovery")
            raise
        gc = self.gc(apply=True)
        if any(r["status"] == "BLOCKED" for r in gc["results"]):
            raise StorageError("Command ended but cleanup is blocked; no false success")
        if violation:
            raise StorageError("Observed storage limit exceeded; disposable payload reclaimed, command receipt retained")
        self.check()
        return code


def enforce(root):
    """Shared preflight for existing verification drivers; never deletes legacy data."""
    return Storage(root).check()


def install_hook(storage):
    configured = git(storage.root, "config", "--get", "core.hooksPath") if subprocess.run(
        ["git", "-C", str(storage.root), "config", "--get", "core.hooksPath"], capture_output=True).returncode == 0 else b""
    if configured.strip():
        raise StorageError("Existing core.hooksPath is protected; compose its hook manually")
    hooks = storage.common / "hooks"
    if linked(hooks):
        raise StorageError("Linked hooks directory")
    hooks.mkdir(exist_ok=True)
    path = hooks / "pre-commit"
    text = '#!/bin/sh\n# HXai storage gate v1\nexec python "$(git rev-parse --show-toplevel)/scripts/project_storage.py" check --staged\n'
    if linked(path):
        raise StorageError("Linked pre-commit hook is protected")
    if path.exists():
        if path.read_text(encoding="utf-8") != text:
            raise StorageError("Existing pre-commit hook will not be overwritten")
    else:
        path.write_text(text, encoding="utf-8", newline="\n")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
    return {"installed": str(path)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("report")
    check = sub.add_parser("check")
    check.add_argument("--reserve-mib", type=int, default=0)
    check.add_argument("--staged", action="store_true")
    check.add_argument("--new-worktree", action="store_true")
    gc = sub.add_parser("gc")
    gc.add_argument("--apply", action="store_true")
    run = sub.add_parser("run")
    run.add_argument("--feature", required=True)
    run.add_argument("--kind", choices=["build", "test", "visual"], required=True)
    disposition = run.add_mutually_exclusive_group(required=True)
    disposition.add_argument("--disposable", action="store_true", dest="disposable")
    disposition.add_argument("--preserve", action="store_false", dest="disposable")
    run.add_argument("--reserve-mib", type=int, default=1024)
    run.add_argument("--retain-hours", type=float, default=0)
    run.add_argument("command", nargs=argparse.REMAINDER)
    sub.add_parser("install-hook")
    args = parser.parse_args(argv)
    try:
        storage = Storage(args.root)
        if args.action == "report":
            result = storage.report()
        elif args.action == "check":
            result = storage.check(args.reserve_mib, args.staged, args.new_worktree)
        elif args.action == "gc":
            result = storage.gc(args.apply)
        elif args.action == "install-hook":
            result = install_hook(storage)
        else:
            command = args.command[1:] if args.command[:1] == ["--"] else args.command
            return storage.run(command, args.feature, args.kind, args.disposable, args.reserve_mib, args.retain_hours)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 2 if args.action == "gc" and any(r["status"] == "BLOCKED" for r in result["results"]) else 0
    except (StorageError, OSError) as e:
        print(json.dumps({"status": "BLOCKED", "reason": str(e)}, ensure_ascii=True), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
