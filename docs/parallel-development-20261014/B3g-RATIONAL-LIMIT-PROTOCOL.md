# B3g — 原实定义域上的有理函数极限分类合同 v7

来源：Pro inputs/04 P2-A高数/数学分析/严格条件与inputs/13 MATH-02/MATH-05；接续B3a–B3f，不覆盖原规范，也不将有理子域代替全部P2。适用base/head和原SHA见HXai_CHANGE_REVIEW/B-RATIONAL-LIMIT-20261015。

## 输入与兼容

同一math_verify/MathVerification.verify/accepts新增rational_limit。严格operation/expression/at/side四字符串。at为有符号整数字面或分数字面（允许带符号非零分母，正规化为精确有理数），或+inf/-inf；不接受小数、科学记数、表达式或代数无理点。side=left/right/both；+inf/-inf只允许both，含义为该指定尾部的单个极限，不比较两个无穷尾部。contractVersion7、domainQ(x)、scope rational_limit_along_original_real_domain。旧合同1–6与其字段/hash/域语义保持。

## 定义域与有限点证明

原语法的所有非零条件完整保留，包括约分前、零乘、嵌套倒数和零次幂条件。它们均非零多项式，排除点有限；原实域在每个有限点的两侧及两个无穷尾部都有逼近点。因此原点未定义不妨碍沿原域的极限，但不能给原式补点值。

正规有理值N/D在有理点a处：

- D(a)非零：精确有理函数连续性给两侧有限N(a)/D(a)，即使原式在a有可去孔洞；proof保存正规分子/分母点值。
- D(a)=0：正规互素确保N(a)非零；逐阶精确求导找到D的首个非零导数，阶m≤32。右侧符号为sign(N(a))*sign(D^(m)(a))，左侧奇m反号、偶m同号；阶乘为正不改变符号。proof保存阶数和首个非零导数，不用浮点接近点猜符号。

输出point_defined、point_value独立于limit；原条件或正规分母在a为零时point_value=null。有限点给one_sided_limits；side选择对应结果，both仅在左右一致时返回该值/同向无穷，否则limit.kind=does_not_exist、reason=unequal_one_sided_limits。

## 无穷证明

按正规分子/分母次数差和精确首项系数比：零函数或分子次数低给0，次数相同给有理首项比，分子次数高给有符号无穷；-inf按次数差奇偶翻转符号。proof保存次数、首项、比和次数差。Polynomial仅新增无分配只读leadingCoefficient访问，原算术不改。无穷没有伪造point_value或有限点单侧数据。

## 状态与可信回执

VERIFIED只表示上述精确分类完成；limit.kind是finite（value为精确分数）、positive_infinity、negative_infinity或does_not_exist。DNE不是存在性证明，无穷不是有限值，不能据分类成功让任何用户目标自动成功。未知语法/点/side/域/超资源保持UNSUPPORTED，不返回半成limit。

accepts从可信原input完整重算所有点值/原域/极点阶数/单侧符号/首项/limit/hash/scope/合同，不能改DNE为有限0、填原孔洞或挪用另一side回执。取消传播，无eval、随机采样或模型请求。

## 限额与接线边界

沿用每表达式4096字符/AST256/正规次数与幂32/系数1024bits/解析64/中间次数64；at字面4096字符、分子/分母各1024bits，精确求值使用现RealRootLimits.EVALUATION_BITS安全界限。正确但超资源题明确未知，不设财务预算。

无Room/sidecar/Pigeon/身份/授权/模型/全局slot/UI迁移，无网络/用户材料写回或第二执行器。A主Chat四文件激活仍由A处理，B只交候选和原SHA，不直接启用；候选必须明确分类与存在性/整目标验收的区别。独立外部审查NOT_EVALUATED；准确实跑/源码/APK/未验边界见本批TEST_RESULTS/SOL_TEST_EVIDENCE/SOL_BLOCKERS，不凭局部通过放行完整App/Release/API26真运行/Mate50/生产。
