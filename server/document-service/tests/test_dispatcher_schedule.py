import importlib
import os
import sys
import time
import types
import uuid

import pytest

from api.store import Store


def test_main_runs_startup_and_periodic_orphan_cleanup(tmp_path, monkeypatch):
    # Windows has no flock; only the platform lock and Docker are replaced.
    # The production main loop, cleanup, Store and SQLite remain real.
    if os.name == "nt":
        monkeypatch.setitem(sys.modules, "fcntl", types.SimpleNamespace(
            LOCK_EX=1, LOCK_NB=2, flock=lambda *_: None))
    dispatcher = importlib.import_module("dispatcher.main")
    monkeypatch.setattr(dispatcher, "ROOT", tmp_path)
    monkeypatch.setattr(dispatcher, "docker", lambda *_, **__: None)
    monkeypatch.setattr(dispatcher.subprocess, "run", lambda *_, **__: None)
    monkeypatch.setattr(Store, "claim", lambda _: None)
    store = Store(tmp_path)
    owner, _ = store.issue()
    source = tmp_path / "uploads" / "synthetic.pdf"
    source.write_bytes(b"%PDF-synthetic")
    job = store.enqueue(owner, "scheduled", "fingerprint", {"extension": "pdf"}, source)
    registered = tmp_path / "jobs" / job["id"]
    # Store controls the source path; preserve its exact bytes and DB state.
    registered_files = {str(p.relative_to(registered)): p.read_bytes()
                        for p in registered.rglob("*") if p.is_file()}

    def orphan(stale):
        directory = tmp_path / "jobs" / str(uuid.uuid4())
        directory.mkdir()
        (directory / "synthetic.pdf").write_bytes(b"%PDF-orphan")
        if stale:
            os.utime(directory, (time.time() - 7200,) * 2)
        return directory

    startup, fresh = orphan(True), orphan(False)
    periodic = None
    cycles = 0

    class StopLoop(Exception):
        pass

    def sleep(_):
        nonlocal cycles, periodic
        cycles += 1
        assert registered.exists() and fresh.exists()
        assert not startup.exists()
        assert store.get(owner, job["id"])["state"] == "QUEUED"
        assert registered_files == {str(p.relative_to(registered)): p.read_bytes()
                                    for p in registered.rglob("*") if p.is_file()}
        if cycles == 1:
            periodic = orphan(True)
        else:
            assert not periodic.exists()
            raise StopLoop()

    monkeypatch.setattr(dispatcher.time, "sleep", sleep)
    with pytest.raises(StopLoop):
        dispatcher.main()
    assert cycles == 2


def test_main_periodic_cleanup_preserves_running_job_and_registered_bytes(tmp_path, monkeypatch):
    if os.name == "nt":
        monkeypatch.setitem(sys.modules, "fcntl", types.SimpleNamespace(
            LOCK_EX=1, LOCK_NB=2, flock=lambda *_: None))
    dispatcher = importlib.import_module("dispatcher.main")
    monkeypatch.setattr(dispatcher, "ROOT", tmp_path)
    monkeypatch.setattr(dispatcher, "docker", lambda *_, **__: None)
    monkeypatch.setattr(dispatcher.subprocess, "run", lambda *_, **__: None)
    store = Store(tmp_path)
    owner, _ = store.issue()
    source = tmp_path / "uploads" / "synthetic-running.pdf"
    source.write_bytes(b"%PDF-running-qa")
    job = store.enqueue(owner, "running", "fingerprint", {"extension": "pdf"}, source)
    registered = tmp_path / "jobs" / job["id"]
    os.utime(registered, (time.time() - 7200,) * 2)
    baseline = {str(p.relative_to(registered)): p.read_bytes()
                for p in registered.rglob("*") if p.is_file()}
    executed = []

    class StopLoop(Exception):
        pass

    def execute(actual_store, row):
        # Real main/claim/SQLite/cleanup; external worker execution is substituted.
        assert row["id"] == job["id"]
        assert actual_store.get(owner, row["id"])["state"] == "RUNNING"
        dispatcher.cleanup(actual_store)
        assert registered.exists()
        assert baseline == {str(p.relative_to(registered)): p.read_bytes()
                            for p in registered.rglob("*") if p.is_file()}
        assert actual_store.get(owner, row["id"])["state"] == "RUNNING"
        executed.append(row["id"])
        actual_store.finish(row["id"], "FAILED", error="synthetic_worker_terminal")

    def sleep(_):
        assert executed == [job["id"]]
        assert registered.exists()
        assert store.get(owner, job["id"])["state"] == "FAILED"
        raise StopLoop()

    monkeypatch.setattr(dispatcher, "execute", execute)
    monkeypatch.setattr(dispatcher.time, "sleep", sleep)
    with pytest.raises(StopLoop):
        dispatcher.main()
