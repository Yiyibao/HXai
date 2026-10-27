# B3i — 有限有理端点多项式定积分声明核验 v1

依据 Pro inputs/04 专业数学/精确计算、inputs/13 MATH-02/MATH-05，继承 B3a–h 同一 math_verify/闭合语法/精确 Rational/独立 accepts；不重建模型或任务执行器。精确源码与产物/命令见 `HXai_CHANGE_REVIEW/B-POLYNOMIAL-INTEGRAL-20261016/`。

## 输入和支持范围

`math_verify` 新 operation `polynomial_definite_integral`，严格五字符串：operation/expression/lower/upper/claimed_value。expression 为单变量 Q[x]；lower/upper/claimed_value 只有限整数字面或分数字面，原整数分量最多1024bits，分母非零，规范化符号/约分。允许逆向端点和相同端点，不支持无穷/小数字面/参数/超越/变量分母/反常积分。

沿原4096字符/AST256/输入次数32/系数1024bits/嵌套64。新原函数次数最多33，系数仍1024bits；端点评值与差值上限69696bits，见输出 limits.evaluation_bits。PolynomialIntegral.EVALUATION_BITS 为 `2*(32+2)*1024+64=69696`，这是精确运算资源界，不是Token/费用预算。

定积分按形式 Q[x] 多项式解释，逐项构造常数项0的原函数 c_i/(i+1)，以 Newton-Leibniz 对多项式全实连续性的条件给出 F(upper)-F(lower)。新积分算术策略拒绝恒零底数的零次幂（例如0^0或(x-x)^0），不把未定义常量当可积常数；旧多项式/有理函数操作及其幂策略不变。同端点仍完整校验原式/资源，不以结果0绕过非法输入。无数值采样/浮点积分/eval。

## 输出与验收

本操作 contractVersion=9、domain=Q[x]、scope=finite_rational_endpoint_polynomial_definite_integral；旧合同1–8不变。值精确相等 VERIFIED/verified=true；不同 DISPROVED/false 并返回精确差 claim_difference；不支持/超限 UNSUPPORTED/false，不包含伪 exact_value。

证据：integrand_coefficients、antiderivative_coefficients、规范化 lower/upper/claimed_value、endpoint_values、exact_value、claim_difference、proof_kind=exact_polynomial_primitive_and_newton_leibniz、integration_domain=oriented_finite_interval_in_real_numbers、continuity_basis=polynomial_continuous_on_all_real_numbers。input_sha256绑定规范化JSON字段排序后的原请求，ast_nodes/limits照真实解析。MathVerification.accepts 从持久输入重新核验完整事实与证据，仅实际VERIFIED可接受；换输入、改值/原函数/端点评值/状态不能自授成功。

无资料/DB/schema/Pigeon/UI/权限/模型/用量副作用，不外传；取消贯穿。VERIFIED不是一般积分/定理条件证明、评分、授权或完整任务成功。

## 实跑与集成

完整私有与Root CLI均2423项（176积分请求含162可核验证书，旧2247不混作完成率），独立Fraction核对原函数形式导数、端点评值与差。原API不支持负对照与首版0^0边界FAIL原日志保留，修复未改测试期望。真实API34 Android新6方法、旧数学46和材料23方法通过；持久真实Tool输出6份独立证书回读通过。JVM410/lint0错误23警告、Debug/QA构建与安装pull hash另有本批记录。

A共享注册/EXACT_MATH/规划门禁仅最小候选，未apply；领域WIRED，主Chat仍PARTIAL/NOT_RUN。独立审查NOT_EVALUATED；Release/Mate50/真实模型/生产未据局部通过放行。