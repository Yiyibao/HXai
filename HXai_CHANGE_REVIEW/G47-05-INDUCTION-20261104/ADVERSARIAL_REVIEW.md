# ADVERSARIAL_REVIEW

同一执行者复审，不是独立审查。

假成功：错误闭式 `a_n=n` 重算为 DISPROVED，不写验收。缺少“非负整数”、含“同上/圆锥/是否”的题面不解析。回复没写出闭式不完成。`induction_delivery` 不能挂到工具步骤上。

正确输入被拒：`a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2。` 解析为 `u+2*n+1` / `n^2`，宿主重算 VERIFIED。尚未在模拟器上重放原 Chat，sealed 的 `HS-SEQUENCE` 不改标。

旧路径：已有 `professional_induction` 工具合同不变。反例交付不再把英文 `disproved` 子串当成核验请求，避免现有“disproved is not success”题面被误完成。
