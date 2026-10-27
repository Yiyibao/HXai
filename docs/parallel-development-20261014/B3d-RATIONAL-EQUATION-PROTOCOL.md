# B3d — 单变量有理方程完整实解合同 v4

来源：Pro inputs/04专业数学计算/严格条件需求，以及B3b合同2、B3c合同3的有出处增量。base/head和当前脏树独立保存于HXai_CHANGE_REVIEW/B-RATIONAL-EQUATION-20261015；不覆盖原规范，不将本子域替代全部P2目标。

## 输入与旧接口

同一mathVerifyTool()/MathVerification.verify/accepts新增rational_equation(left,right)，精确解left=right。只允许operation/left/right三个字符串；不接受relation、权限、owner或附加指令字段。语法和所有原限制保持：整数、x、四则、括号、字面非负整数幂，无小数/超越/多变量/参数/复数域/eval。

新操作contractVersion4/domainQ(x)/scope rational_equation_on_common_defined_real_domain。旧Q[x]合同1、Q(x)恒等/导数合同2、不等式合同3输出和scope保留；不等式关系依然仅四比较，==仍拒绝。不新建第二个工具、模型/执行器/授权入口。

## 完整解集与原域

canonical_sides分别给出正规left/right。left_nonzero_polynomials和right_nonzero_polynomials保存每侧原语法的非零约束，包括除法、乘零前的分母、嵌套倒数和实表达式零次幂约定。original_nonzero_polynomials按左后右组合，保留跨侧来源重复；original_domain的nonzero_polynomials是两侧原限制及差式正规分母的去重合取，ambient=real_numbers。约分/相减/交叉相乘不能扩大定义域。

canonical_rational_function表示left-right，不为求零额外平方或升次。内部RationalRealSolver复用B3c严格Cauchy界、square-free临界多项式、Sturm完备计数/有理隔离和原排除点分类。relation固定==、compared_to固定0：合法分子根可选，原孔洞/极点不可选；非恒零差式的开单元全不选，恒零差式的全部合法单元可选。

solution_intervals沿用精确根端点kind=root/index与正负无穷、开闭旗标，有限根表示同一root双闭单点。可为空集、有限代数根、全实轴或穿孔全域；roots包含求解域所需全部临界实根（包括被排除孔洞），不是仅列答案。根端点由临界多项式的有序唯一实根证书定义，不是浮点值。

VERIFIED只表示这一限定完整解集已算出，空集同样合法。它不表示原方程对所有实数恒真、不表示给定两个方程同解、不表示一般证明或用户全部目标完成，也不授予任何权限。

## 验证与界限

accepts从可信原input独立重算，绑定双侧正规值/原域、差式、根/符号单元/区间、输入hash、scope和合同。不能用模型给出的VERIFIED、删孔洞、增加极点根或其他输入receipt替代。

每表达式4096字符/AST256/正规次数与幂32/系数1024bits/解析64保持；差式也受原有有理次数限制，中间次数64。临界不同根多项式次数32、隔离深度256、工作节点8192、坐标4096bits和有界精确求值。正确但超限的题整体UNSUPPORTED，不返回部分根集。取消传播，无财务硬预算。

无Room/owner/profile/epoch/Pigeon/UI变更，无真实模型/网络/用户材料副作用。A共享注册/调度/全目标验收仍归A，仅候选补丁和原始SHA前提，不自动激活。独立审查NOT_EVALUATED；真实测试与最低API26未运行等边界由本批TEST_RESULTS与SOL_BLOCKERS准确记录，不能授予完整App/Release/Mate50/生产PASS。
