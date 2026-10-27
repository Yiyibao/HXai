# B3a — 原生限定精确数学 v1

B域构造mathVerifyTool()，工具math_verify；输入必须JSON对象，operation必填identity/derivative/equal_expression_chain。identity仅operation/left/right字符串，derivative仅operation/expression/claimed_derivative字符串，chain仅operation/expressions的2–50字符串数组。未知/缺字段/错误类型明确UNSUPPORTED；非对象参数拒绝，不能生成伪绑定回执。

原生BigInteger正规有理数、Q[x]有界多项式和闭合递归下降语法：整数、x、+/-/*、非零常数分母、括号、^/**字面非负整数幂；不支持变量分母消去、超越、多变量、小数、方程等价、OCR或评分。每式4096UTF16/AST等价节点256/次数与幂32/正规系数1024bits/解析深度64。长零整数合法且不分配巨整数；0/1常量与单位运算复用不可变值，幂用平方缩减，相邻等式链表达式只解析一次。

响应contractVersion1/domain=Q[x]、固定scope、operation、input_sha256、ast_nodes、limits和VERIFIED/DISPROVED/UNSUPPORTED+verified。identity scope=formal_univariate_rational_polynomial_identity；derivative=formal_polynomial_derivative；chain=equal_expression_chain_only。合法输入hash是顶层键字典序、紧凑JSON UTF8的SHA256；超大/错误类型非法输入hash为null而不是0，且绝不获VERIFIED。

精确正规差系数为0才VERIFIED；非零差系数是DISPROVED依据，0..degree至多degree+1整数点仅生成精确反例见证，不能当证明。VERIFIED提供canonical_coefficients，DISPROVED提供difference_coefficients/counterexample（数字x、left/right有理数字符串）。chain前缀经逐项精确核对才给first_error_transition；遇未知先给unverified_transition，禁止声称后续首错。

MathVerification.accepts从可信持久input重新计算，只有实际VERIFIED且hash/scope/所有数学证据字段一致才接受；notice仅类型校验而不作为数学或授权数据。Root共享仍failclosed/未激活，B不改其Factory/Registry/TaskResultVerifier/TaskPlanner。四文件SOL_SHARED_INTEGRATION.patch在私有完整App测试：Factory加入构造；local.compute/COMPUTE保持，Registry采用EXACT_MATH；TaskResultVerifier在普通状态解析之前独立重算，拒绝畸形状态/额外输出；Planner允许已核验限定Q[x]作为现tool_verified证据，保留全goal语义验收，DISPROVED/UNSUPPORTED不能自动成功。

证据B-NATIVE-QX-20261014：最终Native25（数学5+旧文件20）PASS，相关规划/授权30 PASS，Python参考运行时83 PASS；独立Fraction360精确案例（240正确/120错误）逐系数/反例/输入hash一致；实际Factory→Room→独立Verifier→TaskStore journal→同配置本机合成语义provider→goal，四状态SUCCEEDED/COMPLETED_UNVERIFIED/COMPLETED_UNVERIFIED/COMPLETED_UNVERIFIED（后3为伪DISPROVED、伪UNSUPPORTED、畸形状态）。不累加历史计数或声称数学准确率。

本批无DB/Pigeon/UI/模型路由/新执行器/费用硬预算、材料外传或新安装。领域与私有接线E4/PASS_SCOPED；Root激活PARTIAL/CONDITIONAL，需Sol三方归并并做任务相关全面门禁。完整App/一般专业证明/Release/Mate50/真实模型/生产/独立审查NOT_EVALUATED或NOT_RUN，原Pro/历史证据不覆盖。
