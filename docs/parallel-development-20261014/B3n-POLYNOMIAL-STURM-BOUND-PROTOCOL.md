# B3n — 闭区间多项式统一误差界精确 Sturm 判定 / 合同14

适用：`B-POLYNOMIAL-STURM-BOUND-20261019` 的 SOURCE_FINAL/TEST_RESULTS；Git HEAD `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 不代表未提交域文件内容。保留合同1–13，不改任务/权限/模型/Room/sidecar/Pigeon。此文档是有出处的增量，不覆写Pro原规范。

## 真实入口与边界

`mathVerifyTool()` → `MathVerification.verify()` → `PolynomialSturmBound.verify()` → 既有 `ExactExpressionParser` / `Polynomial` / `CriticalPolynomials.squareFree` / `SturmSequence`。`MathVerification.accepts()` 从原输入重算全部结果，除展示notice外要求所有字段和键完全一致；VERIFY结果不授予授权或自动完成用户任务。当前主Chat共享Factory/Registry/ResultVerifier/Planner仍交A统一集成，本批只提供累计 `SOL_SHARED_INTEGRATION.patch`，替代B3m候选而非叠加。

## 输入

```json
{"operation":"polynomial_uniform_bound_sturm","expression":"x*(1-x)^2","approximation":"0","lower":"0","upper":"1","claimed_bound":"4/27"}
```

恰好六字段，均为字符串。两式沿用Q[x]闭合语法，次数≤32、4096字符、256 AST节点、64层、系数1024bit。端点/界是有限整数或分数字面，1024bit；要求lower≤upper、claim≥0。拒绝变量分母、函数、多变量、0^0、额外owner/max_depth、非字符串。不扩大旧操作定义。

## 证明对象与输出

目标是每个x∈[a,b]满足|source(x)-approximation(x)|≤claim。构造两个独立多项式：upper_obligation=claim-error，lower_obligation=claim+error。两者必须均在整个闭区间非负。

- 全部满足：status/claim_status=VERIFIED，verified=true。
- 精确发现违反点：status/claim_status=DISPROVED，verified=false，counterexample含有理x、error和absolute_error。它只否定此给定界，不否定所有界或宣称求得最优界。
- 语法/系数/根隔离/输出限额：UNSUPPORTED/verified=false，无部分obligation或反例。未知不变成错误或成功。

公共结果含contractVersion=14、domain=Q[x]、scope=`finite_rational_interval_polynomial_uniform_absolute_bound_by_sturm`、原输入SHA、AST/限额、source/approximation/error精确稀疏系数、端点及界。

每个obligation包含：原多项式coefficients、端点精确值及nonnegative。a=b仅使用精确点值；a<b另含平方自由单首多项式、只正比例归一化的完整Sturm余式链、两端变号数/是否根、开/闭区间不同实根计数、隔离work数、所有有序不同根及符号单元。

根用rational_point，或不含端点根且恰含一个根的open_rational_interval；区间端点上的根显式列出。重数去除仅用于根计数，符号始终对原多项式求值，所以偶重根不错误翻号。每个根之间、根与闭区间边界之间的非空单元均有index、lower/upper根索引或端点描述、精确符号点和值。边界根外的空单元不列。常数/恒零的平方自由多项式取1，无根，仅保留整个区间的常值单元。端点另外精确检查。

## 完备性与资源

直接在[a,b]内按Sturm开区间根数隔离，不用未细化的全局根括区与a/b近似比较。每次精确中点分裂；中点根单列，子区间开根数扣除上端根。所有不同根计数匹配后才用无根单元连续性定号；符号点允许位于闭区间的非根端点或隔离边界。它不是有限采样证明。

沿用深度256、8192 work节点（每个obligation）、坐标4096bit、根求值限额。取消贯穿解析间隔、Sturm与隔离/单元循环。输出保守估计完整escaped JSON长度，加8192字符元数据余量，上限1MiB，超限整体拒绝；不截断/漏根/漏另一侧后授真。系数增长可提前UNSUPPORTED，非对所有次数32输入保证完备。

## 反例与复验

- `x*(1-x)^2`，[0,1]，4/27：新合同精确获证；B3m深度8仍NOT_CERTIFIED，不改变其语义。
- `1-(x^2-2)^2/2`，[1,3/2]，1：包含sqrt(2)偶重切触，获证。
- `1-(x-1/3)^2+1/1000003`，[0,1]，1：端点未超界但内部窄区间超界，精确DISPROVED。
- 同式使用`1/2^600`级微扰：有界根隔离不能完成时UNSUPPORTED，不能靠看似非负的采样授真。

独立脚本 `scripts/verify_b_sturm_bound.py` 使用Fraction AST、平方自由gcd、Sturm正比例余式/全根计数和无遗漏符号单元、两侧符号及有理违反点交叉检查实际Kotlin或Android持久输出，不调用模型。

复跑：`python scripts/verify_b_sturm_bound.py --worktree .local/b3b-rational-worktree --cases HXai_CHANGE_REVIEW/B-POLYNOMIAL-STURM-BOUND-20261019/golden/FINAL_CASES.json --run-dir .local/b3n-sturm-replay-new`（目标目录必须新）。真实Android、JVM/构建结果以本批证据为准；完整Chat、真实Provider、API26/Release/Mate50/生产与独立审查不由此协议预授通过。
