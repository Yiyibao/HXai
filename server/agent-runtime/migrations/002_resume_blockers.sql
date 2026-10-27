-- Apply only to the recognized offline reference runtime v1, with executors stopped.
ALTER TABLE tasks ADD COLUMN blocked_on TEXT;
UPDATE tasks SET blocked_on=CASE
 WHEN device_confirmation=1 OR state='WAITING_DEVICE' THEN 'WAITING_DEVICE'
 WHEN state IN ('WAITING_INPUT','WAITING_AUTHORIZATION','PAUSED_BY_USER') THEN 'WAITING_INPUT'
 ELSE NULL END;
-- v1 could have lost a prerequisite while authorization was unavailable.
-- Preserve data and require explicit review rather than guessing it is satisfied.
UPDATE tasks SET reason='migration_requires_dependency_review'
 WHERE state IN ('WAITING_AUTHORIZATION','PAUSED_BY_USER') AND blocked_on='WAITING_INPUT';
UPDATE tasks SET state='WAITING_DEVICE'
 WHERE state='QUEUED' AND device_confirmation=1;
PRAGMA application_id=1213743433;
PRAGMA user_version=2;
