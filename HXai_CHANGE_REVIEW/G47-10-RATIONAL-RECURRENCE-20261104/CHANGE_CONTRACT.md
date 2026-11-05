# CHANGE_CONTRACT

FeatureID: G47-10-RATIONAL-RECURRENCE-20261104

用户故事：数列 `a_0=0`，`a_(n+1)=((n+1)/(n+2))*a_n+(n+1)`，求通项并证明。正确闭式 `a_n=n(n+2)/3` 必须能被精确证书核验；错误闭式不得通过；定义域上分母有整数零点不得当成已证或已否证。

base: `a90bc8eb4bb81dceb47c593898edd1d6c3e3fad1`。head: 本批未提交改动，不另开工作树。

入口：`MathVerification.verify` → `professional_induction`。多项式递推保持原 `proof_kind`。非常数除法才进入有理线性证书。宿主只在题面是「求通项并证明」且回复写出唯一可核闭式时，从空计划文本路径完成；已有工具计划或已派发调用不由该文本路径擦除。

schema：请求字段仍是 `start/initial/next/claimed`。新 `proof_kind` 为 `rational_linear_recurrence_exact_base_and_step`。不新增 operation，不改 Room schema。

成功：定义域完整且初值、清分母后的归纳步都是零多项式。失败：恒等失败为 DISPROVED；非线性、`u` 在分母、分母在整数域上为零、资源超限为 UNSUPPORTED。UNSUPPORTED/DISPROVED 都不完成任务。

回滚：删除本批新文件，恢复 `MultivariateCertificate.induction`、宿主三处接线与 guidance 句子。
