# TRACE

HEAD `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`，分支 `codex/storage-lifecycle-20261103`。本切片未提交，叠在已有脏树上。

入口：空计划、无已派发调用时，`TaskPlanner.accept` 在有理递推之后识别三次含参题面，宿主重算证书，回复必须逐字包含三格符号表，再由 `AgentTaskStore.acceptCubicMonotonicity` 落库。可见回复不是证书。已有工具计划或已派发调用不走这条文本路径。

| 符号 | 位置 |
|---|---|
| `CubicParameterMonotonicity.verify` | `client/app/src/main/java/me/rerere/rikkahub/feature/math/CubicParameterMonotonicity.kt:9` |
| `MultivariateCertificate.verify` 分发 | `client/app/src/main/java/me/rerere/rikkahub/feature/math/MultivariateCertificate.kt:143` |
| guidance | `client/app/src/main/java/me/rerere/rikkahub/feature/math/ProfessionalMathGuidance.kt:7` |
| `CubicParameterGoal.parse` | `client/app/src/main/java/me/rerere/rikkahub/feature/agent/CubicParameterGoal.kt:17` |
| `TaskPlanner.acceptRecognizedCubicMonotonicity` | `client/app/src/main/java/me/rerere/rikkahub/feature/agent/TaskPlanner.kt:367` |
| `AgentTaskStore.acceptCubicMonotonicity` | `client/app/src/main/java/me/rerere/rikkahub/feature/agent/AgentTaskStore.kt:1007` |
| `AgentPlan.validate` | `client/app/src/main/java/me/rerere/rikkahub/feature/agent/AgentPlan.kt:17` |
| API26 | `client/app/src/androidTest/java/me/rerere/rikkahub/feature/agent/HostAcceptanceRuntimeTest.kt:117` |

表达式捕获停在第一个非算式字符，避免把「在实数域的单调性」吃进多项式。
