# TRACE_AND_DIFF

HEAD 是 `codex/storage-lifecycle-20261103` / `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`，工作树脏。本切片未提交。

- `CubicParameterGoal.chart` 去掉反斜杠后，把 `\sqrt a` / `\sqrt{a}` 收成 `sqrt(a)`，把 `\infty` 收成 `∞`。
- `quoted` 的三支区间不再用向后 32 字窗口。按句号切开后，外侧句必须含 `|x|>`、`x^2>`、`外侧` 或 `∞`；中间句必须含 `|x|<`、`x^2<`、`中间`，或含 `sqrt(a)` 且不含 `∞` 与 `|x|>`。
- `UncertifiedGoalTest.sealedUnsupportedPromptsCannotBeDeliveredAsChat` 锁住椭圆/直线、一致收敛、缺初值递推三则题面。
- `BackupExclusions` 拒绝 `extension-tool-grants-v1.db`、`extensions-v1.db`、`extension-oauth-v1.db`、`credentials-v1`、`agent-tasks-v1.db`、`ocr-cache-v1.json`。`buildBackup` 在写 manifest 前要求清单键名都不命中。

未改 `run_live.py`、密封 CASE 原文、旧 37 题分母。
