# 反例交付 v1

核验目标可以因独立重算的 DISPROVED 证书和可见回复中的反例而完成。这不证明被否定的命题。

必须同时满足：目标要求核验、是否或反例；被核声明原文出现在目标中；证书重算为带 counterexample 的 DISPROVED，且 `claim_status` 不是 `NOT_EVALUATED`；回复引用每个反例字段，且不包含“验证通过”。

RETURNED 的 `math_verify` 保留。暂停、撤权、缺输入和设备确认仍优先。`tool_verified` 仍只接受 VERIFIED。证明请求不会因反例而成功。
