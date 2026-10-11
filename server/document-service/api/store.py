"""Persistent, identity-scoped queue. Only the dispatcher starts containers."""
import contextlib
import hashlib
import json
import os
import secrets
import shutil
import sqlite3
import time
import uuid
from pathlib import Path

TERMINAL = {"SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "EXPIRED"}
OWNER_STORAGE_LIMIT = 512 * 1024 * 1024
TOTAL_STORAGE_LIMIT = 2 * 1024 * 1024 * 1024


def storage_reservation(options, source_bytes):
    output_limit = (30 if options.get("operation") == "office_pdf" else 64) * 1024 * 1024
    return source_bytes + output_limit + len(json.dumps(options).encode("utf-8"))


class Store:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "jobs").mkdir(exist_ok=True)
        (self.root / "uploads").mkdir(exist_ok=True)
        with self.db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS identities (id TEXT PRIMARY KEY, token_hash TEXT UNIQUE, active INTEGER NOT NULL DEFAULT 1);
                CREATE TABLE IF NOT EXISTS request_cancellations (owner TEXT NOT NULL, request_key TEXT NOT NULL, created REAL NOT NULL,
                    PRIMARY KEY(owner, request_key));
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, request_key TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, options TEXT NOT NULL, state TEXT NOT NULL,
                    created REAL NOT NULL, updated REAL NOT NULL, expires REAL NOT NULL,
                    error TEXT, manifest TEXT, cancel INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(owner, request_key));
            """)
            db.execute("BEGIN IMMEDIATE")
            if "reserved_bytes" not in {row["name"] for row in db.execute("PRAGMA table_info(jobs)")}:
                db.execute("ALTER TABLE jobs ADD COLUMN reserved_bytes INTEGER NOT NULL DEFAULT 0")
            for row in db.execute("SELECT id,options FROM jobs WHERE reserved_bytes=0 AND state!='EXPIRED'").fetchall():
                directory = self.root / "jobs" / row["id"]
                if directory.is_dir():
                    options = json.loads(row["options"])
                    input_directory = directory / "input"
                    input_bytes = sum(path.stat().st_size for path in input_directory.iterdir() if path.is_file()) if input_directory.is_dir() else 0
                    db.execute("UPDATE jobs SET reserved_bytes=? WHERE id=?", (storage_reservation(options, input_bytes), row["id"]))

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.root / "tasks.sqlite3", timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()
            if hasattr(os, 'geteuid') and os.geteuid() == 0:
                owner = self.root.stat()
                for name in ("tasks.sqlite3", "tasks.sqlite3-wal", "tasks.sqlite3-shm"):
                    path = self.root / name
                    if path.exists():
                        os.chown(path, owner.st_uid, owner.st_gid)
                        os.chmod(path, 0o660)

    def issue(self):
        owner, token = str(uuid.uuid4()), secrets.token_urlsafe(32)
        with self.db() as db:
            db.execute("INSERT INTO identities(id,token_hash) VALUES (?,?)", (owner, hashlib.sha256(token.encode()).hexdigest()))
        return owner, token

    def authenticate(self, token):
        with self.db() as db:
            row = db.execute("SELECT id FROM identities WHERE token_hash=? AND active=1", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        return row[0] if row else None

    def get(self, owner, job_id):
        with self.db() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=? AND owner=?", (job_id, owner)).fetchone()
        return dict(row) if row else None

    def enqueue(self, owner, key, fingerprint, options, staged):
        now, job_id = time.time(), str(uuid.uuid4())
        destination = self.root / "jobs" / job_id
        created = False
        try:
            with self.db() as db:
                db.execute("BEGIN IMMEDIATE")
                old = db.execute("SELECT * FROM jobs WHERE owner=? AND request_key=?", (owner, key)).fetchone()
                if old:
                    if old["fingerprint"] != fingerprint:
                        old_options = json.loads(old["options"])
                        # Renderer releases are server-owned execution metadata,
                        # not a change to the caller's conversion intent.
                        same_conversion = (
                            old_options.get("operation") == options.get("operation") == "office_pdf"
                            and {k: v for k, v in old_options.items() if k != "rendererVersion"}
                            == {k: v for k, v in options.items() if k != "rendererVersion"}
                        )
                        if not same_conversion:
                            raise ValueError("idempotency_conflict")
                    return dict(old)
                cancelled = db.execute("SELECT 1 FROM request_cancellations WHERE owner=? AND request_key=?", (owner, key)).fetchone()
                if cancelled:
                    db.execute("INSERT INTO jobs(id,owner,request_key,fingerprint,options,state,created,updated,expires,cancel) VALUES (?,?,?,?,?,'CANCELLED',?,?,?,1)",
                               (job_id, owner, key, fingerprint, json.dumps(options), now, now, now + 86400))
                    return dict(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())
                count = db.execute("SELECT COUNT(*) FROM jobs WHERE state IN ('QUEUED','RUNNING')").fetchone()[0]
                own = db.execute("SELECT COUNT(*) FROM jobs WHERE owner=? AND state IN ('QUEUED','RUNNING')", (owner,)).fetchone()[0]
                if count >= 10 or own >= 3:
                    raise ValueError("queue_full")
                reserved = storage_reservation(options, staged.stat().st_size)
                total_bytes = db.execute("SELECT COALESCE(SUM(reserved_bytes),0) FROM jobs WHERE state!='EXPIRED'").fetchone()[0]
                owner_bytes = db.execute("SELECT COALESCE(SUM(reserved_bytes),0) FROM jobs WHERE owner=? AND state!='EXPIRED'", (owner,)).fetchone()[0]
                if total_bytes + reserved > TOTAL_STORAGE_LIMIT or owner_bytes + reserved > OWNER_STORAGE_LIMIT:
                    raise ValueError("storage_quota")
                destination.mkdir(mode=0o700)
                created = True
                (destination / "input").mkdir()
                (destination / "output").mkdir()
                staged.replace(destination / "input" / ("source." + options["extension"]))
                (destination / "input" / "request.json").write_text(json.dumps(options), encoding="utf-8")
                db.execute("INSERT INTO jobs(id,owner,request_key,fingerprint,options,state,created,updated,expires,reserved_bytes) VALUES (?,?,?,?,?,'QUEUED',?,?,?,?)",
                           (job_id, owner, key, fingerprint, json.dumps(options), now, now, now + 48 * 3600, reserved))
        except BaseException:
            # The db context has rolled back before compensation. Never remove a
            # pre-existing idempotent job. A cleanup failure is retried by reconciliation.
            if created:
                with contextlib.suppress(OSError, sqlite3.Error):
                    with self.db() as db:
                        if db.execute("SELECT 1 FROM jobs WHERE id=?", (job_id,)).fetchone() is None:
                            self._remove_job_directory(destination)
            raise
        return self.get(owner, job_id)

    def _remove_job_directory(self, directory):
        if directory.parent != self.root / "jobs" or directory.is_symlink() or directory.resolve() != directory:
            raise RuntimeError("invalid_cleanup_path")
        shutil.rmtree(directory)

    def cleanup_orphan_jobs(self, grace_seconds=3600):
        now = time.time()
        # Serialize with enqueue through commit; even an old/slow in-flight upload
        # must not be mistaken for an orphan before its row is committed.
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            for directory in (self.root / "jobs").iterdir():
                try:
                    valid_id = str(uuid.UUID(directory.name)) == directory.name
                except ValueError:
                    valid_id = False
                if not valid_id or directory.is_symlink() or not directory.is_dir():
                    continue
                if now - directory.stat().st_mtime < grace_seconds:
                    continue
                if db.execute("SELECT 1 FROM jobs WHERE id=?", (directory.name,)).fetchone() is None:
                    self._remove_job_directory(directory)

    def cancel(self, owner, job_id):
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT state FROM jobs WHERE id=? AND owner=?", (job_id, owner)).fetchone()
            if not row:
                return False
            if row[0] not in TERMINAL:
                db.execute("UPDATE jobs SET cancel=1,state='CANCELLED',updated=?,expires=MIN(expires,?) WHERE id=?", (time.time(), time.time() + 86400, job_id))
        return True

    def cancel_request(self, owner, key):
        """Cancel even an upload whose acknowledgement was lost, without re-uploading."""
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("INSERT OR IGNORE INTO request_cancellations(owner,request_key,created) VALUES (?,?,?)", (owner, key, time.time()))
            row = db.execute("SELECT * FROM jobs WHERE owner=? AND request_key=?", (owner, key)).fetchone()
            if row and row['state'] not in TERMINAL:
                db.execute("UPDATE jobs SET cancel=1,state='CANCELLED',updated=?,expires=MIN(expires,?) WHERE id=?", (time.time(), time.time()+86400, row['id']))
                row = db.execute("SELECT * FROM jobs WHERE id=?", (row['id'],)).fetchone()
            return dict(row) if row else None

    def claim(self):
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM jobs WHERE state='QUEUED' AND cancel=0 ORDER BY created LIMIT 1").fetchone()
            if row:
                db.execute("UPDATE jobs SET state='RUNNING',updated=? WHERE id=?", (time.time(), row["id"]))
        return dict(row) if row else None

    def finish(self, job_id, state, manifest=None, error=None):
        now = time.time()
        with self.db() as db:
            db.execute("UPDATE jobs SET state=?,manifest=?,error=?,updated=?,expires=MIN(expires,?) WHERE id=? AND state='RUNNING' AND cancel=0",
                       (state, json.dumps(manifest) if manifest else None, error, now, now + 86400, job_id))
