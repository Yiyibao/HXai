# B3f — 有理方程完整实解集同解合同 v6

来源：Pro inputs/04 P2-A严格条件/解答批改与inputs/13 MATH-03/MATH-05；继承B3a–B3e，不改原规范，不替代高考/大学/分析/实变最终需求。精确baseline/head/rawSHA在HXai_CHANGE_REVIEW/B-RATIONAL-EQUIVALENCE-20261015。

## 输入与兼容

同一math_verify、MathVerification.verify/accepts新增`rational_equation_equivalence`。严格字段operation/before_left/before_right/after_left/after_right五字符串，比较before_left=before_right与after_left=after_right各自原实定义域中的全部实解。只本操作允许五顶层字段参与bounded input hash；旧操作字段/合同1–5/域语义保持。contractVersion6、domainQ(x)、scope`rational_equation_equivalence_over_original_real_solution_sets`。

语法仍单变量整数/四则/括号/非负整数字面幂；不支持任意附加假设、参数、多变量、超越/复数域和一般证明。不是把所有输入的方程等价判断都启用。

## 精确实解集表示

每个方程独立解析两侧，保存原left/right_nonzero_polynomials，不因约分、乘零、嵌套倒数、零次幂抹掉条件。差式正规值为N/D；平方自由原排除并E包含原双侧条件和差式正规分母。

- N非零：解是多项式`squareFree(N)/gcd(squareFree(N),E)`的全部实根，solution_kind=`finite_real_roots`，包括空集。此多项式可以含无实根因子，不凭次数误判有实解。
- N恒零：解是R去掉E的全部实根，solution_kind=`complement_of_finite_real_roots`，包括全实轴和穿孔全域。

before/after各给双侧正规值、原条件、正规差式、E及root_set_polynomial。定义域可以不同，只要各自域内的解完全相同就可同解；不能先取两方程域交集丢掉增根/失根。

## 同解证明与反证

有限和余有限类型必不同：非零多项式实根有限，而其补集无限，输出DISPROVED、proof_kind=`finite_vs_cofinite_real_solution_sets`及双方精确表示，不伪造有限对称差。

类型相同时，取root_set多项式的gcd，各自除去共有因子。有限类型中before独有因子是removed_solutions，after独有因子是added_solutions；余有限类型方向相反。每个因子输出多项式、严格Cauchy界、Sturm序列/两端变号数、完整实根计数、精确有理点/隔离区间、工作节点。只有两侧差的实根数都为0才VERIFIED/equivalent=true；实根隔离证书不是浮点近似，复根不作增失实解。

固定边界：x/x=1与x=x不同解（0增根）；x²=1与x=1失−1；x²=−1与1=0均空集同解；1=1与(x²+1)/(x²+1)=1同解；1=1与(x²−2)/(x²−2)=1不同解；(x²−1)/(x−1)=0与x+1=0同解（−1），不能因为自然域不同一律拒绝。

## 可信回执、限额与接线

accepts从可信原input完整重算并绑定所有双侧条件/差式/根集/证书/hash/scope/合同，不能靠模型声明equivalent或VERIFIED、删孔洞/改根计数获得接受。UNSUPPORTED保持未知，取消传播。每式4096字符/AST256/次数与幂32/系数1024bits/解析深度64/中间次数64；排除根并次数32和现Sturm隔离资源限额不变。正确超限输入也不输出半成同解结论。

无Room/sidecar/Pigeon/身份/权限/模型/UI迁移，无第二执行器/网络/模型调用/用户材料写入。A主ChatFactory/可信registry/receipt/整目标验收归A，B只交包含新scope的最小共享候选/原SHA，不直接启用；VERIFIED只证明单步限定同解，不是用户全部目标/一般证明/OCR/评分通过。原有有理等式链仍固定第一式域，不能把本新操作与它混用。

准确源码、实际命令/运行/产物hash及未验边界见本批TEST_RESULTS/SOL_TEST_EVIDENCE/SOL_BLOCKERS。独立外部审查NOT_EVALUATED，局部CLI/Tool不放行完整App/Release/API26真运行/Mate50/生产。
