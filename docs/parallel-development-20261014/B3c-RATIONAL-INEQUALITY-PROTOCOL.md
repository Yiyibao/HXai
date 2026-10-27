# B3c — 原定义域上的精确有理不等式合同 v3

来源：B-RATIONAL-INEQUALITY-20261015当前B领域增量；base/head=5d826d5d7d8755ed42db834d6cd6dc52ae914c69与未提交源码分开，准确raw SHA/APK/输入见本批SOURCE_MAP/TEST_RESULTS。只追加领域合同，不覆盖Pro原规范、B3a/B3b或A全局任务协议。

## 输入与旧合同

同名mathVerifyTool()/MathVerification.verify/accepts新增operation=rational_inequality。仅允许operation、expression、relation三个字符串，求解expression与0的 >、>=、<、<=；未知/多余字段、其他关系、超越/多变量/不清符号或资源越界返回UNSUPPORTED。零次幂沿用B3b实表达式非零基底条件。旧Q[x]合同1、有理恒等/导数合同2及所有原scope保持。

新输出contractVersion3、domain=Q(x)、scope=rational_inequality_on_original_defined_real_domain。VERIFIED表示已精确求出这一限定问题的完整解集，空解集也是完整答案；不是一般证明、方程同解、任意用户目标完成或权限凭据。

## 证明与根表示

canonical_rational_function保留正规分子/分母；original_nonzero_polynomials保留原语法条件，original_domain是这些条件和正规分母非零的合取。约分、乘零和倒数不消除原孔洞。

所有原限制/正规分母的square-free LCM形成excluded_squarefree_coefficients，再与正规非零分子的square-free部分合并为critical_squarefree_coefficients。零分子不增加有限临界根。先去重根再检查合并次数，不能把x^32的一个根误当32个边界。

sturm_sequence使用精确有理系数余式、取负，再只除以正比例因子；负首项余式不能直接monic而翻转符号。严格Cauchy root_bound覆盖全部实根；bound_variations之差等于real_root_count。根隔离为有理二分，Sturm零项省略的右侧变号约定在开区间计数时扣除上端点根。

roots按实数次序编号。isolation是rational_point(value)或open_rational_interval(lower,upper,root_count=1)，全部数值为精确整数/有理数字符串。不强称小数精度。每个root的excluded通过原排除多项式的精确根计数判断；selected仅在合法分子零点且非严格关系成立时为真。

cells覆盖相邻根之外的全部连通开单元。完整临界根证书证明其中没有零点/极点；连续性保证符号恒定，sample/value/sign只用于这一已认证单元的精确符号分类，不是随机或有限采样证明。原域条件必须在sample上全部满足。

solution_intervals是相邻已选单元/合法根点合并后的不交叠完整解集。端点kind为negative_infinity、positive_infinity或root(index)；root索引指上述临界多项式及唯一根证书，不是浮点近似。lower_closed/upper_closed控制端点，单点可表示为同一root的双闭区间。孔洞与极点永不开闭为包含；无穷端点不闭合。

## 可信重算与限制

accepts从可信持久input完整重算，根数、Sturm序列、原域、符号、区间与开闭端点、scope/合同/输入hash任一不匹配均拒绝；notice只作表达。来源/授权/task成功权威仍由A宿主控制，不信模型自述。

旧4096字符/AST256/正规次数及幂32/系数1024bits/解析64保持；临界不同根多项式次数32、有理二分深度256、工作节点8192、坐标4096bits与有界求值。数学正确但过近/过复杂的根可能UNSUPPORTED，必须整体拒绝而不是交付部分解集。取消传播，不设置Token/费用预算。

## Android与接线边界

B领域不依赖Android API33的BigInteger.TWO字段，使用valueOf(2)的最低API安全写法；API26运行本身须另实测，不能用高API模拟器代替。Root共享Factory/Registry/任务验证/目标接受仍归A；本批不改这些文件，接线候选与scope说明单独交付。无新模型、DB、Pigeon、UI、网络或文件副作用。最高证据和准确实跑结果以本批TEST_RESULTS为准；完整Chat/Release/Mate50/生产/独立审查不由此协议放行。
