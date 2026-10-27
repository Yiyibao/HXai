# B3b — 定义域保全的原生有理函数核验 v2

来源：B-RATIONAL-MATH-20261014，base/head 5d826d5d7d8755ed42db834d6cd6dc52ae914c69，加当前未提交B领域增量；准确原始字节见本批SOURCE_MAP/FILES_SHA256。此文增量补充B3a，保留原Pro规范与历史证据，不放宽原Q[x]门槛。

## 入口和兼容

仍使用 mathVerifyTool(): Tool / math_verify、MathVerification.verify(JsonObject)、accepts(input,candidate)。旧identity、derivative、equal_expression_chain输出contractVersion1/domainQ[x]和原scope；变量分母仍不支持。新增rational_identity(left,right)、rational_derivative(expression,claimed_derivative)，只接受操作所需字符串字段，输出contractVersion2/domainQ(x)。不新增模型、调度器、权限、Room/Pigeon或网络服务。

MathVerification使用唯一闭合递归下降语法ExactExpressionParser<T>，PolynomialArithmetic保持旧多项式边界，RationalFunctionArithmetic收集原表达式定义条件。旧PolynomialParser/ParsedPolynomial删除，调用方一次性迁移，不留别名。

## 数学语义

- 输入仅整数、x、+/-/*/、括号、^或**的字面非负整数幂。不同于Q[x]的形式环约定，Q(x)实表达式的a^0要求a已定义且非零，常量0^0明确UNSUPPORTED。
- 有理函数以BigInteger正规有理系数多项式分子/分母表示，Euclid GCD约分、分母首一化；正规值之外，原语法每个除法的除数分子非零条件独立保留。0*(1/x)、(1/x)^0、1/(1/x)均仍要求x!=0；约分不填孔洞。
- rational_identity固定scope=rational_identity_on_common_defined_real_domain。rational_derivative固定scope=rational_derivative_on_common_defined_real_domain，商法则后比较仅在原函数与声称导数的共同定义域内。两侧总定义域相同、孔洞延拓、方程同解、一般定理均不属于VERIFIED声明。
- computed_left/claimed_right各含正规numerator/denominator（次数键→有理数十进制字符串）；left_nonzero_polynomials/right_nonzero_polynomials保存各自原条件；comparison_domain={ambient:real_numbers,predicate:all_polynomials_nonzero,nonzero_polynomials:[…]}是条件的并集。
- cross_difference_coefficients是n_left*d_right-n_right*d_left的精确系数。全零才VERIFIED；非零为DISPROVED依据。counterexample在满足全部原条件且两正规分母非零的整数点显示精确left/right。根数次数上界保证有限查找可找到见证；取样不是证明。
- identically-zero divisor、语法/域/资源越界返回UNSUPPORTED，不当作错误解法或目标成功。当前不支持小数、多变量、超越、一般方程/定理、OCR和评分。

## 可信证据与资源

accepts从可信持久input重算；只有实际VERIFIED且domain/scope/contractVersion/input_sha256/全部系数与原非零条件完全一致才接受。notice只要求字符串，不作为数学或权限证据。删改定义域、伪造状态和用另一个input的receipt均拒绝。

每式4096字符、AST256、解析深度64、正规分子/分母次数32、字面幂32、正规系数1024bits；受控中间多项式次数64。复杂但数学正确的输入可能因中间资源界限UNSUPPORTED，不能用无限符号计算消除安全边界。没有eval/浮点/随机证明或财务预算。取消向上传播，计算后再检查协程取消，不返回伪核验。

## 分工与交付边界

B只修改feature/math、专属测试/验证脚本和本协议。Factory/可信Registry/TaskResultVerifier/TaskPlanner仍由A最终接线；B3a已有注册patch可继续使用同一构造与独立accepts，但计划提示的范围必须从仅Q[x]更新为明确限定Q[x]/Q(x)及原非零条件。不直接覆盖共享文件。完整Chat→全局任务→目标成功及专业UI不由单个数学receipt授予。

本批CLI/实际Android的准确结果、失败负对照、被测输入/APK/产物与原件hash见TEST_RESULTS和HANDOFF。无UI修改，真实模型、Release/Mate50、Office/云/生产以及独立外部审查仍未获得本批验收。
