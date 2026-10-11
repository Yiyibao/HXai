# Scoped safety patch, 2026-10-12

Base: `5d826d5d7d8755ed42db834d6cd6dc52ae914c69`.

This PR is a small, merge-reviewable subset of the HXai Pro delivery. It does NOT implement a complete cloud Agent, Android standalone TaskStore, new UI, or release build.

## Changes

* Recheck active identity atomically at enqueue, including slow parse/Office uploads and idempotent receipt retrieval.
* Atomically revoke identity and cancel queued/running jobs; fence legacy revocations at claim and finish. Existing files/storage reservations remain until normal cleanup.
* Return generic HTTP 401 through the shared FastAPI exception handler.
* Remove recognized OCR text/local URLs from logs and preserve CancellationException rather than converting it to model input.

Revocation prevents new admissions after its transaction commits. It does not synchronously kill an already-dispatched worker; the existing dispatcher observes cancellation. Production deployment has NOT been performed.

## Actual local evidence

Exact original Store blob `59a9a1c7282f094d796be67c790eb4f3e92a6ea6` was reconstructed and Git-blob-hash verified. Two targeted baseline tests failed on the original and passed after the patch. The added `test_revocation_fence.py` passed 15 cases using SQLite and HTTPX ASGI transport, including upload-time revocation for parse/Office, late completion, identity isolation, reopen, and repeated claim/revoke races. Inputs are synthetic signatures, NOT actual MinerU/Office render tests.

Run the targeted suite from `server/document-service`:

```sh
python -m pytest -q tests/test_revocation_fence.py
```

Full repository pytest, Android compile/lint, actual OCR provider calls, device tests, signing/Release, Docker workers and production gateway are NOT_RUN for this PR. OCR is source-reviewed, not an Android-runtime acceptance result. No full-project completion percentage is claimed.

The downloadable delivery retains original failing logs, fixed logs, additional offline task/math/Kotlin code and integration instructions. Those additional items are NOT included in this PR.

Rollback: revert this feature commit on a separate branch after stopping affected workers under authorized operations. Do not restore old active credentials, delete task databases, or erase retained material files. No schema change is required by this patch.
