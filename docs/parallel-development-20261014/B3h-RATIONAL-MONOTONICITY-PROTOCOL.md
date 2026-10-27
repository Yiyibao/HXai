# B3h — 有理函数最大连通单调分支合同 v8

来源：Pro inputs/04 P2-A导数/严格条件、inputs/13 MATH-01/MATH-02/MATH-05；接续B3a–B3g，不覆盖原规范或缩小全部专业数学目标。精确base/head、原字节和被测范围见HXai_CHANGE_REVIEW/B-RATIONAL-MONOTONICITY-20261015。

## 输入、输出与旧接口

同一math_verify/MathVerification.verify/accepts新增rational_monotonicity(expression)，严格operation和expression两字符串。contractVersion8、domainQ(x)、scope rational_monotonic_branches_on_original_real_domain。旧合同1–7、字段/hash和定义域语义保持。

返回canonical_rational_function（原函数）、derivative_evidence（完整精确有理导数/Sturm符号分解/原域/根/单元）、increasing_intervals、decreasing_intervals、constant_intervals、stationary_everywhere及stationary_points。branch_scope为maximal_connected_non_degenerate_intervals_in_original_real_domain：只声明原域内最大连通非退化区间，不比较跨不连通分量的整体序关系。端点沿用精确root/index/开闭，不是浮点近似。

## 原域与证明

保留约分/零乘/嵌套倒数/零次幂之前的所有原非零条件。对正规函数用精确商法则求导，一次复用RationalRealSolver.solve(derivative,originalConditions,==)得到完整平方自由临界多项式、Sturm实根计数/隔离、排除根和无根单元符号。原失效点无论能否补成连续函数都不能纳入分支、驻点或极值。

非恒零导数在每个已证明无临界根的开单元符号恒定。导数同号单元跨合法驻点合并，故x³在0的驻点不拆分全实轴严格递增；转折驻点可同时是两侧不同单调分支的闭端点，如x²的(-inf,0]递减、[0,+inf)递增。每个分支内导数同弱符号，零点仅有限个、函数连续可导，故严格单调；原域孔洞必须切断连通分支。

恒零导数时不输出严格增/减（不包含无意义单点区间），各原域连通分量为常值；stationary_everywhere=true表明每个定义点为驻点，stationary_points空列表不等于没有驻点。非恒零时，所有未排除临界根为驻点：+→−局部极大，−→+局部极小，同号为非极值驻点。仅输出精确root_index/classification，不编造代数根小数或函数值。

## 复用与可信回执

RationalRealSolver原私有solution区间组装改名为领域内intervals，所有旧调用干净迁移，无旧别名；新分支复用同一端点/区间规范，不引入第二种区间表示。独立Fraction核验脚本把既有数学证书正文提取为check_decomposition，旧check_certificate仍核对原操作metadata与方程双侧；避免构造伪scope/hash给嵌套导数证书冒充Tool输出。

accepts用可信原input完整重算，绑定原域/导数/根/符号/最大合并分支/驻点/hash/scope/合同。删孔洞、把x³拆成两段、篡改单调方向/驻点或另一输入回执都不能通过。VERIFIED仅限定分类，不是参数/超越/一般证明/OCR/评分/整任务成功。

## 限额与接线边界

沿用字符4096/AST256/正规次数与幂32/系数1024bits/解析64/中间次数64和Sturm完整实根资源界限；原式虽在范围内但导数/临界并超界也整体UNSUPPORTED，无半成分支。取消传播，无随机采样证明、网络、模型、用户材料副作用或财务硬预算。

无Room/sidecar/Pigeon/身份/授权/模型/全局slot/UI变更。A主Chat四文件激活只交候选+原SHA，B不apply；主Chat注册和整目标验收仍归A。外部独立审查NOT_EVALUATED。准确实际运行/源码/APK/未验范围以本批TEST_RESULTS/SOL_TEST_EVIDENCE/SOL_BLOCKERS为准，不放行完整App/Release/API26真运行/Mate50/生产。
