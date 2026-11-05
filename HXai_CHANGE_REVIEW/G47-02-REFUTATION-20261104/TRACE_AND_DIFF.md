# TRACE_AND_DIFF

HEAD 开工时 `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`。本批未提交。

## 现场 9 个工程 FAIL

仪器门禁只把 `SUCCEEDED` 记 PASS。模型请求都成功。

- `AGENT_MISSING_INPUT`、`HS-SEQUENCE-SAMPLING`：`WAITING_INPUT`。缺输入或“同上”没有上下文，产品停住是对的，不改成成功。
- `HS-SEQUENCE`、`HS-SEQUENCE-TELESCOPING-FULL`、`HS-PARAM-CUBIC-FULL`、`HS-CONIC-PARAM-INTERSECTION`、`ANALYSIS-UNIFORM-NOT-DERIVATIVE`：文本论证或未覆盖的完整讨论停在 `COMPLETED_UNVERIFIED`。现有核验器没有对应证书，不强制成功。
- `HS-CONIC-IDENTITY`：恒等式已核验，语义验收因证据包没有可见解释而拒绝。本批把已提交回复放进语义证据，标记为 `durable_visible_reply_not_certificate`，仍不能当数学证书。
- `AGENT_FALSE_COMPLETION`：`math_verify` 否定 `(x+1)^2=x^2+1`，回复给出 x=1 的反例，但唯一计划步骤是 RETURNED，`FinalPlanRepair` 要求另有 VERIFIED 步骤，语义验收没跑到。这是本批修复点。

## 改动

- `RefutationDelivery`：只接受带 counterexample 的 DISPROVED，拒绝 `claim_status=NOT_EVALUATED`、UNSUPPORTED、VERIFIED，以及回复中的“验证通过”。目标必须是核验/是否/反例，且声明原文在目标里。
- `TaskPlanner.acceptSettledRefutation`：重算证书，读回已提交回复，再写验收。
- `AgentTaskStore.acceptRefutation` / `acceptancePromotes`：保留 RETURNED 调用；只有反例交付计划才能在这些步骤未 VERIFIED 时完成。
- `GenerationLoop`：有可见文本回复时都先提交，避免 conflate 丢掉最后一条。
- 语义验收提示增加回复字段不是证书的约束。

未改：不把 DISPROVED 升成 VERIFIED，不删日志，不放宽 `tool_verified`。
