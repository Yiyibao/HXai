# Chat Rendering v4

用户实测待优化问题（2026-10-11）：卡片、方案比较、One Dark 代码块、图表坐标、数学绘图失败、概览布局及成果修改后显示 JSON。测试编号、实现与回归入口见 [专项问题记录](intelligent-chat-display-feedback-20261011.md)。这些反馈待后续专项处理，既有测试通过不代表视觉验收完成。

## Scope

The Flutter intelligent-chat renderer accepts versions 1-4. Existing saved surfaces keep their original behavior. New tools are instructed to prefer version 4. Kotlin validates definitions before persistence; Flutter validates again before rendering. Definitions are declarative data, not executable HTML, JavaScript or Dart.

## Content Catalog

| Type | Fields |
| --- | --- |
| `rich_text` | `text`: Markdown with the existing LaTeX renderer |
| `heading` | `text`, optional `level`: 1, 2 or 3 |
| `metric` | `label`, `value`: number/string; optional `unit`, `delta` |
| `callout` | `text`; optional `title`, `tone`: neutral/info/success/warning/error |
| `steps`, `comparison` | `items`: 1-12 `{title,text}` records |
| `code_block` | `code`; optional `language`, `collapsed`; display/copy only |

Existing controls, calculations, images, tables and charts remain available. v4 `text` and `card` also render Markdown. Unknown syntax-highlighting languages fall back to plain text.

## Styles and Composition

- Root `presentation`: `inline` (v4 default) or `panel`; `density`: `comfortable` or `compact`.
- Root `template`: `analysis`, `comparison`, `explanation` or `exploration`. Explicit composition takes precedence.
- Component `variant`: `plain`, `subtle`, `emphasis` or `outline`; `tone`: neutral/info/success/warning/error; optional component `density` overrides the root.
- Root `composition`: strings reference component IDs; nodes use `type`: column/row/grid/tabs/accordion and `children`.
- Rows accept integer `weights` (1-12, one per child). Grids accept `columns` (1-3). Child groups in tabs/accordion have `label`.
- Each component must appear exactly once. Composition is limited to 64 nodes and 5 levels. `layout` and `composition` are mutually exclusive.
- Narrow rows/grids collapse into single-column layouts. Tabs keep form controls alive; accordion state is local.

```json
{
  "version": 4,
  "title": "Budget comparison",
  "components": [
    {"id": "actual", "type": "metric", "label": "Actual", "value": 1240, "unit": "USD", "variant": "subtle", "tone": "info"},
    {"id": "plan", "type": "metric", "label": "Planned", "value": 1400, "unit": "USD", "variant": "subtle", "tone": "success"},
    {"id": "note", "type": "callout", "text": "**160 USD** below the plan.", "tone": "success"}
  ],
  "composition": {"type": "column", "children": [{"type": "grid", "columns": 2, "children": ["actual", "plan"]}, "note"]},
  "presentation": "inline"
}
```

## Charts, Tables and Images

- Charts accept either `points` or `series`: 1-6 `{label,points}` series, at most 500 points total. Line/bar/scatter use fl_chart. Optional `xUnit`, `yUnit`, `digits` (0-6), and up to 8 `annotations`: `{axis:"x"|"y",value,label}`.
- Legends toggle visibility locally and cannot hide the last series. Selection uses the original concatenated point index, including hidden series. Tooltips include series names and units.
- Reference lines are supported on line/scatter charts. Bar y-references are lines; bar x-references are presented in the annotation legend.
- Table `columnTypes`: text/number/currency/percent/date; optional `units`, `digits`, and up to 32 `highlight` cells: `{row,column,tone}`. Row/column indices are zero-based. Highlights remain tied to original rows after sorting. Percentage values are fractions (`0.12` displays as `12%`).
- Headers stay fixed while the bounded body scrolls. Wide tables scroll horizontally with a visible scrollbar. Sorting is local presentation state, not a model submission.
- Image options: `aspectRatio` (0.2-5), `fit` (contain/cover), `sourceUrl` (http/https), `sourceTitle`; fullscreen supports pan/zoom. Image URLs must come from user input or tools, not invented sources.

## Progressive Rendering

Emit `version`, `title`, then `components`, with root styling/composition afterward. The preview accepts direct definitions and the tool's escaped `definition` string. Each complete component goes through the normal catalog validator; incomplete or invalid values stay placeholders. Finished component widgets are retained while subsequent data arrives.

Preview is restricted to automatically approved tools on a RUNNING message. It never shows inputs, action buttons, image requests or reference-dependent calculations. It does not load/save state or submit actions. Only completed native tool results enter the persistent interactive renderer. Cancellation, interruption, failure and approval-pending states fall back to the existing tool card.

Frame-coalesced prefix parsing is bounded to 196608 input characters / 65536 decoded characters / 24 preview components. Control edits refresh only dependent components; root layout/density changes retain existing edits. Schema/content changes still reload validated state.

## Verification

See `qa/rendering-upgrade-20261010/report.md` for per-batch review, test results and screenshot evidence. Real-provider output and phone installation remain acceptance checks outside the automated suite.
