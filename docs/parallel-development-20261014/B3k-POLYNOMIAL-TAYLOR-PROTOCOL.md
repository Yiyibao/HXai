# B3k — 有理中心多项式 Taylor 系数与精确余项 v1

FeatureID `B-POLYNOMIAL-TAYLOR-20261018`，接续B3j。base/head `5d826d5d7d8755ed42db834d6cd6dc52ae914c69` 加本批未提交域源码，精确版本见 SOURCE_FINAL/BUILD_INPUT/TEST_RESULTS。沿同一math_verify与accepts，旧十三操作合同1–10保持。

## 输入 / 合同11
operation=polynomial_taylor；严格五字段 operation/expression/center/order/claimed_coefficients。前四项为字符串，claimed_coefficients为按 h=x-center 升幂的完整order+1个有理数字面字符串，零项不能省。center和声明沿现 PolynomialIntegral.number 的有限有理字面/原分量1024bits规则；order规范十进制0–32，拒绝负数/前导0/小数/越界/溢出及错误类型。

表达式为Q[x]闭合语法，4096字符/AST256/次数32/系数1024bits/深度64；变量分母、多变量/超越、恒零底数0次幂UNSUPPORTED。x^0按形式多项式常数解释，不冒称带孔洞有理式的总定义域。新增Polynomial.coefficient只读不可变值，不复制容器。新移位/差值限额69760bits，旧Polynomial系数1024bits限制不变。

## 算法、证书与真假
精确有理Horner移位给出p(center+h)全部系数；每轮检查取消。逐项比较完整声明，VERIFIED/DISPROVED与0起first_mismatching_power。非标准等价式、分数约分、非零有理中心、零/常数、最高次数、超过实际次数的完整补零均保持。

结果contractVersion11/domain Q[x]/scope=finite_rational_center_polynomial_taylor_with_exact_remainder，含原x多项式、全部h移位系数、order+1计算/声明/差系数、余项因子h^(order+1)*Q(h)。身份公式使用**计算所得系数，不是未经核验的声明**。非零Q不能省略或称截断式全局等于原式；order>=degree时真实Q=0，补零仍完整。不是一般函数Taylor/Lagrange余项/收敛半径/误差界证明。

MathVerification.accepts从持久输入完整重算；错中心/阶数/余项/系数/hash/状态不授真。新增五字段哈希限额、Tool数组schema和有限scope提示；无新权限/模型/任务/DB/Pigeon/网络/文件副作用。A共享四文件仅累计候选，含原章节/数学契约与Taylor范围提示，替代旧候选，不盲叠；未经主Chat集成不宣称整链WIRED。

## 本批实跑
根/私有真实Kotlin CLI各3104输入，其中本批340、321可出证书结果由独立Fraction形式导数/阶乘求系数并完整二项式重建原多项式。私有Debug/QA/JVM437通过，lint0错24警告；最终匹配安装APK的Android新6、原数学72、章节5通过。实际持久10输入/结果含9证书独立核验，1超限拒绝无伪展开。源码/输入/产物/日志SHA见本批证据，不累计历史完成率。

领域PASS_SCOPED/E4；独立审查NOT_EVALUATED。主Chat共享候选编译/任务链、API26/Mate50/Release/真实模型/生产未放行。下一B领域接续多项式有理闭区间误差界证书，有限方法证明不足时保持未证实，不将正确但更紧的界判为错。
