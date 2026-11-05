# 裁决

**PASS_SCOPED**

范围：有理线性递推证书，以及「求通项并证明」的空计划文本宿主路径。证据是 JVM 7 通过和 API26 假模型 `HostAcceptanceRuntimeTest` 7 通过。不是 sealed 真模型重跑，不是 Mate50、Release 或生产。独立审查 `NOT_EVALUATED`。产品总体仍是 CONDITIONAL。

回滚：删除 `RationalLinearRecurrence.kt`、`RationalRecurrenceGoal.kt`、对应测试和文档，恢复 `MultivariateCertificate.induction`、`TaskPlanner.accept`、`AgentTaskStore`、`AgentPlan.validate`、guidance 与 planner prompt。`ExtensionRuntimeTest` 的 `read()` 是编译修复，回滚证书时不要再删掉。
