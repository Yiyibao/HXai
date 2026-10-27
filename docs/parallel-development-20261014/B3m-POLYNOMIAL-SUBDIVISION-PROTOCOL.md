# B3m — 有理闭区间多项式精确细分误差证书

FeatureID `B-POLYNOMIAL-SUBDIVISION-20261018`。base/head `5d826d5d7d8755ed42db834d6dc52ae914c69` 加本轮SOURCE_BUILD_INPUT/SOURCE_FINAL记录的实际脏域源码。沿同一math_verify/accepts；旧十五操作和合同1–12保留，B3l基本包络结果不变。

## 输入与合同13

operation=`polynomial_uniform_bound_subdivision`，严格七字符串字段：operation/expression/approximation/lower/upper/claimed_bound/max_depth。前六字段同B3l，最后一个必须是规范单字符十进制`0`–`8`。两式Q[x]、有限有理lower<=upper、声明界非负。输入次数32/系数及端点1024bits/4096字符/AST256/深度64等不放宽；拒绝变量分母、超越、多变量、无穷、0^0、未知身份字段、数字类型的depth及01/+1等非规范值。

scope=`finite_rational_interval_polynomial_uniform_absolute_bound_by_subdivided_bernstein`、domain=Q[x]、contractVersion=13。VERIFIED只证明声明不小于本证书的充分误差界；不足返回UNSUPPORTED、claim_status=NOT_CERTIFIED，不把错误界或正确但未证明的紧界自动DISPROVED，也不声明最优。

## 精确算法与完整覆盖

复用B3l解析/误差差多项式/有理仿射及初始Bernstein系数。区间树深度优先、左至右；只有当前充分界大于声明、非退化、非常数且深度不足时，才在真实有理中点细分。de Casteljau三角递推每项为相邻系数的精确平均，其两条边正是两个半区间的Bernstein系数。不使用浮点、数值采样或第二解析器。

每个终止叶子保留lower/upper/depth、完整n+1个系数、error_enclosure、certified_absolute_bound、stop_reason（bound_certified/point_interval/constant_error/depth_limit）。失败叶子同样必须保留。叶子从左到右首尾覆盖原闭区间，相邻端点相同，除共享边界外不重叠；最终包络为全部叶凸包的并包络，绝对界为全部叶绝对界之最大值。因此仅一片获证、漏掉右支或达到限额绝不算完整成功。max_depth=0保留基本包络；零差/常数/同端点不做无益细分。

## 输出与限额

顶层返回原两式与误差系数、原区间/宽度/声明、bernstein_degree/basis、最终包络和声明状态。`initial_*`明确只标初始全区间的Bernstein系数/单位幂系数/包络/界，不与最终细分界混淆。附完整leaves、leaf_count、split_count、deepest_leaf、depth_limit_reached和覆盖策略。最深8、最多256叶/255次split（511节点），系数限139392bits。证书1,048,576字符上限采取保守累计预算并给元数据留余量；接近限额可能提前拒绝，超限整体UNSUPPORTED且没有部分leaves，不截断后授真。每个区间及三角层检查取消，异常取消向上传播。

accepts从持久原输入重算完整事实，不只看status；删叶、错端点/系数/界/深度/hash/输入均不能验收。notice仅显示性文案，不参与证明权威。

## 正反例

- x-x²、[0,1]、声明1/4：depth0仅有1/2包络而未知；depth1给[0,1/2]与[1/2,1]两个精确包络并证明1/4。
- x(1-x)²、[0,1]、声明4/27：真界在x=1/3取得，因`4/27-x(1-x)^2=(x-1/3)^2*(4/3-x)>=0`可知正确；该非二进制极值在有界中点细分后仍可NOT_CERTIFIED，不误判正确声明。
- x²+1、[0,1]、声明0、depth8：必须保留全部256个失败叶子，最终界2且不获证。
- 大分母x^8生成的完整输出超过证书限额：整体拒绝，不发布只含前几个叶子的假证据。

初期反例误把x-3x²在[0,1/3]的极值1/6当作非中点；实际工具正确获证。已按相对区间坐标纠正夹具，保留FIXTURE_CORRECTION及被取消构建记录，不改产品迎合错误期望。

## 接线与证据

真实入口mathVerifyTool.execute→MathVerification.verify→PolynomialUniformBound→PolynomialUniformSubdivision→JSON；域源码根/私有隔离同步。A四共享仍只累计候选，替代B3l旧候选，不盲叠、不改A真源；无新DB、Pigeon、UI、授权、模型配置或业务副作用。Android工具持久回读、独立Fraction全式基恒等和完整覆盖验证、JVM/构建/lint结果以本批TEST_RESULTS、RUNS和HANDOFF为准，不将CLI或隔离nativeqa证据冒充主Chat、Release或Mate50通过。

原生首次lint还发现BigInteger.TWO要求API33而产品minSdk26；最终改为同值BigInteger.valueOf(2L)，不压制lint、不抬高最低版本。失败源码/完整构建输出和lint报告保留在本批failed-api-build证据，最终兼容源码另行完整构建与工具实跑，不能以桌面JVM通过替代Android API门禁。
