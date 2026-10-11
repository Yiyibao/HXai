# Intelligent Chat rendering upgrade

Scope: four sequential batches requested on 2026-10-10. Existing worktree changes are preserved. This report records checks performed in this run, not historical project-wide acceptance.

## Batch 1: Rich text and semantic components

- Added version 4 while retaining versions 1-3. Kotlin and Flutter validate the same semantic fields.
- Added rich text, headings, explicit metrics, callouts, steps, comparisons, and highlighted code with copy/collapse controls.
- Reused Markdown/LaTeX rendering. Updated model tool instructions to advertise the new schema.
- Self-review repairs: version-gated math compatibility, rich text in v4 cards, strict heading/tone validation, selectable code and bounded code scrolling, removed conflicting v3-first tool guidance.
- Flutter targeted suite: 18 passed. Post-repair semantic suite: 3 passed. Targeted analysis: no errors/warnings; 36 existing flow-control style infos in the legacy renderer.
- Kotlin protocol tests: passed (7 tests). Unknown-language highlight fallback and exact clipboard source: passed. Batch 1 complete before Batch 2 edits.

## Batch 2: Style and layout

- Added semantic variants, tones and density, inline/panel presentation, four templates, and bounded composition trees (rows with ratios, columns, grids, tabs, accordion).
- v4 defaults to unframed inline content; loading/errors remain visible. Legacy surfaces retain panel behavior.
- Self-review repairs: Material ancestor for decorated surfaces, accordion PageStorage collision with descendant scroll offsets, minimum widths based on column ratios, per-component themed text colors.
- Flutter targeted suite after initial repairs: 23 passed. Kotlin protocol suite: 8 passed. Additional ratio/depth/template repairs verified before Batch 3.

## Batch 3: Charts and tables

- Added fl_chart-based v4 multi-series line/bar/scatter views, legend visibility, global point selection, units, formatted tooltips and reference annotations. Legacy chart rendering remains compatible.
- Added typed/sortable table columns, original-row cell highlights, pinned headers, horizontal scrollbar and bounded body scrolling. Image ratio/fit/source/fullscreen support added.
- Self-review repairs: legend state across equivalent snapshots, reset sorting when columns change, selection mapping after hiding series, semantic highlight colors, nonduplicated tick spacing and edge labels, dark tooltip contrast. Visual test font loading repaired.
- Flutter targeted regression: 28 passed, then 6 chart/visual checks after repairs. Kotlin protocol suite: 9 passed. Four actual PNGs inspected at 390/960 pixels in light/dark mode.
- Scope detail: scatter uses the chart library's zero-width line series with dots; bar x-reference labels are shown in the annotation legend (the library supports horizontal reference lines on bars).

## Batch 4: Progressive rendering

- Added bounded, frame-coalesced partial JSON decoding and component-by-component previews. Only complete catalog-validated passive components render; incomplete controls stay placeholders. Approved completed tool results retain the existing persistent renderer.
- Cached finished preview components and added dependency-targeted updates for interactive controls/calculations/charts. No preview persistence, submission or remote-image requests.
- Self-review repairs: previews require RUNNING message state (stopped messages cannot spin forever); strict integer versions and preview count bounds; presentation/composition/layout changes refresh styling without discarding edits; completed preview nodes remain stable across stream updates.
- Flutter progressive suite: 7 passed, covering character prefixes, escapes, unsafe/referenced nodes, stop/approval states, update coalescing, independent component identity and state-preserving style updates. Batch 4 reviewed before final verification.

## Final verification

- Full Flutter suite: 205 passed after a final comparison-layout repair. Android app unit suite: 367 passed in 56 suites, 0 failures/errors/skips.
- Final self-review repair: many comparison items wrap into readable columns instead of all sharing a single row; regression test added and full Flutter suite rerun.
- Flutter analysis: no errors or warnings; informational style diagnostics remain in existing and touched files.
- Actual mobile/wide light/dark PNGs inspected. Numeric sorting, original-row highlights, hidden-series selection, theme contrast and large-text/narrow-layout scenarios covered by tests.
- Final dual-ABI Flutter debug AAR rebuilt after the last renderer edit. Android `:app:assembleDebug` succeeded.
- ARM64, x86_64 and universal debug APKs each contain all 32 matching current Flutter assets; SHA-256/source evidence is in `apk-arm64.json`, `apk-x86_64.json` and `apk-universal.json`.
- Deliverable: `client/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`; universal APK is in the same directory. Rendering protocol and examples: `docs/chat-rendering-v4.md`.
- The verification above was completed before live model/emulator acceptance; it does not itself establish that coverage.

## Live acceptance follow-up

- Reused the existing Android emulator and its configured `gpt-6.1-sol` / OpenAI / Responses model without replacing credentials or clearing app data.
- Real model generation and v4 schema validation succeeded. Emulator interaction, editing, persistence, code expansion, and dark/large-text checks passed in a separately identified no-network replay of that real output.
- Overall live acceptance is PARTIAL: the configured service subsequently returned `RATE_LIMIT`; online continuation and cancellation remain unverified. No physical handset was tested.
- Full scope, failed attempts, hashes and evidence: [live-report.md](live-report.md).
- Subsequent user-authorized configuration: `gpt-6-astra`, reasoning `low`, Responses on `xinyue.mom`. Configuration passed; online continuation was rejected with `RATE_LIMIT` and cancellation remains unverified. [Astra follow-up](astra-report.md).
