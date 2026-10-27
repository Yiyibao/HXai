# B3j — 有理原函数证书的有限定积分核验 v1

FeatureID `B-RATIONAL-PRIMITIVE-INTEGRAL-20261018`，自动接续 B2i；继承 B3a–i 原生统一 math_verify、闭合表达式语法、精确 Rational/Polynomial/RationalFunction、Sturm 和独立 accepts。base/head `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加本批完整未提交域源码，精确文件与产物SHA见SOURCE_FINAL/BUILD_INPUT/TEST_RESULTS。原操作合同1–9保持不变。

## 输入与资源
新增 `operation=rational_primitive_integral`，严格六字符串 operation/expression/primitive/lower/upper/claimed_value。expression/primitive 都须单变量 Q(x) 闭合语法，给出有理原函数而非要求自动求出超越原函数。有限有理端点和值复用 B3i 字面/1024bits限制。解析4096字符、AST256、次数/幂32、系数1024bits、嵌套64；中间次数64及原有 RationalFunction 构造限制仍生效，计算超限UNSUPPORTED。

沿既有非零条件记录每个原始语法除数/0次幂，不因代数约分抹掉孔洞。MathVerification 有界规范请求哈希新增此操作6字段，其他操作限额不改。Sturm域评值沿 RealRootLimits；有理积分端点评值/差上限 `4*(32+1)*1024+128=135296bits`，单列 `limits.integral_evaluation_bits`；这是CPU/存储安全界，不是费用/Token预算。

## 算法与结论
先解析两式，收集各自原非零条件及规范分母；对每个去重条件单独平方自由化、建精确Sturm序列。有限端点排序后，对整个闭区间检查两端不是根且开区间根数0，同端点仍查原定义域。不需要将所有因子相乘放大次数，也不用数值采样证明。

闭区间任何原始孔洞/极点（包括可去、无理、仅primitive额外孔洞、0^0）→ UNSUPPORTED，不包含 exact_value，不宣称原积分发散、主值存在或不存在。允许合法区间外的孔洞/只有复根的分母。

全部定义域条件成立后，精确核对 primitive' 与 expression 的交叉乘差系数。证书不等→ status/certificate_status=DISPROVED、claim_status=NOT_EVALUATED，不输出exact_value；这只能证明证书错，不能误判实际可能正确的积分值声明。正确证书用有理端点 F(upper)-F(lower) 精确求差：值相等 VERIFIED，否则DISPROVED并给claim_difference。加常数、反向端点、合法同端点和不同正确表达式保持可用。

## 结果与信任
新 contractVersion=10/domain Q(x)/scope=`finite_rational_endpoint_integral_with_rational_primitive_certificate`。输出两规范式、原非零条件、规范导数/交叉差、每因子square-free/Sturm/端点变差/0根计数、闭区间与精确端点差证书。独立 `MathVerification.accepts` 从持久原请求完整重算，状态/域/端点/hash篡改不能通过。无新增模型、授权、任务/DB/Pigeon、网络或文件副作用；取消传播。

累计共享候选四文件包含本批数学和B2g–i三章节工具，**替代**B2i共享候选，不能盲叠两个补丁。Factory/Registry EXACT_MATH/TaskResultVerifier接受重算、TaskPlanner明确每种有限scope与完整目标门禁；原字节在SHARED_SOURCE_PREREQUISITES，可应用检查通过，但未改A真源、未编译或实际运行主Chat。领域已WIRED，整Chat仍PARTIAL。

## 本批运行证据
私有与根真实Kotlin CLI均2764请求（含本批341、独立Fraction/闭合AST/Sturm核验328可出证书结果）；不是把历史计数累加当完成率。私有Debug/QA/JVM431通过，lint0错误24警告。最终匹配安装APK的Android新6、原数学66、章节检索5通过。实际持久12输入/结果独立回读，其中7份证书逐项核验、5份域不全定义拒绝保持无精确值。详TEST_RESULTS/原始logs/产物SHA，不把CLI或领域Tool当真实模型端到端。

领域 PASS_SCOPED/E4；独立审查NOT_EVALUATED。自动求一般有理原函数、ln/三角/超越/反常积分/主值/积分交换/一般证明、API26/Mate50/Release/真实模型/生产均未由本批放行。后续B可实现多项式Taylor有限展开声明核验（精确中心/阶数/系数，先合同再代码），不能以采样或省略余项冒充一般函数展开证明。
