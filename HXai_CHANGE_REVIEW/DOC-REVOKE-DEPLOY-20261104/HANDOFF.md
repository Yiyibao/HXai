# Completed deployment handoff

Requested deployment and one-credential public acceptance completed. Live hashes and all status/cleanup facts are in TEST_RESULTS.json, bound to source manifest / FIXES.patch and confirmed independently by FINAL_READBACK.log. No requested deployment step remains.

The deployed files intentionally contain only the revocation-related subset of the repository changes. Their full-file hashes therefore differ from the user-provided complete repository files; dispatcher remains at its online hash. Do not advertise newer receipt, request lookup, quota, recalculation or schema features as deployed.

Synthetic task 65ddf923-2fa2-4aa6-b339-d51fef142444 is CANCELLED/authorization_revoked and its source remains under the service's existing normal retention lifecycle. Its formerly running worker and validator are absent. Temporary synthetic identity was administratively revoked; /var/lib/mathdoc-admin/credentials/revoke-smoke-20261104T022653Z.json is absent. Do not clean other credentials or manually alter /var/lib/mathdoc.

Backup and rollback instructions: VERDICT_AND_ROLLBACK.md. No service rollback occurred. No image rebuild or Nginx modification was needed. Do not rerun deployment blindly; the operator refuses source drift from the original before hashes. Independent external review is NOT_EVALUATED; scope is this authorized deployment and synthetic acceptance only.

All new local files are compact operations/evidence artifacts in this directory. Existing dirty tracked files, unrelated concurrent artifacts and client/app/google-services.json were left untouched. No commit/push/merge. Storage-managed disposable output reclamation is recorded in STORAGE_FINAL.json.
