# CHANGE_CONTRACT

FeatureID: G47-02-REFUTATION-20261104

用户故事：用户要求核验一个假恒等式并给出反例时，独立重算的 DISPROVED 证书和可见回复中的反例应完成该核验目标。它不能把假命题记成已证明，也不能让“请证明该式”在没有证明时成功。

base: `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`

入口：原 Chat `GenerationLoop` 结束时提交可见回复，`TaskPlanner.accept` 在工具计划未全部 VERIFIED 时先尝试 `acceptSettledRefutation`。

持久层：`AgentTaskStore.acceptRefutation` 写入 `refutation-delivery-v1` 证据，`claim_promoted=false`，`mathematics_status=DISPROVED`。RETURNED 的 `math_verify` 调用保留。`finish` 只在验收种类是 `refutation_delivery` 且未验证步骤全是 RETURNED math_verify 时提升为 SUCCEEDED。

权限：不新增工具权限，不把证书当授权。独立暂停、撤权、缺输入、设备确认仍阻断。

模型快照：不换 provider/model/reasoning。反例路径不再为该目标另发语义验收请求。

副作用：不重放工具，不删除 RETURNED 日志。

成功：目标含核验/是否/反例，声明原文出现在目标中，证书重算为带 counterexample 的 DISPROVED，且 `claim_status` 不是 NOT_EVALUATED，可见回复引用每个反例字段且不写“验证通过”。

失败：证明请求、缺反例、UNSUPPORTED、错误证书、回复改口、另有非数学工具效果，都保持未验收。

兼容：旧 `tool_verified` 仍只接受 VERIFIED。`response_delivery` 仍不能覆盖已派发工具。
