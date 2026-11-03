# R27-06–09 quality protocol v1 (2026-10-28)

This additive continuation starts at AB28 commit `070f3d83c294ba0d4a2197d591055e75cff59ac3` in a separate managed worktree. It does not include or change concurrent R27-03–05 work. Authoritative original Pro requirements and math contracts 1–17 remain intact. Exact current source and executed evidence belong to `HXai_CHANGE_REVIEW/R27-06-09-QUALITY-20261028/`.

## R27-06: original Chat evaluation, not another executor

`prepare_r27_live_quality.py` reuses the fixed 32-question corpus and creates a separate public manifest with 5 original Agent scenarios. Only id/category/prompt reach the App. Reference arguments, tool certificates and expected answers remain outside the live manifest. Existing answers/references are not rewritten.

The explicitly gated `LiveQualityEvaluationTest` uses the production ChatService, Scheduler, immutable model snapshot, GenerationLoop, tools, material store and usage ledger. Each case has a new profile/conversation, memory and extensions disabled, a nonempty title and suggestions disabled. The accepted configuration is the user-selected `gpt-6.1-terra`, Responses, medium, `store=false`, at the provided endpoint. No fallback model is permitted. A one-shot random-nonce localhost channel supplies the credential directly into the existing encrypted settings/task vault; no key is a source file, command-line argument, exported answer or log.

Before possible dispatch, a unique intent is persisted. Original visible text, request usage, task identity/state, tool identities/input hashes and independently copied/hash-checked artifacts are retained. Hidden reasoning is not exported. Failed and unrun cases remain in the denominator. Unknown/unsettled physical execution stops subsequent dispatch; a known settled failure may be followed by a different case. Evidence directories cannot be reused, and neither timeout nor a missing ACK authorizes replay.

`import_r27_quality_answers.py` verifies answer, task/receipt/usage and artifact byte bindings and the pinned model before producing the existing scorer's RUN_INPUT. Its output is EXTERNAL_UNATTESTED: matching hashes and an origin label do not authenticate App execution, authorization, human judgments or mathematical correctness. Device/package/instrumentation evidence is separate. Then use the existing seal -> template -> score pipeline; references are author-derived and independent adjudication remains NOT_EVALUATED.

## R27-07: explicit proof-rule increment

Mathematical operations/contracts 1–17 retain their scopes. `ProofDerivation` adds three constructive rules:

| Rule | Ordered premises | Conclusion / dependency behavior |
|---|---|---|
| `or_elim` | P∨Q, P→R, Q→R | R; union of all premise dependencies |
| `modus_tollens` | P→Q, ¬Q | ¬P; union of both dependencies |
| `not_intro` | contradiction derived under a named hypothesis P | ¬P; remove only that hypothesis, retain all other dependencies |

Both disjunction branches and their conclusion must match; extra/missing premises are not silently accepted. Unknown premises keep UNKNOWN-prefix behavior. An invalid certificate does not refute its target proposition (`claim_status=NOT_EVALUATED`). `ProfessionalMathGuidance` exposes the actual new rule contracts to the same locked main model. Original theorem-condition, numeric-versus-proof and declared-subproblem scope limits remain.

## R27-08: meaningful transcription and OCR evidence

`MathTranscription.requireContent` is applied by MathWorkInputs before confirmation and during source-bound readback. Empty, whitespace/format-only or XML-invalid Unicode input is rejected without overwriting an old record. Ordinary Unicode, combining/joining text, symbols, supplementary characters and line breaks remain unchanged. Nonempty titles/ambiguity descriptions use the same gate. Candidate schema1/2 is not migrated; unusable old records remain on disk, and valid existing records still read.

WinRT remains an explicitly selected local evaluation engine, not Android's default OCR. Nonwhite pages without recognized text now retain an explicit warning; they are never inferred blank. Its PowerShell hash helper uses streaming .NET SHA256 so inherited PSModulePath cannot disable Get-FileHash. Error diagnostics emit only known codes, exception class/HRESULT and script line, never OCR text or arbitrary exception messages. The fixed five-page truth/source and language are not changed in response to recognition errors.

The independent OCR scorer rejects blank declarations that conceal a formula/LaTeX, table or image behind empty `text`. CER remains literal Unicode comparison, not mathematical equivalence or end-to-end quality.

`LiveOcrQualityTest` is a separately gated original DocumentServiceRepository path for the fixed CC0 five-page corpus (blank physical page 5). It does not replace the older LiveTest's different fixture. It captures actual capability/parser versions, source/task/remote IDs, downloaded document and source-bound candidate Word; no fake human confirmation or math approval. It does not delete unrelated remote work or retry unknown uploads. Current capability lookup found the existing MinerU 4.0.10 and LibreOffice 25.2.3.2 service ready; actual parsing/output success must still have its own evidence.

## R27-09: precise XLSX ownership

`SpreadsheetEditRegions` is consumed by both `MaterialEditor.excelCells` and `setCells`. A merged follower or array/data-table/shared formula region cannot be treated as an ordinary editable constant merely because its own cell lacks an f element. The legal merged anchor and unrelated ordinary constants remain editable; malformed/overlapping affected regions are rejected rather than guessed. Bounds are resource/format limits, not financial budgets.

Inspection adds optional `edit_blocked_reason` and preserves `editable=false`; no Pigeon/DTO migration. Editing rejects before output publication and preserves source bytes, styles, merges and formula ownership. Old cache invalidation/real recalculation requirements remain; structural checks cannot award Microsoft Office/WPS or rendering qualification. The Office PowerShell hash helper receives the same .NET compatibility fix. WPS has an existing user process and is not controlled; Microsoft Office regression uses only its own new processes and synthetic copies.

## Evidence and rollback

Room33, TaskStore13, authorization/model/owner/profile/epoch, global slot/fence, manual pause/revoke/blockers, UNKNOWN, material originals/new versions and deletion semantics are unchanged. Product UI/Host/Provider/Scheduler/GenerationLoop/build files owned by the concurrent cloud agent are not edited. The isolated TaskPlanner candidate adds five scoped instruction lines; its contextual patch must be integrated against the concurrent branch without replacing that file. AAR/Web resources used for targeted QA are copied, not rebuilt or represented as a new full frontend qualification.

The original failure logs remain, including the Web pretask that unexpectedly attempted dependency downloads and was stopped. Subsequent Gradle commands explicitly exclude that unchanged Web task and use copied existing static resources; offline targeted qualification is stated accordingly.

Rollback stops this evaluation/adapter or inverses only its precise feature delta. Preserve encrypted snapshots, all tasks/usage/receipts/unknown states, source/new artifacts and confirmed-input records. No schema downgrade or clearing/replaying unresolved actions. Development and adversarial checks are by one executor; independent/full-project review stays NOT_EVALUATED, and no subagent is used.

## Actual-response increments and results

Real Responses calls supplied irrelevant defaults for every optional math field, which the exact verifier correctly rejected. Math tool input protocol1 now presents two required fields, `{operation,request:JSON object string}`. Legacy operation fields go inside request; operation/request nesting and unrelated fields are rejected. Professional inputs retain their existing wire format. Historical direct inputs/receipts still verify without rehashing or rewriting them; new receipts bind the actual envelope hash and `tool_input_contract=1`. No permission, supported operation, mathematical scope or completion predicate is broadened.

The complete baseline contains 32 original math cases plus 5 public Agent cases, with 58 settled physical request records. All 37 engineering outcomes failed the original task-success requirement. Same-executor assessment of sealed math answers found 25 meeting their listed obligations and 7 undetermined; this is not authoritative or independent mathematical accuracy. The context-dependent HS-SEQUENCE-SAMPLING question remains undetermined rather than silently editing its prompt.

After the math wire/type fix, three bounded real regressions used the same locked model/reasoning and 15 settled physical requests. Identity reached SUCCEEDED with host acceptance. The conditional proof certificate verified, and Word/XLSX artifacts independently contained their requested markers/text/numeric values, but both final tasks remained COMPLETED_UNVERIFIED. Their plans/receipts remain available; neither valid tool results nor real files override the task predicate. No complete post-fix 32-question quality claim is made. Costs remain unknown, not zero.

Both actual OCR engines processed the unchanged public five-page corpus. Literal CER: WinRT zh-Hans-CN 41/93 = 44.086%; MinerU 46/93 = 49.462%. These are measured recognition failures, not engine-execution failures. The MinerU source-bound candidate Word opened/saved/reopened/exported in an owned MS Word process; the resulting PDF had 8 physical pages and all 5 original page images. Five existing Microsoft Office synthetic compatibility jobs also passed their independent readback scope. WPS, licensed real student handwriting and its independent symbol labels remain unavailable.

The live LibreOffice upload was accepted, then the response lacked rendererVersion. Preserve this actual unfinished intent; do not upload it again to fill the evidence. OfficeConversionRepository now saves the validated remote ID/state with `renderer_receipt_incomplete` before rejecting incomplete metadata. Repeated submit reuses that task; refresh clears the blocker only upon the same remote job's actual version receipt; unresolved download/artifact publication is blocked. No capability version substitutes for a task execution version. This changes no serialized schema, and does not silently recover the earlier pre-fix intent whose remote ID was lost. The existing deployed response contract requires reconciliation; no service deployment is included.

The final five-line planning/conditional-proof guidance candidate was also measured in a fresh two-case run; both final tasks remained COMPLETED_UNVERIFIED with 12 settled physical usage records. The added guidance did not resolve the whole-goal false negatives. Original failures, real positive certificates/files, and every run remain separately retained.

User-requested continuation is a separately versioned delta, `docs/r27-final-plan-repair-contract1.md` and `HXai_CHANGE_REVIEW/R27-06-FINALPLAN-20261028/`. It preserves these failed runs and adds guarded final planning, actual Document-producer binding and rejected-math context in whole-goal evidence. Do not apply this earlier batch's shared-file patch over that continuation or the concurrently developed cloud protocol.

Math tool wire1 imposes an explicit aggregate request-string resource limit of 65536 characters in addition to each mathematical operation's limits. Existing direct-input contracts and their receipts keep their previous verification limits; they are not silently migrated or rewritten to the new envelope. Resource UNSUPPORTED is not a mathematical disproof.
