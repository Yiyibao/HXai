# 裁决

**PASS_SCOPED**

范围：API26 模拟器上的普通管理入口授予、调用、撤销、旧代次拒绝、再授权；宿主三次含参区间写法；圆锥参数交点与一致收敛保持未核验。不是 Mate50，不是 Linux 沙箱故事，不是 live cohort。独立审查 `NOT_EVALUATED`。

`SandboxMcpChatStoryTest` 仍是 `NOT_RUN`。它要外部容器和 `code_sha256` / `sandbox_token` / `sandbox_port`，本轮没有伪造这些参数。

回滚：删除 `ordinaryManagementEntryGrantsCallsRevokesAndRejectsTheOldEpoch`、两个宿主新用例，以及 `UncertifiedGoalTest` 的解析器空断言。不要回滚 grant 表或已有证书。
