# Real model and Android emulator acceptance

Date: 2026-10-10. Overall outcome: **PARTIAL**, not full online end-to-end acceptance.

## Environment and safety

- Existing AVD: `mathstudy-s0`, `emulator-5554`, Android API 34, x86_64, 1080 x 2340, density 420.
- Existing configured model: `gpt-6.1-sol`; provider metadata: OpenAI; protocol: Responses. These are configuration identifiers, not independent verification of the remote service's model identity.
- App: `org.hxsi.agent.debug`. Current x86_64 app APK SHA-256: `ddb777db0982cb2db1b969c58252e58a5f47bfdf43936f645452bc51f8ea6e5a`.
- No app data reset, credential dump, automatic model fallback or further online retry after the rate-limit rejection. Tests used isolated synthetic QA conversations. Original profile, assistant/model settings and appearance were restored in test cleanup.
- This follow-up changed the instrumentation harness and evidence collector, not production rendering code. No physical handset was tested.

## Actual online observations

- Runs 3, 4, 5 and 6 completed real generation and validated the v4 definition through native production validation. The generated surface included rich text/math, headings, metrics, callouts, steps, comparisons, code, a two-series chart, typed table, slider, calculation and continuation button.
- Runs 3 and 5 observed visible progressive preview and respectively 107 and 105 partial tool-argument snapshots. Runs 4 and 6 received only 2 and 1 snapshots; transport buffering makes the observed cadence variable.
- These complete instrumentation invocations remained FAILED: early test harness failures included ambiguous expansion-label matching, offscreen interaction and overly strict stream-timing assertions. Those assertions/helpers were repaired; the failed logs and manifests were retained, not relabeled PASS.
- Run 2 observed partial arguments but ultimately failed with an HttpException. That run did not preserve a safe rejection reason; its service-side cause remains unknown.
- Run 7 failed with the sanitized rejection reason `RATE_LIMIT` and message `Model service rejected the request`; HTTP status was unavailable. The application's classification can represent either frequency limits or insufficient quota, so the exact cause is not established.
- Online continuation using the edited state and online stream cancellation remain **NOT RUN / UNVERIFIED** after this rejection. No additional network requests were made in the replay.

## Emulator replay of real output

- Result: **PASS (1 instrumentation test)**, explicitly `real-output-replay-no-network`.
- Replay source: successful real generation in run `348b2f62-5d90-40e7-b37b-472557e8386f` (the complete source test invocation itself was FAILED).
- Source definition SHA-256: `5afae99550988847e95831ee8607f0508a2f6fab08e26d6b6abf5cfcf04f8559`.
- Passed through the actual production native tool, Flutter host bridge and emulator UI, not a screenshot mock or substitute model response.
- Checked inline/fullscreen rendering, code accordion expansion, accessible slider editing, dependent calculation, save/close, activity recreation, identical persisted state and light/dark appearance at 1.5x text scale.
- Slider state changed from 2 to approximately 2.45 (raw floating-point state `2.4499999999999997`); displayed calculation changed from 220 to 265. Reopening preserved the exact stored state.
- Screenshots were inspected for code highlighting, visible nonblank chart series, readable table/content and dark/large-text layout. Wide table content uses horizontal scrolling; not all columns fit at once.
- Replay UUID: `6b0d7b02-8e54-4212-b20b-5e24edbf9d5b`. Online continuation/cancellation fields are explicitly `NOT_RUN_PROVIDER_RATE_LIMIT`.

## Evidence and test review

- Harness: `client/app/src/androidTest/java/me/rerere/rikkahub/service/ChatRenderingRealModelTest.kt`.
- Collector: `scripts/collect_rendering_run.py`; reuses the strict existing verifier for APK hashes, method UUID, timestamps, terminal outcome and evidence-file hashes. Shared collector unit suite: 9 passed. Collector syntax compilation and Android test APK build passed.
- UI helper coordinates are scoped to the AVD geometry above, not a general physical-device automation guarantee.
- Final [replay result](device-replay-1/qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/6b0d7b02-8e54-4212-b20b-5e24edbf9d5b/result.json) and [runtime log](device-replay-1.log).
- Screenshots: [slider/calculation](device-replay-1/qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/6b0d7b02-8e54-4212-b20b-5e24edbf9d5b/slider-edited.png), [expanded code/chart](device-replay-1/qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/6b0d7b02-8e54-4212-b20b-5e24edbf9d5b/code-expanded.png), [dark large-text table](device-replay-1/qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/6b0d7b02-8e54-4212-b20b-5e24edbf9d5b/table-dark-large-text.png).
- Online evidence directories: `live-run-2` through `live-run-7`; logs: `live-runtime-2.log` through `live-runtime-7.log`. Each collected manifest retains its actual FAILED terminal outcome.

## Remaining acceptance

Restore availability/credit for the configured service, or explicitly select another already configured model, then rerun online continuation with edited state and cancellation while streaming. Until then, local emulator acceptance and real initial generation are established, but the complete online flow is not.

Subsequent user-authorized Astra/low configuration and additional online attempts are recorded separately in [astra-report.md](astra-report.md). The configuration passed, but that service also rejected continuation with `RATE_LIMIT`; it did not close the remaining acceptance items.
