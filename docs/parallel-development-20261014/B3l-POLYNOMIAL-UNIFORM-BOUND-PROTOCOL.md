# B3l — 有理闭区间多项式 Bernstein 统一误差界 v1

FeatureID `B-POLYNOMIAL-UNIFORM-BOUND-20261018`，本会话在完成B3k后自动接续。base/head `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加本批完整未提交域源码；SOURCE_FINAL/BUILD_INPUT/TEST_RESULTS固定实际版本。沿同一math_verify/accepts，原十四操作合同1–11保留。

## 输入、状态与合同12
operation=polynomial_uniform_bound，严格六字符串 operation/expression/approximation/lower/upper/claimed_bound。两式为Q[x]闭合语法，lower<=upper且界非负，有限有理字面沿既有1024bits原分量规则。同端点合法；无穷/小数/变量分母/超越/额外身份字段/恒零底数0次幂拒绝。AST256、4096字符、输入次数32/系数1024bits/深度64及误差差多项式的既有限额不变。

新scope=finite_rational_interval_polynomial_uniform_absolute_bound_by_bernstein/domain Q[x]/contractVersion12。VERIFIED仅表示该界已由本证书证明；若给出的界比当前包络更紧，返回UNSUPPORTED、claim_status=NOT_CERTIFIED，不返回DISPROVED，不声称界错或最优误差已求出。非法输入/资源超限无伪包络；方法不足但有真实包络时仍返回该真实证书。

## 精确算法与证明范围
误差e(x)=expression-approximation，令x=a+(b-a)t、t∈[0,1]。共享内部ExactPolynomialTransform.affine通过有理Horner得到单位区间幂系数c_i；B3k移位迁移至该helper，行为与旧证据保留。无可变系数外泄或第二解析器。

度n误差的Bernstein系数b_k=sum(i<=k)c_i*C(k,i)/C(n,i)，整数二项式沿n<=32的精确Long中间量计算，不使用浮点。Bernstein基C(n,k)t^k(1-t)^(n-k)在闭单位区间非负且和为1，因此全区间e落在[min(b_k),max(b_k)]，统一绝对误差不超过B=max(|min|,|max|)。只有claimed_bound>=B才授真。每轮检查取消，仿射/Bernstein数值限额139392bits单列；旧系数限额不放宽。

证书包含两输入多项式、真实差多项式、区间/宽度、单位区间幂系数、完整n+1 Bernstein系数、精确凸包/绝对包络/声明界，以及sufficient_not_necessarily_sharp。不是数值采样或一般函数/有理函数最优误差证明。x-x²在[0,1]真实最优界1/4，本基本凸包只能出1/2；正确1/4保持未证实，1/2可证明，不靠误判正确输入消除FAIL。

## 信任与共享接线
accepts从持久原输入完整重算，伪造包络/系数/状态/hash/claim不能通过。无新模型/权限/owner/epoch/任务/DB/Pigeon/UI/网络或文件副作用。A四共享仍只累计候选，包含原章节/数学、Taylor与新界的有限范围；替代旧候选，不盲叠。候选可应用于冻结preimage但未编译/运行主Chat；必须保留完整计划/目标验收。

## 本批实跑
根/私有真实Kotlin CLI各3432请求，其中本批328、318真实包络证书、212已证实声明。独立Fraction二项式仿射/完整Bernstein基逆变换核对整式恒等，不以若干点作证明。私有隔离Debug/QA/JVM443通过，lint0错24警告。最终匹配安装APK的Android新6、原数学含Taylor78、章节5通过。实际持久9证书独立回读：8声明可证、1正确紧界保持NOT_CERTIFIED。精确输入/源码/APK/输出/日志SHA见本批证据，不将计数相加当产品完成率。

领域PASS_SCOPED/E4；独立审查NOT_EVALUATED。主Chat共享候选编译/任务链、API26/Mate50/Release/真实模型/生产未放行。后继B可在新的合同下增加有界区间细分与紧界证书，完整覆盖所有子区间，证明不足仍未知；不得直接改本合同把NOT_CERTIFIED重写成错误或成功。
