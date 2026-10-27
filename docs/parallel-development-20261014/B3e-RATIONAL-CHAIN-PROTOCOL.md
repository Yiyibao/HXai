# B3e — 初始实定义域上的有理等式链合同 v5

来源：Pro inputs/04 P2-A 批改首错/正确异法、inputs/13 MATH-03/MATH-05；继承 B3a–B3d，不覆盖原规范，不把本子域替代高中/大学/实变数学目标。适用 base/head 与原字节来源见 HXai_CHANGE_REVIEW/B-RATIONAL-CHAIN-20261015。

## 输入、范围与旧接口

沿用 mathVerifyTool()/MathVerification.verify/accepts，operation=rational_expression_chain；严格只有 operation、expressions，2–50 个字符串。语法沿用单变量 Q(x) 整数/四则/括号/非负整数字面幂，不接受条件/授权/owner/额外指令字段。contractVersion=5、domain=Q(x)、scope=rational_expression_chain_on_initial_defined_real_domain。旧操作合同1–4保持，不把 rational_identity 的共同定义域语义悄悄改成整链语义。

## 固定域与证明对象

第一表达式的原语法非零多项式确定 D；约分、乘零、零次幂和嵌套倒数不会抹掉它。整链核验每个后继表达式在 D 上全部有定义，并与前一步恒等。D 只从第一表达式确定，不随后续自然域交集缩小；允许后继表达式自然域扩大，但不宣称原孔洞获得值。

设 A 为初始排除条件的平方自由并，B 为后继条件的平方自由并。B/gcd(A,B) 的全部实根正是 D 内新增的失效点：无实根时允许该写法；有实根即 DISPROVED。用现有严格 Cauchy 界、Sturm 完备计数/隔离给出精确证书，不依赖整数/随机采样，也不把复根当实数孔洞。条件的重数不影响定义域。

定义域检查通过后，复用 RationalFunctionVerification 的精确交叉乘差系数比较值。非零系数直接反驳恒等；反例点在初始/双方条件都有效的位置，仅为见证，不是证明采样。

- `x/x → 1 → x/x`：通过，D 始终是 R 去掉0。
- `1 → x/x`：不通过，新增0失效点。
- `1 → (x²+1)/(x²+1)`：通过，无新增实失效点。
- `1 → (x²−2)/(x²−2)`：不通过，新增两个无理失效点。

## 首错、未知和回执

逐步运行，`checks` 只到首个非 VERIFIED。`first_error_transition` 为 1-based DISPROVED 步，`verified_prefix_transitions` 为已验证前缀长度。遇不支持语法/条件或资源上限则 UNSUPPORTED，`first_error_transition=null`，用 `unverified_transition` 标当前步，不判断后缀。非法数组/字段在执行前拒绝，不虚构首错。

返回 initial_domain/initial_excluded_squarefree_coefficients、逐步 domain_check/value_check 及 VERIFIED 时的 canonical_final_expression。域失败不伪造 value_check。`accepts` 从可信原 input 完整重算，绑定全部字段、input_sha256、scope、合同、前缀和精确证据；模型写 VERIFIED、删域/改证书/挪用其他输入回执都不授予通过。

## 限额、状态与接线边界

沿用每式4096字符、AST256、次数/幂32、系数1024bits、解析深度64、中间次数64；新增域排除并次数32、Sturm隔离深度256/工作节点8192/坐标4096bits，50表达式有界。正确但超限仍 UNSUPPORTED，不返回已完成整链。取消传播，不调用模型/网络，不设置财务预算。

无 Room/sidecar/Pigeon/身份/授权/模型/UI 变化，无用户材料副作用；Math Tool 的真实领域调用与主 Chat 注册分开。A 共享 Factory/TrustedToolRegistry/TaskResultVerifier/TaskPlanner 只提供包含新 scope 的完整最小候选 patch 和原 SHA，B 不直接启用或新建执行器。VERIFIED 仅证明本表达式链，不是方程同解/一般证明/手写OCR/评分/任务整体成功。

准确实跑、环境/源码/产物 SHA 和未验边界以本批 TEST_RESULTS.json、SOL_TEST_EVIDENCE、SOL_BLOCKERS 为准。独立外部审查 NOT_EVALUATED；不沿用历史 E4 为本轮 Android/Release/真机/生产授予 PASS。
