# HXai task runtime: local protocol and opt-in network host

The local `TaskStore`/demo below remains the v0.3.0 + Windows compatibility reference. The opt-in `hxai_agent.service` network host adds an authenticated schema3 authority, task-scoped encrypted file-key vault, independently supervised model/tool worker and paired Android adapter. These are separate entry modes over the same task protocol, not two uncoordinated schedulers.

Current network/App contract, source map, safety limits and exact smoke commands: [R27 cloud/device protocol v1](../../docs/r27-cloud-device-protocol-v1.md). Actual loopback/owned-emulator evidence is in `HXai_CHANGE_REVIEW/R27-CLOUD-DEVICE-20261028/continuation-04-05-final/`. Real Provider quality, hosted TLS/KMS, target devices, optional cross-App automation and production are not certified by those synthetic runs. No server starts by default.

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

## Android and network integration

The opt-in adapter wraps the existing Kotlin ChatService/GenerationLoop, Scheduler, original tool/verifier and MaterialVersions. Native tasks also require network admission while bound; disabling a client cannot authorize replay of a delegated task. The worker shares the V6 planning/acceptance contract, journals physical Provider requests, and independently verifies actual artifacts. Native-only work waits for explicit foreground device confirmation.

Normal service transport requires TLS; the explicit HTTP exception is restricted to loopback QA. Enrollment and one-way domain retirement are trusted local operator commands, never public model tools. See the linked protocol before configuring any real host or transmitting credentials. This file-key backend is not a hardware KMS; the local store/database itself is not fully encrypted.

## R29 network extension (2026-10-29)

The current network database is schema **4**, not local schema 2. The exact
schema-3 migration adds `net_document_calls` without discarding receipts,
encrypted configurations, unknown effects or the global slot. RPC remains
protocolVersion 1 with additive `hosting.update`, `source.chunk` and artifact
chunk reads; Room33/native TaskStore13/Pigeon are unchanged.

Use `--cert-file` / `--tls-key-file` for the listener and, when its bind address
is not the certificate identity, `--worker-url https://<certificate-name>:<port>`
plus `--worker-ca-file <private-CA.pem>`. Worker TLS verifies hostname, expiry and
CA; it never infers a valid identity from `0.0.0.0` or silently disables checking.
The local IPv4 smoke covers real TLS, invalid CA/hostname/expired certificates
and authority crash while its original supervised worker remains alive.

Explicit UNTIL_REVOKED hosting retains the original goal, selected sources and
pinned model; it is not an unrestricted file or lifetime data grant. Duration
renewal can leave authorization EXPIRED and never clears pause/input/device
blockers or revocation. Credential validation is a separately journaled role.
An explicit same-scope recovery reuses known successful physical responses;
UNKNOWN and physically live instances cannot be superseded to bypass the slot.

Optional document hosting requires a separate encrypted, pinned document role.
Only explicitly selected binary versions may be uploaded or read. The trusted
tools reuse the original document API and validators; uploads are journaled
before dispatch and lost ACKs are resolved by authenticated original-request
lookup, not re-upload. Cancellation, terminal status and missing lookup are not
stop witnesses. Verified PDF/XLSX/text artifacts retain immutable source lineage;
unknown document-role usage/cost remains unknown/null.

From repository root, use new output directories:

```sh
python scripts/verify_r29_tls.py --run-dir .local/r29-tls-NEXT
python scripts/verify_r29_validation.py --run-dir .local/r29-validation-NEXT
python scripts/verify_r29_documents.py --run-dir .local/r29-documents-NEXT
```

These commands use only synthetic model credentials and owned loopback services.
The Office smoke requires the existing local LibreOffice installation; it does
not certify Docker isolation, MinerU quality or production. IPv6 qualification
is opt-in (`verify_r29_tls.py --ipv6`) and currently blocked by local Windows
`::1` connect WinError10013. See the [versioned protocol](../../docs/r27-cloud-device-protocol-v1.md)
and `HXai_CHANGE_REVIEW/R29-TLS-HOSTING-DEVICE-20261029/` for exact source/evidence
and external acceptance prerequisites. No real host, account or paid model is
authorized by these local smoke commands.
