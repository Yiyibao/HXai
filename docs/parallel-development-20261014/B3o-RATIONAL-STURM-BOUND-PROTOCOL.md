# B3o — 原始定义域内的有理函数闭区间绝对误差界 / 合同15

适用本批 `HXai_CHANGE_REVIEW/B-RATIONAL-STURM-BOUND-20261019/` 的SOURCE_FINAL/TEST_RESULTS，base/head为5d826d5d7d8755ed42db834d6cd6dc52ae914c69，实际产品含未提交域增量；不可仅用HEAD冒充被测版本。保留原Pro原文和合同1–14。本批无任务/权限/模型/Room/sidecar/Pigeon/UI迁移。

## 真实入口和输入

`mathVerifyTool` → `MathVerification.verify` → `RationalSturmBound.verify` → `RationalFunctionArithmetic`原语法域/`ClosedIntervalDomain.certify`/`PolynomialSturmBound.nonnegative`。新共享纯数学助手`ClosedIntervalDomain`从既有`RationalPrimitiveIntegral`域检查提取，积分继续使用完全相同字段/顺序/拒绝语义；B3n判号算法仅开放内部复用入口，无算法/旧合同变更。真实A主Chat Factory/Registry等仍由A统一接线，累计候选不是已启用产品事实。

```json
{"operation":"rational_uniform_bound_sturm","expression":"1/(x+1)","approximation":"0","lower":"0","upper":"1","claimed_bound":"1"}
```

恰好六个字符串字段。两式为现有单变量Q(x)闭合语法：整数、x、四则、括号、非负整数幂；每式4096字符/256节点/64层，规范分子和分母各≤32次，中间≤64次、系数1024bit。端点和界是1024bit有限整数/分数字面；a≤b，claim≥0。额外owner/max_depth等或非字符串字段拒绝。主模型、授权、任务成功不由数学输入或输出控制。

## 原域条件优先于化简值

两条原式在整个[a,b]均须有定义。保留除数约分前分子的非零条件、嵌套除法继承条件、零次幂非零条件；原分母、近似分母和差的规范分母也独立列入闭区间核验。多项式按单首规范去重，保留首次出现顺序。逐条件输出平方自由多项式、完整Sturm序列、端点是否根/变号数、开区间根数0。

任一条件在端点或内部有根即`original_domain_not_defined_on_closed_interval`/UNSUPPORTED，无部分证明。`x/x`、`0/(x-1)`、相同含极点表达式的零误差、同端点的孔洞也不得放过。无理孔洞同样查根，不靠有理采样。孔洞在请求区间外、无实根分母和合法单点仍支持。未知不判给定界数学错误，不执行连续补值/极限。

## 双侧符号证明

差的规范有理函数记N/D。已证明D在整个闭区间非零，因此D²严格正：

- claim-error≥0 ⇔ (claim·D−N)·D≥0；
- claim+error≥0 ⇔ (claim·D+N)·D≥0。

这里不是未经证明地乘未知符号分母。负分母不需要额外猜测/翻号分支。两侧乘后多项式均≤32次；超过时`bound_obligation_degree_limit`/UNSUPPORTED，即使平方自由后较低也不扩大本合同范围。沿用精确完整闭区间Sturm非负证明（原值判号，重数仅为根计数去除），含所有端点/不同实根及非空无根符号单元；点值只用于根完备后定号，不是采样证明。

## 输出与独立验收

合同15、domain=Q(x)、scope=`finite_rational_interval_uniform_absolute_bound_on_both_original_rational_domains`；绑定原输入SHA/AST/资源。完整结果包含source/approximation/error规范有理函数、两原式语法非零条件、interval_domain_checks、closed_interval.every_point_defined、确切端点/界、polynomial_reduction、upper/lower_obligation。

- 两侧整个闭区间非负：status/claim_status=VERIFIED，verified=true，仅本给定界。
- 发现原域内精确有理违反点：DISPROVED，counterexample含x、source_value、approximation_value、error、absolute_error，且|error|>claim。
- 原域不全定义/语法/度数/系数/隔离/输出限额：UNSUPPORTED，verified=false；不发布任一侧或domain_checks部分证书，不授任务成功。

不求最优界、不推广一般函数定理/去孔补值/反常积分。`MathVerification.accepts`保持从持久原输入重算、除notice外所有键值精确比较；模型自报或篡改原域/分母/单侧证明不能获证。

## 资源和回滚

现根隔离深度256、每侧8192 work节点、4096bit坐标及精确求值限额。取消贯穿解析间隔、原域/Sturm和隔离循环。复用保守escaped JSON长度计算，不额外序列化整份证书，1MiB上限并为公共元数据留8192字符；超限整体拒绝。不是对所有32次输入承诺必定成功，系数增长可以更早未知。

本批改动及共享候选可独立回滚；旧积分及B3n恢复本批baseline，不清库/删材料/丢回执。共享累计候选替代B3n旧候选，由A三方核对后合入；Tool直调不能冒充主Chat全目标验收。

## 可执行复跑

`python scripts/verify_b_rational_bound.py --worktree .local/b3b-rational-worktree --cases HXai_CHANGE_REVIEW/B-RATIONAL-STURM-BOUND-20261019/golden/FINAL_CASES.json --run-dir .local/b3o-rational-bound-replay-new`

run-dir必须新。独立脚本使用闭合AST/Fraction原域、规范差和分母平方恒等、域Sturm根数、两侧全部根/符号单元及原域精确反例，检查真实Kotlin或Android落盘结果。实际构建/Android证据见本批TEST_RESULTS；主Chat共享候选、API26实际设备、Release/Mate50、真实Provider/生产及独立外部/完整项目审查不由文档授PASS。
