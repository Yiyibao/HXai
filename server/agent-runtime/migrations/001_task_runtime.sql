
CREATE TABLE IF NOT EXISTS tasks (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT,
 id TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, request_key TEXT NOT NULL,
 intent_hash TEXT NOT NULL, goal TEXT NOT NULL, model_json TEXT NOT NULL,
 plan_json TEXT NOT NULL, acceptance_json TEXT NOT NULL, require_evidence INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','VERIFYING','STOP_REQUESTED','WAITING_INPUT',
  'WAITING_DEVICE','WAITING_AUTHORIZATION','PAUSED_BY_USER','FAILED_BLOCKED',
  'SUCCEEDED','CANCELED','UNCERTAIN_SIDE_EFFECT')),
 revision INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0,
 queue_order INTEGER NOT NULL, fence INTEGER NOT NULL DEFAULT 0,
 manual_pause INTEGER NOT NULL DEFAULT 0, cancel_requested INTEGER NOT NULL DEFAULT 0,
 authorization TEXT NOT NULL CHECK(authorization IN ('ACTIVE','EXPIRED','REVOKED')),
 auth_epoch INTEGER NOT NULL DEFAULT 1, capabilities_json TEXT NOT NULL,
 device_confirmation INTEGER NOT NULL DEFAULT 0,
 reason TEXT, result_json TEXT, created REAL NOT NULL, updated REAL NOT NULL,
 UNIQUE(owner,request_key)
);
CREATE TABLE IF NOT EXISTS execution_slot (
 singleton INTEGER PRIMARY KEY CHECK(singleton=1), task_id TEXT REFERENCES tasks(id),
 fence INTEGER NOT NULL DEFAULT 0, queue_clock INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO execution_slot(singleton) VALUES (1);
CREATE TABLE IF NOT EXISTS calls (
 task_id TEXT NOT NULL REFERENCES tasks(id), call_id TEXT NOT NULL,
 tool TEXT NOT NULL, capability TEXT NOT NULL, effect TEXT NOT NULL,
 arguments_hash TEXT NOT NULL, arguments_json TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('PREPARED','IN_FLIGHT','SUCCEEDED','UNKNOWN','NOT_DISPATCHED','FAILED_SAFE')),
 fence INTEGER NOT NULL, auth_epoch INTEGER NOT NULL,
 result_json TEXT, verification_json TEXT, reason TEXT,
 created REAL NOT NULL, updated REAL NOT NULL, PRIMARY KEY(task_id,call_id)
);
CREATE TABLE IF NOT EXISTS events (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES tasks(id),
 owner TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL,
 metadata_json TEXT NOT NULL, created REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS usage (
 task_id TEXT NOT NULL REFERENCES tasks(id), request_id TEXT NOT NULL,
 provider_id TEXT NOT NULL, model_id TEXT NOT NULL,
 provenance TEXT NOT NULL CHECK(provenance IN ('provider_reported','estimated','unknown')),
 input_tokens INTEGER, output_tokens INTEGER, cached_tokens INTEGER,
 created REAL NOT NULL, PRIMARY KEY(task_id,request_id)
);
CREATE INDEX IF NOT EXISTS task_queue ON tasks(state,priority,queue_order);
CREATE TRIGGER IF NOT EXISTS immutable_model BEFORE UPDATE OF model_json ON tasks
WHEN NEW.model_json != OLD.model_json BEGIN SELECT RAISE(ABORT,'model_lock_immutable'); END;

PRAGMA user_version=1;
