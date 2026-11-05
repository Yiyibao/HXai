# 裁决

**PASS_SCOPED**

范围: JVM 上的密封题面文本门禁、三次含参区间写法、以及备份清单键名排除。不是 API26，不是 Mate50，不是 OCR 质量分，不是 live cohort。旧 37 题仍是 28 PASS / 9 FAIL，分母不改。独立审查 `NOT_EVALUATED`。

回滚: 恢复 `CubicParameterGoal.kt` 的窗口匹配，删除 `BackupExclusions.kt` 及其测试，去掉 `buildBackup` 的 `rejects` 检查。不要恢复已撤销的 grant 行，不要重跑未授权的 live cohort。
