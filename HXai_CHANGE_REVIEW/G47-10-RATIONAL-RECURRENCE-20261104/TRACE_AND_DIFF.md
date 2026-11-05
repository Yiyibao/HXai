# TRACE

入口：`MathVerification.verify` → `ProfessionalMath.verify` → `MultivariateCertificate.induction`。多项式解析抛出 `multivariate_nonzero_constant_divisor_required` 时才进入 `RationalLinearRecurrence.verify`。

宿主：`TaskPlanner.accept` 在 `responseEligible` 时识别 `RationalRecurrenceGoal`，重算后调用 `AgentTaskStore.acceptRationalRecurrence`。已有工具计划或已派发调用不会走这条文本路径。

| 符号 | 作用 |
|---|---|
| `RationalLinearRecurrence.verify` | 清分母恒等，并证明语法分母在 `n>=start` 无整数零点 |
| `RationalRecurrenceGoal.parse/claim` | 只提取「求通项并证明」和回复里唯一的 `a_n=` |
| `TaskPlanner.acceptRecognizedRationalRecurrence` | 假模型可见回复不是证书 |
| `AgentTaskStore.acceptRationalRecurrence` | 空计划、无非 provider 调用才落验收 |

`ExtensionRuntimeTest` 恢复了被授权行替换掉的局部 `read()`。否则 `assembleDebugAndroidTest` 编不过，仪器测试无法启动。
