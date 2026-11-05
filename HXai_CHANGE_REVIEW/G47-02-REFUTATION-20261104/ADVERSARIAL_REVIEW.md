# ADVERSARIAL_REVIEW

同一执行者复审，不是独立审查。

假成功：证明请求 `证明 (x+1)^2=x^2+1` 不能绑定反例交付。`claim_status=NOT_EVALUATED`、UNSUPPORTED、VERIFIED 证书不能当反例。回复写“验证通过”即使带上数字也拒绝。讲义编辑目标即使含“是否”也不含声明原文，拒绝。`refutation_delivery` 不能挂到工具步骤上冒充 `tool_verified`。

正确输入被拒：核验目标、声明原文、重算 DISPROVED 和回复中的 x=1/4/2 可以通过纯函数。尚未在模拟器上重放原 Chat，所以不能把 sealed 的 `AGENT_FALSE_COMPLETION` 改标为 PASS。

旧数据：不改 schema，不删 RETURNED 行，不把旧 DISPROVED 升成 VERIFIED。其它阻碍仍优先于完成。
