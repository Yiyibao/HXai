# 有理线性递推证书 v1

`professional_induction` 在非常数除法失败后，才认证 `u(n+1)=A(n)*u(n)+B(n)`。`A`、`B` 和闭式的分母只能是 `n` 的多项式。证书比较初值，并把归纳步清分母后的差做成多项式恒等。

每个语法除数在约分前都要在全部整数 `n>=start` 上非零。有零点、`u` 的非线性项或分母中的序列值返回 `UNSUPPORTED`，不把未定义递推判为假。恒等失败才是 `DISPROVED`。有限项相符不是证明。

`proof_kind` 为 `rational_linear_recurrence_exact_base_and_step`。多项式递推仍用原来的 `mathematical_induction_exact_polynomial_base_and_step`。

题面是「求通项并证明」、给出 `a_0` 和非负整数域、且没有圆锥/单调/讨论/「同上」时，宿主从可见回复提取唯一的只含 `n` 的 `a_n=` 闭式并重算。回复不是证书。已有工具计划或已派发调用不由这条文本路径擦除。
