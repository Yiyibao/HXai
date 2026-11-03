# HXai local task-protocol reference, v0.3.0 + Windows compatibility

This is executable local infrastructure, NOT a model-driven planner, a public API, a production cloud Agent, or Android-integrated functionality. It uses one SQLite database/global execution slot per user work domain, with multiple learner profile owners. Production multi-user deployment requires account isolation and authenticated adapters.

From this directory, using preinstalled Python and pytest:

```sh
python -m pytest -q
python -m hxai_agent.demo --output /tmp/hxai-demo-new-empty-directory
```

The demo refuses a nonempty output directory. It reads synthetic text, verifies an algebra chain exactly, writes a real immutable Markdown version, interrupts after the write, reopens the journal, reconciles the existing artifact, resumes explicitly without duplicate effects, and preserves a second version. It makes no network, model, device, OCR, or production call.

For a fresh, source-hashed evidence run from the repository root:

```sh
python scripts/verify_agent_runtime.py --output qa/runtime-new-run
```

The runner refuses an existing output directory and never writes to the original
`HXai_Pro_Delivery`. Nonzero exits, missing executables, timeouts and missing or
invalid demo evidence still produce `TEST_RESULTS.json`. Successful report
receipts are independently checked against actual bytes and hashes.

## Platform publication and evidence scope

POSIX publication uses a no-replace hard link followed by directory `fsync`.
Windows publication uses same-directory `MoveFileExW` with
`MOVEFILE_WRITE_THROUGH` and without `REPLACE_EXISTING` or `COPY_ALLOWED`, after
flushing the temporary file. Native errors are propagated; an uncertain
publication keeps the journal/slot until independently reconciled.

Windows reparse points/junctions at managed roots, ancestors, directories and
file leaves are rejected. Windows regression fixtures exercise real,
privilege-free directory junctions; they do not establish a privileged Windows
file-symlink test PASS. POSIX file-symlink coverage remains.

The exercised durability scope is trusted-local **process-crash recovery** and
independent file readback, not hardware power-loss, Android, cloud or production
verification. See [MoveFileExW documentation](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw)
and `qa/pro-compat-20261012/` for this checkout's evidence. The original delivery
and its historical negative controls remain unchanged.

## Implementation

`store.py`: immutable ModelLock, ordered plans with explicit acceptance predicates, owner/request idempotency, global fenced lease, PREPARED/IN_FLIGHT/verified receipts, independent authorization/manual pause/device/input blockers, conservative recovery, durable usage provenance. `runner.py`: trusted local registry with read/text-version/exact-math tools and independent readback. `math_verify.py`: bounded rational univariate polynomial identities/derivatives and equal-expression chains, not arbitrary proof grading. `demo.py`: reproducible local protocol E2E.

Schema version 2 uses application_id=1213743433. `migrations/001_task_runtime.sql` documents v1 and `002_resume_blockers.sql` documents the executed migration. Constructor validates the known old schema before migration and rejects unrelated/future databases. Stop executors before migration. Unknown old waiting prerequisites require explicit review; migration must not guess that credential renewal satisfies device or input blockers. New code, SQL migration, test fixtures and docs must be updated together.

## Trust boundaries

`restore_authorization`, `device_available`, `resume`, `reconcile_applied` and receipt verification are trusted-host operations. A model or unauthenticated network client cannot call them directly. `executor_stopped=True` is an adapter assertion that must be independently established; timeout or model text is not proof. Pending external effects retain the slot. No lease expiry automatically takes over. External network requests that may be billed must not be classified as harmless local READ.

Database arguments/results are not encrypted. Credential-shaped fields are rejected but that is not a general secret/DLP detector. Use an app-private, trusted filesystem root; do not expose writable directories to malicious same-user processes. There is no arbitrary Python/code execution tool. Files are bounded UTF-8/Markdown, not professional Office editing.

## Next integration

Port the protocol invariants into the existing Kotlin ChatService/GenerationLoop and native TaskStore rather than running parallel uncoordinated Agents. Preserve ConversationSession checkpoints and MaterialVersions. Add trusted provider/device/verifier adapters, vault-backed authorization, account-aware transport, and versioned Pigeon snapshots before claiming App or cloud Agent support. See the delivery's 02/03/11 documents.
