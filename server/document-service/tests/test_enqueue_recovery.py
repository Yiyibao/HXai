import contextlib
import os
import sqlite3
import time
import threading
import uuid
from pathlib import Path

import pytest

from api.store import Store


@pytest.mark.parametrize("failure", ["metadata", "insert", "commit"])
def test_failed_enqueue_compensates_files_and_allows_retry(tmp_path, monkeypatch, failure):
    store = Store(tmp_path)
    owner, _ = store.issue()
    options = {"extension": "pdf"}
    source = tmp_path / "uploads" / "synthetic.pdf"
    source.write_bytes(b"%PDF-synthetic")
    original_db = store.db
    original_write = Path.write_text
    if failure == "metadata":
        def write(path, *args, **kwargs):
            if path.name == "request.json":
                raise OSError("synthetic metadata failure")
            return original_write(path, *args, **kwargs)
        monkeypatch.setattr(Path, "write_text", write)
    elif failure == "insert":
        with store.db() as db:
            db.execute("CREATE TRIGGER fail_enqueue BEFORE INSERT ON jobs BEGIN SELECT RAISE(ABORT, 'synthetic insert failure'); END")
    else:
        failed = False
        @contextlib.contextmanager
        def fail_commit():
            nonlocal failed
            with original_db() as db:
                yield db
                if not failed:
                    failed = True
                    raise sqlite3.OperationalError("synthetic commit failure")
        monkeypatch.setattr(store, "db", fail_commit)
    with pytest.raises((OSError, sqlite3.Error)):
        store.enqueue(owner, "retry-key", "fingerprint", options, source)
    with original_db() as db:
        assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 0
    assert list((tmp_path / "jobs").iterdir()) == []
    monkeypatch.setattr(Path, "write_text", original_write)
    monkeypatch.setattr(store, "db", original_db)
    if failure == "insert":
        with store.db() as db:
            db.execute("DROP TRIGGER fail_enqueue")
    source.write_bytes(b"%PDF-synthetic")
    job = store.enqueue(owner, "retry-key", "fingerprint", options, source)
    assert (tmp_path / "jobs" / job["id"] / "input" / "source.pdf").read_bytes() == b"%PDF-synthetic"
    duplicate = tmp_path / "uploads" / "duplicate.pdf"
    duplicate.write_bytes(b"%PDF-synthetic")
    assert store.enqueue(owner, "retry-key", "fingerprint", options, duplicate)["id"] == job["id"]
    assert duplicate.exists()
    assert len(list((tmp_path / "jobs").iterdir())) == 1


def test_orphan_reconciliation_preserves_fresh_and_registered_jobs(tmp_path):
    store = Store(tmp_path)
    owner, _ = store.issue()
    source = tmp_path / "uploads" / "source.pdf"
    source.write_bytes(b"%PDF-synthetic")
    job = store.enqueue(owner, "registered", "fingerprint", {"extension": "pdf"}, source)
    registered = tmp_path / "jobs" / job["id"]
    old = tmp_path / "jobs" / str(uuid.uuid4())
    fresh = tmp_path / "jobs" / str(uuid.uuid4())
    unexpected = tmp_path / "jobs" / "not-a-job"
    for directory in (old, fresh, unexpected):
        directory.mkdir()
        (directory / "synthetic.pdf").write_bytes(b"%PDF-synthetic")
    stale = time.time() - 7200
    for directory in (old, registered, unexpected):
        os.utime(directory, (stale, stale))
    store.cleanup_orphan_jobs()
    assert not old.exists()
    assert fresh.exists() and registered.exists() and unexpected.exists()
    assert store.get(owner, job["id"])["state"] == "QUEUED"


def test_compensation_cannot_remove_outside_task_root(tmp_path):
    store = Store(tmp_path / "service")
    outside = tmp_path / str(uuid.uuid4())
    outside.mkdir()
    (outside / "synthetic.pdf").write_bytes(b"%PDF-synthetic")
    with pytest.raises(RuntimeError, match="invalid_cleanup_path"):
        store._remove_job_directory(outside)
    assert (outside / "synthetic.pdf").exists()


def test_reconciliation_waits_for_inflight_enqueue_commit(tmp_path, monkeypatch):
    store = Store(tmp_path)
    owner, _ = store.issue()
    source = tmp_path / "uploads" / "source.pdf"
    source.write_bytes(b"%PDF-synthetic")
    entered, release, cleaned = threading.Event(), threading.Event(), threading.Event()
    original_write = Path.write_text
    def blocked_write(path, *args, **kwargs):
        if path.name == "request.json":
            directory = path.parent.parent
            os.utime(directory, (time.time() - 7200, time.time() - 7200))
            entered.set()
            assert release.wait(5)
        return original_write(path, *args, **kwargs)
    monkeypatch.setattr(Path, "write_text", blocked_write)
    results, failures = [], []
    def enqueue():
        try:
            results.append(store.enqueue(owner, "inflight", "fingerprint", {"extension": "pdf"}, source))
        except BaseException as error:
            failures.append(error)
    def cleanup():
        try:
            store.cleanup_orphan_jobs()
        except BaseException as error:
            failures.append(error)
        finally:
            cleaned.set()
    writer = threading.Thread(target=enqueue)
    cleaner = threading.Thread(target=cleanup)
    writer.start()
    assert entered.wait(5)
    cleaner.start()
    try:
        assert not cleaned.wait(0.15), "Cleanup must serialize with the uncommitted enqueue"
    finally:
        release.set()
        writer.join(5); cleaner.join(5)
    assert not failures and not writer.is_alive() and not cleaner.is_alive()
    assert len(results) == 1
    assert (tmp_path / "jobs" / results[0]["id"] / "input" / "source.pdf").exists()


def test_cancelled_inputs_keep_quota_until_cleanup_and_duplicate_is_allowed(tmp_path, monkeypatch):
    import api.store as storage
    store = Store(tmp_path)
    owner, _ = store.issue()
    other, _ = store.issue()
    options, payload = {"extension": "pdf"}, b"%PDF-quota"
    reservation = storage.storage_reservation(options, len(payload))
    monkeypatch.setattr(storage, "OWNER_STORAGE_LIMIT", reservation * 2)
    monkeypatch.setattr(storage, "TOTAL_STORAGE_LIMIT", reservation * 3)

    def submit(identity, key):
        source = tmp_path / "uploads" / key
        source.write_bytes(payload)
        return store.enqueue(identity, key, "fingerprint", options, source)

    first = submit(owner, "first")
    store.cancel(owner, first["id"])
    second = submit(owner, "second")
    store.cancel(owner, second["id"])
    assert submit(owner, "first")["id"] == first["id"]
    with pytest.raises(ValueError, match="storage_quota"):
        submit(owner, "owner-over-limit")
    third = submit(other, "third")
    store.cancel(other, third["id"])
    with pytest.raises(ValueError, match="storage_quota"):
        submit(other, "global-over-limit")
    assert (tmp_path / "jobs" / first["id"] / "input/source.pdf").read_bytes() == payload

    with store.db() as db:
        db.execute("UPDATE jobs SET expires=0 WHERE id=?", (first["id"],))
    import dispatcher.main as dispatcher
    monkeypatch.setattr(dispatcher, "docker", lambda *args, **kwargs: None)
    dispatcher.cleanup(store)
    assert not (tmp_path / "jobs" / first["id"]).exists()
    assert store.get(owner, first["id"])["state"] == "EXPIRED"
    assert submit(owner, "after-cleanup")["state"] == "QUEUED"
    reopened = Store(tmp_path)
    with pytest.raises(ValueError, match="storage_quota"):
        source = tmp_path / "uploads" / "reopen"
        source.write_bytes(payload)
        reopened.enqueue(other, "reopen", "fingerprint", options, source)


def test_legacy_cancelled_inputs_receive_storage_reservations(tmp_path, monkeypatch):
    import api.store as storage
    store = Store(tmp_path)
    owner, _ = store.issue()
    options, payload = {"extension": "pdf"}, b"%PDF-legacy"
    source = tmp_path / "uploads" / "legacy"
    source.write_bytes(payload)
    job = store.enqueue(owner, "legacy", "fingerprint", options, source)
    store.cancel(owner, job["id"])
    with store.db() as db:
        db.execute("ALTER TABLE jobs DROP COLUMN reserved_bytes")
    reopened = Store(tmp_path)
    reservation = reopened.get(owner, job["id"])["reserved_bytes"]
    assert reservation >= storage.storage_reservation(options, len(payload))
    monkeypatch.setattr(storage, "OWNER_STORAGE_LIMIT", reservation)
    next_source = tmp_path / "uploads" / "next"
    next_source.write_bytes(payload)
    with pytest.raises(ValueError, match="storage_quota"):
        reopened.enqueue(owner, "next", "fingerprint", options, next_source)
