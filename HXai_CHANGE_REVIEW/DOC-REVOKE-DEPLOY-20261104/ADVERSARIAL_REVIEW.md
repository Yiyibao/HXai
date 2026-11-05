# Scoped same-executor adversarial review

False success / wrong recovery: local real ASGI and SQLite readback reject an old token after revoke; late finish cannot publish a manifest or resurrect the task; a previously authenticated owner cannot recover its old receipt after revoke; twenty existing claim/revoke race iterations preserve cancellation. Production acceptance separately requires both database cancellation and exact Docker worker/validator absence; database state alone never grants the stop claim.

Valid input / existing data: a second active owner remains authorized, ordinary enqueue returns 202, original source bytes remain unchanged, normal active idempotent and cancellation receipts still work, reopen preserves cancellation, and SQLite columns remain identical to the online schema. Existing queue limits remain in force. Source backups contain only replaced Python files; rollback never restores identities or tasks.sqlite3.

Operational counterexamples: source hash/anchor drift, a nonempty queue, any mathdoc-* container, a symlink source, an existing credential path, unsafe credential permissions, non-generic unauthorized response, extra successful revoke response fields, failed service readiness, unconfirmed Docker stop or mismatched original hash abort the operation. Source restoration is refused while unrelated jobs/containers exist or current source has drifted outside the reviewed before/after hashes. No blind image rebuild, job retry, token logging or host guessing occurs.

Full App, real learning-profile credentials, document rendering fidelity and independent external review are outside this authorized deployment. Independent review: NOT_EVALUATED. Production results are recorded separately in TEST_RESULTS.json and DEPLOYMENT.log.
