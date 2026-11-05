# DOC-REVOKE-DEPLOY-20261104

User authorization: deploy only the existing document identity revocation implementation to the explicitly named host; no new product interface, image build, Nginx change, database/schema migration, or application change.

Repository: branch `codex/storage-lifecycle-20261103`, HEAD/base `fac15db2f0be0336c0c7c5bfd2f2a4b2a30dcdbf`. Existing dirty files and review directories belong to prior work and will remain untouched. No commit or push is requested.

Entry and persistence: public `/documents/api/v1/identity/revoke` -> existing FastAPI handler -> Store token lookup and single-transaction identity deactivation / queued-running cancellation -> unchanged dispatcher cancellation path. Owner is derived exclusively from the synthetic bearer token. No profile, model, UI, capability, or schema change.

Deployment gate: confirm `/opt/mathdoc/service`, loaded active mathdoc-api and mathdoc-dispatcher, loopback-only 8766 and health status ok. QUEUED/RUNNING and all mathdoc-* containers must be absent before stopping or replacing anything. Recheck after stopping API, then stop dispatcher. Back up only replaced source under timestamped `/opt/mathdoc`, mode 0600, verify SHA-256. Start dispatcher then API.

Minimal sync: extract existing route / exception handler and Store authorization fences from hash-verified repository source, preserving online unrelated behavior. Do not deploy newer receipt, storage-quota, request-lookup, recalculation or worker-stop schema changes. Dispatcher remains byte-identical because its current cancel flag path already removes workers and validators.

Success: both units active, local/public health ok; anonymous and invalid-token revoke 401 without identity/token fields; synthetic upload 202 and queued/running; revoke 200 with only revoked:true; old token capabilities 401; read-only database proves CANCELLED/authorization_revoked and original file remains with same hash. If dispatched, independently observe corresponding container stopped/absent. Administrator CLI revokes only this temporary identity and its 0600 credential is removed.

Failure/recovery: clean only the exact synthetic identity, await its actual container exit, and restore backed-up source only after the queue/container idle gate; start dispatcher then API. Never restore SQLite or identity state. If unrelated work prevents safe rollback, stop further mutations and report the blocked stage and backup path. No real credentials/documents enter local files or logs. Full independent review remains NOT_EVALUATED; validation applies only to this deployment.
