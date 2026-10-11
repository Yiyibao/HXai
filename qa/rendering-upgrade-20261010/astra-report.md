# Astra configuration and live follow-up

Date: 2026-10-10 (Asia/Taipei). Configuration outcome: **PASS**. Online acceptance: **PARTIAL / blocked by service rejection**. This follow-up uses the user's explicitly selected third-party endpoint, not the official OpenAI endpoint.

## Applied configuration

- Base URL: `https://xinyue.mom/v1`; protocol: Responses; model: `gpt-6-astra` (confirmed by an authenticated model-catalog query).
- Current emulator app assistant reasoning level: `low`. Response storage requested: `store: false`. This is a request parameter, not an assurance about the third-party service's retention practices.
- Applied to `org.hxsi.agent.debug` on `emulator-5554`, not to Codex's own configuration. No new model was silently substituted. The earlier `gpt-5.5` selection was superseded by the user's Astra instruction before configuration.
- Unrelated providers and models were retained. The explicitly selected endpoint's credential was updated through the production SettingsStore and Android Keystore-backed credential vault.
- Private transfer input was deleted before committing configuration. The final configuration test also asserted the key does not occur in plaintext preferences. Key content is not stored in source, instrumentation arguments, QA evidence or this report.
- Configuration tests passed: [astra-configuration-2.log](astra-configuration-2.log), [astra-configuration-3.log](astra-configuration-3.log). An earlier transport quoting mistake produced no configuration file and no network request; its failed configuration test is retained in `astra-configuration.log`.

## Online observations

- Initial full v4 request: **FAILED**, approximately 190 seconds. The service rejected the request before any partial tool arguments or visible preview were observed. The sanitized native error was HttpException, HTTP status/rejection classification unavailable. Do not infer rate limits or an application rendering defect from that generic result.
- Run UUID: `1d09beb5-7cd3-4407-9f68-ddbc88299e9f`. [Runtime](astra-live-runtime-1.log), [result](astra-live-run-1/qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/1d09beb5-7cd3-4407-9f68-ddbc88299e9f/result.json).
- Single short-text desktop probe: HTTP 200 with Responses lifecycle and text-delta events, but no completed terminal response within its bounded observation. Result **FAIL / bounded_stream_limit**, approximately 72 seconds. The soft 60-second stream check is evaluated on receipt of a line; blocking socket reads can extend elapsed observation. This does not establish Android continuation or cancellation. [Probe result](astra-desktop-probe.json).

## Focused continuation run

- Mode: `live-continuation-from-real-output`. Uses the verified synthetic v4 definition generated previously by `gpt-6.1-sol`, source run `348b2f62-5d90-40e7-b37b-472557e8386f`, SHA-256 `5afae99550988847e95831ee8607f0508a2f6fab08e26d6b6abf5cfcf04f8559`.
- This isolates real Astra continuation after UI editing from regeneration of the complete component catalog. Reusing the definition is not evidence that Astra generated it.
- Local emulator steps completed during this run: inline/fullscreen render, slider edit, dependent calculation, code expansion, persistence across recreation and dark/large-text table display.
- Final instrumentation outcome: **FAILED**, approximately 140 seconds. The online continuation was rejected with native classification `RATE_LIMIT`; HTTP status was unavailable. This classification can represent frequency limits or insufficient quota, so the exact cause is not established.
- The local UI steps and exact persisted state check succeeded (`people = 2.4499999999999997`, displayed 2.45); online state acknowledgement did not complete. The subsequent cancellation request was **NOT RUN** because continuation failed first. No further model requests were issued after RATE_LIMIT.
- Run UUID: `6e39c247-7ab8-4115-8ad2-d3547005e4f0`. [Runtime](astra-resume-runtime-1.log), [result](astra-resume-run-1/qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/6e39c247-7ab8-4115-8ad2-d3547005e4f0/result.json), [dark large-text screenshot](astra-resume-run-1/qa-evidence/ChatRenderingRealModelTest.configuredModelStreamsRendersEditsRestoresContinuesAndCancels/6e39c247-7ab8-4115-8ad2-d3547005e4f0/table-dark-large-text.png).
- Both failed online runs were collected using immutable method/run manifests, installed APK hashes, timestamps and evidence-file hashes. Their actual FAILED outcomes were preserved.
- Cleanup restored the pre-test profile and appearance while retaining the user's newly authorized Astra/low provider configuration. No physical handset was tested.

## Test self-review

- Opt-in configuration fixture consumes only an app-private input and redacts parse failures. Old Authorization/Host assistant headers and model/reasoning/storage body overrides are removed for the selected configuration.
- Real continuation now verifies the numerical returned state, not just the existence of a marker. Cancellation only passes stream verification after observing tool arguments or reasoning; cancelling an unresponsive network wait is not sufficient.
- Android instrumentation APK builds passed. Shared evidence-verifier suite: 9 passed. Bounded-probe/redaction suite: 5 passed.
- Scan of changed test/probe source and rendering evidence found no key-shaped secret strings. No production rendering code was changed in this follow-up.
- Prior four-batch implementation/regression results remain in [report.md](report.md); initial model/emulator acceptance is in [live-report.md](live-report.md).

## Remaining action

Check this third-party service's Astra availability, account quota/credit and limits before rerunning online continuation and stream cancellation. Do not report either as passed based on model discovery, partial text events or local replay. The configured model is retained as requested; no protocol fallback or silent model substitution was used.
