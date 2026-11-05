# 对抗复审

同一执行者，不是独立审查。

假成功：`n*(n+2)/2` 为 DISPROVED，且 `step_holds=false`。API26 回复 `a_n=n` 保持 `COMPLETED_UNVERIFIED`，disposition 计数为 0。`(n/n)*u+(n+1)` 在 start=0 是 UNSUPPORTED，不因为约分后看起来有定义就 VERIFIED。`u*u` 和 `u` 在分母中也是 UNSUPPORTED，不是 DISPROVED。

正确输入被拒：封存工具调用 `next=((n+1)/(n+2))*u+(n+1)`、`claimed=n*(n+2)/3` 为 VERIFIED。同一递推在 start=1、闭式 `1/n` 为 VERIFIED。API26 可见回复写出 `\frac{n(n+2)}{3}` 时任务 SUCCEEDED，证明种类是新证书，数学调用数为 0。旧多项式归纳仍保持原 `proof_kind`。

残余：两种代数相同但字符串不同的闭式会被宿主拒绝。已派发的 `math_verify` 不由文本路径擦除，也不由它补完成。裂项求和的叙述本身不是证书。
