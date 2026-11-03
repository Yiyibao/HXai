package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Exact, explicitly scoped Q[x]/Q(x) facts. Task completion authority stays with the host. */
object MathVerification {
    private val operations = setOf("identity", "derivative", "equal_expression_chain", "rational_identity", "rational_derivative", "rational_inequality", "rational_equation", "rational_expression_chain", "rational_equation_equivalence", "rational_limit", "rational_monotonicity", "polynomial_definite_integral", "rational_primitive_integral", "polynomial_taylor", "polynomial_uniform_bound", "polynomial_uniform_bound_subdivision", "polynomial_uniform_bound_sturm", "rational_uniform_bound_sturm")
    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) { for (b in bytes) { val n = b.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) } }
    }
    private fun boundedInputHash(input: JsonObject): String? {
        if ((input["operation"] as? JsonPrimitive)?.content in ProfessionalMath.operations) {
            if (input.keys != setOf("operation", "request") || input.values.any { it !is JsonPrimitive || !it.isString } ||
                input.getValue("request").jsonPrimitive.content.length > ProfessionalMath.REQUEST_CHARS) return null
            return digest(buildJsonObject { for (key in input.keys.sorted()) put(key, input.getValue(key)) }.toString())
        }
        val maxFields = when ((input["operation"] as? JsonPrimitive)?.content) {
            "polynomial_uniform_bound_subdivision" -> 7
            "rational_primitive_integral", "polynomial_uniform_bound", "polynomial_uniform_bound_sturm", "rational_uniform_bound_sturm" -> 6
            "rational_equation_equivalence", "polynomial_definite_integral", "polynomial_taylor" -> 5
            else -> 4
        }
        if (input.size > maxFields || input.keys.any { it.length > 64 }) return null
        for (value in input.values) when (value) {
            is JsonPrimitive -> if (!value.isString || value.content.length > QxLimits.CHARS) return null
            is JsonArray -> if (value.size > 50 || value.any { it !is JsonPrimitive || !it.isString || it.content.length > QxLimits.CHARS }) return null
            else -> return null
        }
        return digest(buildJsonObject { for (key in input.keys.sorted()) put(key, input.getValue(key)) }.toString())
    }
    private fun scope(operation: String) = when (operation) {
        in ProfessionalMath.operations -> "${operation}_explicit_scope_contract16"
        "identity" -> "formal_univariate_rational_polynomial_identity"
        "derivative" -> "formal_polynomial_derivative"
        "equal_expression_chain" -> "equal_expression_chain_only"
        "rational_identity" -> "rational_identity_on_common_defined_real_domain"
        "rational_derivative" -> "rational_derivative_on_common_defined_real_domain"
        "rational_inequality" -> "rational_inequality_on_original_defined_real_domain"
        "rational_equation" -> "rational_equation_on_common_defined_real_domain"
        "rational_expression_chain" -> "rational_expression_chain_on_initial_defined_real_domain"
        "rational_equation_equivalence" -> "rational_equation_equivalence_over_original_real_solution_sets"
        "rational_limit" -> "rational_limit_along_original_real_domain"
        "rational_monotonicity" -> "rational_monotonic_branches_on_original_real_domain"
        "polynomial_definite_integral" -> "finite_rational_endpoint_polynomial_definite_integral"
        "rational_primitive_integral" -> "finite_rational_endpoint_integral_with_rational_primitive_certificate"
        "polynomial_taylor" -> "finite_rational_center_polynomial_taylor_with_exact_remainder"
        "polynomial_uniform_bound" -> "finite_rational_interval_polynomial_uniform_absolute_bound_by_bernstein"
        "polynomial_uniform_bound_subdivision" -> "finite_rational_interval_polynomial_uniform_absolute_bound_by_subdivided_bernstein"
        "polynomial_uniform_bound_sturm" -> "finite_rational_interval_polynomial_uniform_absolute_bound_by_sturm"
        "rational_uniform_bound_sturm" -> "finite_rational_interval_uniform_absolute_bound_on_both_original_rational_domains"
        else -> "unsupported_request"
    }
    private fun string(input: JsonObject, key: String): String {
        val value = input[key] as? JsonPrimitive ?: throw MathUnsupported("invalid_request_fields")
        if (!value.isString) throw MathUnsupported("invalid_request_fields")
        return value.content
    }
    private fun unsupported(reason: String): JsonObject = buildJsonObject {
        put("status", "UNSUPPORTED"); put("verified", false); put("reason", reason)
    }
    private fun compare(left: Polynomial, right: Polynomial, includeCanonical: Boolean = true): JsonObject {
        val difference = left.add(right, subtract = true)
        return buildJsonObject {
            put("status", if (difference.isZero) "VERIFIED" else "DISPROVED")
            put("verified", difference.isZero)
            put("proof_kind", if (difference.isZero) "exact_rational_coefficients" else "exact_nonzero_rational_coefficients")
            if (includeCanonical && difference.isZero) put("canonical_coefficients", left.json())
            put("difference_coefficients", difference.json())
            if (!difference.isZero) {
                // Nonzero exact coefficients are the disproof; these points only produce a witness.
                val x = (0..difference.degree).first { !difference.value(it).isZero }
                put("counterexample", buildJsonObject { put("x", x); put("left", left.value(x).toString()); put("right", right.value(x).toString()) })
            }
        }
    }
    private suspend fun chain(expressions: JsonArray, nodes: MutableList<Int>): JsonObject {
        if (expressions.size !in 2..50 || expressions.any { it !is JsonPrimitive || !it.isString }) throw MathUnsupported("provide_2_to_50_expression_strings")
        val checks = mutableListOf<JsonElement>()
        var previous: Polynomial? = null
        for (index in 0 until expressions.size - 1) {
            currentCoroutineContext().ensureActive()
            val check = try {
                val left = previous ?: ExactExpressionParser(expressions[index].jsonPrimitive.content, PolynomialArithmetic).parse().also { nodes += it.astNodes }.value
                val right = ExactExpressionParser(expressions[index + 1].jsonPrimitive.content, PolynomialArithmetic).parse().also { nodes += it.astNodes }.value
                previous = right
                compare(left, right, includeCanonical = false)
            } catch (e: MathUnsupported) { unsupported(e.reason) }
            checks += check
            val status = check.getValue("status").jsonPrimitive.content
            if (status != "VERIFIED") return buildJsonObject {
                put("status", status); put("verified", false)
                put("first_error_transition", if (status == "DISPROVED") JsonPrimitive(index + 1) else JsonNull)
                put("unverified_transition", if (status == "UNSUPPORTED") JsonPrimitive(index + 1) else JsonNull)
                put("verified_prefix_transitions", index); put("checks", JsonArray(checks))
            }
        }
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true)
            put("first_error_transition", JsonNull); put("unverified_transition", JsonNull)
            put("verified_prefix_transitions", expressions.size - 1); put("checks", JsonArray(checks))
            put("proof_kind", "exact_rational_coefficients"); put("canonical_coefficients", checkNotNull(previous).json())
        }
    }

    suspend fun verify(input: JsonObject): JsonObject = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val requested = (input["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val operation = requested?.takeIf { it in operations || it in ProfessionalMath.operations } ?: "unsupported_request"
        val professional = operation in ProfessionalMath.operations
        val inequality = operation == "rational_inequality"
        val equation = operation == "rational_equation"
        val rationalChain = operation == "rational_expression_chain"
        val equationEquivalence = operation == "rational_equation_equivalence"
        val rationalLimit = operation == "rational_limit"
        val monotonicity = operation == "rational_monotonicity"
        val integral = operation == "polynomial_definite_integral"
        val rationalIntegral = operation == "rational_primitive_integral"
        val taylor = operation == "polynomial_taylor"
        val uniformBound = operation == "polynomial_uniform_bound"
        val subdividedBound = operation == "polynomial_uniform_bound_subdivision"
        val sturmBound = operation == "polynomial_uniform_bound_sturm"
        val rationalBound = operation == "rational_uniform_bound_sturm"
        val rational = inequality || equation || rationalChain || equationEquivalence || rationalLimit || monotonicity || rationalIntegral || rationalBound || operation == "rational_identity" || operation == "rational_derivative"
        val nodes = mutableListOf<Int>()
        val result = try {
            val fields = when (operation) {
                in ProfessionalMath.operations -> setOf("operation", "request")
                "identity", "rational_identity", "rational_equation" -> setOf("operation", "left", "right")
                "derivative", "rational_derivative" -> setOf("operation", "expression", "claimed_derivative")
                "equal_expression_chain", "rational_expression_chain" -> setOf("operation", "expressions")
                "rational_inequality" -> setOf("operation", "expression", "relation")
                "rational_equation_equivalence" -> setOf("operation", "before_left", "before_right", "after_left", "after_right")
                "rational_limit" -> setOf("operation", "expression", "at", "side")
                "rational_monotonicity" -> setOf("operation", "expression")
                "polynomial_definite_integral" -> setOf("operation", "expression", "lower", "upper", "claimed_value")
                "rational_primitive_integral" -> setOf("operation", "expression", "primitive", "lower", "upper", "claimed_value")
                "polynomial_taylor" -> setOf("operation", "expression", "center", "order", "claimed_coefficients")
                "polynomial_uniform_bound", "polynomial_uniform_bound_sturm", "rational_uniform_bound_sturm" -> setOf("operation", "expression", "approximation", "lower", "upper", "claimed_bound")
                "polynomial_uniform_bound_subdivision" -> setOf("operation", "expression", "approximation", "lower", "upper", "claimed_bound", "max_depth")
                else -> throw MathUnsupported("unsupported_operation")
            }
            if (input.keys != fields) throw MathUnsupported("invalid_request_fields")
            when (operation) {
                in ProfessionalMath.operations -> try {
                    ProfessionalMath.verify(operation, ProfessionalMath.request(string(input, "request")))
                } catch (e: MathUnsupported) { throw e }
                  catch (_: IllegalArgumentException) { throw MathUnsupported("invalid_professional_request") }
                  catch (_: NoSuchElementException) { throw MathUnsupported("missing_professional_request_field") }
                "identity", "derivative" -> {
                    val left = ExactExpressionParser(string(input, if (operation == "identity") "left" else "expression"), PolynomialArithmetic).parse().also { nodes += it.astNodes }
                    currentCoroutineContext().ensureActive()
                    val right = ExactExpressionParser(string(input, if (operation == "identity") "right" else "claimed_derivative"), PolynomialArithmetic).parse().also { nodes += it.astNodes }
                    compare(if (operation == "derivative") left.value.derivative() else left.value, right.value)
                }
                "rational_identity", "rational_derivative" -> {
                    val leftPolicy = RationalFunctionArithmetic()
                    val rightPolicy = RationalFunctionArithmetic()
                    val left = ExactExpressionParser(string(input, if (operation == "rational_identity") "left" else "expression"), leftPolicy).parse().also { nodes += it.astNodes }
                    currentCoroutineContext().ensureActive()
                    val right = ExactExpressionParser(string(input, if (operation == "rational_identity") "right" else "claimed_derivative"), rightPolicy).parse().also { nodes += it.astNodes }
                    RationalFunctionVerification.compare(if (operation == "rational_derivative") left.value.derivative() else left.value,
                        right.value, leftPolicy.nonzeroPolynomials, rightPolicy.nonzeroPolynomials)
                }
                "rational_inequality" -> {
                    val relation = string(input, "relation")
                    if (relation !in RationalRealSolver.inequalityRelations) throw MathUnsupported("unsupported_inequality_relation")
                    val policy = RationalFunctionArithmetic()
                    val value = ExactExpressionParser(string(input, "expression"), policy).parse().also { nodes += it.astNodes }
                    RationalRealSolver.solve(value.value, policy.nonzeroPolynomials, relation)
                }
                "rational_equation" -> {
                    val leftPolicy = RationalFunctionArithmetic()
                    val rightPolicy = RationalFunctionArithmetic()
                    val left = ExactExpressionParser(string(input, "left"), leftPolicy).parse().also { nodes += it.astNodes }
                    currentCoroutineContext().ensureActive()
                    val right = ExactExpressionParser(string(input, "right"), rightPolicy).parse().also { nodes += it.astNodes }
                    val conditions = leftPolicy.nonzeroPolynomials + rightPolicy.nonzeroPolynomials
                    val proof = RationalRealSolver.solve(left.value.add(right.value, subtract = true), conditions, "==")
                    JsonObject(proof + mapOf(
                        "canonical_sides" to buildJsonObject { put("left", left.value.json()); put("right", right.value.json()) },
                        "left_nonzero_polynomials" to JsonArray(leftPolicy.nonzeroPolynomials.map { it.json() }),
                        "right_nonzero_polynomials" to JsonArray(rightPolicy.nonzeroPolynomials.map { it.json() }),
                    ))
                }
                "rational_expression_chain" -> RationalExpressionChain.verify(input["expressions"] as? JsonArray ?: throw MathUnsupported("provide_2_to_50_expression_strings"), nodes)
                "rational_equation_equivalence" -> RationalEquationEquivalence.verify(string(input, "before_left"), string(input, "before_right"), string(input, "after_left"), string(input, "after_right"), nodes)
                "rational_limit" -> RationalLimit.verify(string(input, "expression"), string(input, "at"), string(input, "side"), nodes)
                "rational_monotonicity" -> RationalMonotonicity.verify(string(input, "expression"), nodes)
                "polynomial_definite_integral" -> PolynomialIntegral.verify(string(input, "expression"), string(input, "lower"), string(input, "upper"), string(input, "claimed_value"), nodes)
                "rational_primitive_integral" -> RationalPrimitiveIntegral.verify(string(input, "expression"), string(input, "primitive"), string(input, "lower"), string(input, "upper"), string(input, "claimed_value"), nodes)
                "polynomial_taylor" -> PolynomialTaylor.verify(string(input, "expression"), string(input, "center"), string(input, "order"),
                    input["claimed_coefficients"] as? JsonArray ?: throw MathUnsupported("taylor_coefficient_array_required"), nodes)
                "polynomial_uniform_bound" -> PolynomialUniformBound.verify(string(input, "expression"), string(input, "approximation"),
                    string(input, "lower"), string(input, "upper"), string(input, "claimed_bound"), nodes)
                "polynomial_uniform_bound_sturm" -> PolynomialSturmBound.verify(string(input, "expression"), string(input, "approximation"),
                    string(input, "lower"), string(input, "upper"), string(input, "claimed_bound"), nodes)
                "rational_uniform_bound_sturm" -> RationalSturmBound.verify(string(input, "expression"), string(input, "approximation"),
                    string(input, "lower"), string(input, "upper"), string(input, "claimed_bound"), nodes)
                "polynomial_uniform_bound_subdivision" -> {
                    val depth = string(input, "max_depth")
                    if (depth.length != 1 || depth[0] !in '0'..'8') throw MathUnsupported("subdivision_depth_0_to_8_required")
                    PolynomialUniformBound.verify(string(input, "expression"), string(input, "approximation"),
                        string(input, "lower"), string(input, "upper"), string(input, "claimed_bound"), nodes, depth[0] - '0')
                }
                else -> chain(input["expressions"] as? JsonArray ?: throw MathUnsupported("provide_2_to_50_expression_strings"), nodes)
            }
        } catch (e: MathUnsupported) { unsupported(e.reason) }
        currentCoroutineContext().ensureActive()
        buildJsonObject {
            for ((key, value) in result) put(key, value)
            put("contractVersion", if (professional) 16 else if (rationalBound) 15 else if (sturmBound) 14 else if (subdividedBound) 13 else if (uniformBound) 12 else if (taylor) 11 else if (rationalIntegral) 10 else if (integral) 9 else if (monotonicity) 8 else if (rationalLimit) 7 else if (equationEquivalence) 6 else if (rationalChain) 5 else if (equation) 4 else if (inequality) 3 else if (rational) 2 else 1)
            put("operation", operation)
            put("domain", if (professional) "explicit_Q_R_or_conditional_logic_scope" else if (rational) "Q(x)" else "Q[x]")
            put("scope", scope(operation))
            put("input_sha256", boundedInputHash(input)?.let(::JsonPrimitive) ?: JsonNull)
            put("ast_nodes", JsonArray(nodes.map(::JsonPrimitive)))
            put("limits", buildJsonObject {
                put("expression_chars", QxLimits.CHARS); put("ast_nodes", QxLimits.NODES); put("degree", QxLimits.DEGREE)
                put("coefficient_bits", QxLimits.BITS); put("parser_depth", QxLimits.DEPTH)
                if (rational) put("intermediate_degree", QxLimits.INTERMEDIATE_DEGREE) else put("chain_expressions", 50)
                if (rationalChain) put("chain_expressions", 50)
                if (inequality || equation || rationalChain || equationEquivalence || monotonicity || rationalIntegral || sturmBound || rationalBound) {
                    put("critical_degree", RealRootLimits.DEGREE); put("isolation_depth", RealRootLimits.DEPTH)
                    put("isolation_work_nodes", RealRootLimits.WORK_NODES); put("coordinate_bits", RealRootLimits.COORDINATE_BITS)
                    put("evaluation_bits", RealRootLimits.EVALUATION_BITS)
                }
                if (rationalLimit) { put("point_bits", QxLimits.BITS); put("evaluation_bits", RealRootLimits.EVALUATION_BITS) }
                if (integral) { put("endpoint_bits", QxLimits.BITS); put("antiderivative_degree", QxLimits.DEGREE + 1); put("evaluation_bits", PolynomialIntegral.EVALUATION_BITS) }
                if (rationalIntegral) { put("endpoint_bits", QxLimits.BITS); put("integral_evaluation_bits", RationalPrimitiveIntegral.EVALUATION_BITS) }
                if (taylor) { put("center_and_claim_bits", QxLimits.BITS); put("order", QxLimits.DEGREE); put("shift_coefficient_bits", PolynomialTaylor.SHIFT_BITS) }
                if (uniformBound || subdividedBound) { put("endpoint_and_claim_bits", QxLimits.BITS); put("bernstein_evaluation_bits", PolynomialUniformBound.EVALUATION_BITS) }
                if (subdividedBound) { put("subdivision_depth", PolynomialUniformSubdivision.MAX_DEPTH); put("subdivision_leaves", 1 shl PolynomialUniformSubdivision.MAX_DEPTH); put("certificate_chars", PolynomialUniformSubdivision.MAX_CERTIFICATE_CHARS) }
                if (sturmBound) { put("endpoint_and_claim_bits", QxLimits.BITS); put("certificate_chars", PolynomialSturmBound.MAX_CERTIFICATE_CHARS) }
                if (rationalBound) { put("endpoint_and_claim_bits", QxLimits.BITS); put("obligation_degree", RealRootLimits.DEGREE); put("certificate_chars", PolynomialSturmBound.MAX_CERTIFICATE_CHARS) }
            })
            put("notice", if (professional)
                (result["notice"] as? JsonPrimitive)?.content ?: "仅认证当前operation所陈述的明确范围，不是OCR、授权或完整用户目标成功。"
                else if (rationalBound)
                "仅核验两Q(x)原表达式在整个有理闭区间上的给定绝对误差界。先以完整Sturm证明原语法条件与分母全区间无孔洞/极点；约分、相减抵消或同端点不填原域。差N/D的双侧界归约为(claim*D±N)*D非负，依据D²严格正，不误用负分母方向。两侧完整精确符号证书才获证，错误界含原域精确有理反例，资源不足UNSUPPORTED无部分证书。不求最优界/极限补值/一般函数定理，不是授权或完整任务成功。"
                else if (sturmBound)
                "仅精确判定两Q[x]在有理闭区间的给定统一绝对误差界，不求最优界。分别对claim-error与claim+error构造完整Sturm根与符号单元证书，包含端点和重根；点值只在根完备后分类，不以采样证明。错误声明DISPROVED含精确有理反例，资源不足UNSUPPORTED且不发布部分证书。不是一般函数误差定理、授权或完整任务成功。"
                else if (subdividedBound)
                "仅核验两Q[x]在有理闭区间上的充分统一绝对误差界。精确中点de Casteljau细分Bernstein凸包，完整覆盖所有闭子区间；max_depth为0–8字符串，只细分未证实的非退化区间。到深度上限仍不足则NOT_CERTIFIED/UNSUPPORTED，不判界错或最优，不以采样证明。完整证书超限整体拒绝，不漏叶授真；不是一般函数误差定理、授权或完整任务成功。"
                else if (uniformBound)
                "仅用精确Bernstein非负和为1的基凸包，核验两Q[x]多项式在有理闭区间上的统一绝对误差界。包络是充分证书、未必最紧，不以采样证明；声明小于当前包络只表示本方法NOT_CERTIFIED/UNSUPPORTED，不证明该界错误或最优界。退化区间也精确处理；不扩大到一般/有理函数或给未经证明的误差结论，不是授权或完整任务成功。"
                else if (taylor)
                "仅核验Q[x]多项式在有限有理中心、指定0–32阶的完整系数声明，坐标h=x-center。精确Horner移位与余项因子恒等是依据，不以采样证明。余项/截断恒等使用计算所得系数，错误声明DISPROVED不能借计算证书授真；非零余项不得省略，不宣称截断式全局等于原式。不是一般函数Taylor定理、Lagrange余项/收敛半径/误差界、授权或完整任务成功。"
                else if (rationalIntegral)
                "仅核验有限有理端点间的有理函数定积分及所给有理原函数证书。对两式完整原定义域以Sturm证明整个闭区间无孔洞/极点，再精确核对原函数导数与有向端点差，不以采样证明、不填可去孔洞。证书导数不对时DISPROVED仅表示证书错误，claim_status=NOT_EVALUATED，不宣称积分值错误；区间不全定义返回UNSUPPORTED，不算反常积分/主值或证明发散。同端点仍须合法证书和定义域。不是一般积分、超越原函数、授权或完整任务成功。"
                else if (integral)
                "仅精确核验Q[x]多项式在有限有理端点间的有向定积分声明。多项式在全实数连续，逐项原函数和Newton-Leibniz端点评值是依据，不用数值采样；反向端点取负，同端点仍校验原式合法。VERIFIED只表示本声明值精确相等，不是一般有理函数/反常积分/积分交换/一般证明、OCR、授权或完整任务成功。"
                else if (monotonicity)
                "仅精确分类单变量有理函数原实定义域内的最大连通非退化严格递增/递减/常值区间及驻点。完整Sturm导数符号分解是依据，不以采样作证明；合法同号驻点不拆分单调区间，转折驻点可为两侧闭端点，原孔洞永不纳入。恒零导数在各连通分量常值，不能标严格单调；不声明跨不连通分量的整体序关系。VERIFIED仅本分类，不是参数/超越/一般证明、OCR、评分、授权或完整用户目标成功。"
                else if (rationalLimit)
                "仅精确分类单变量有理函数沿原实定义域在有理点或指定+inf/-inf尾部的极限，原式点值与极限分开。有理点side=left/right/both；无穷处只允许both且只指给定尾部，不比较正负无穷。可去孔洞不妨碍极限；极点符号和双侧不存在由精确阶数核对。VERIFIED只表示分类完成，does_not_exist不是极限存在证明，infinity不是有限值；不是一般极限、OCR、评分、授权或完整用户目标成功。"
                else if (equationEquivalence)
                "仅判断两个单变量有理方程在各自双侧原实定义域中的完整实解集是否相同，不先取两个方程域的交集。精确有限根集或余有限根集表示、平方自由gcd和Sturm完备实根证书核对增失解，复根不算实解。VERIFIED仅表示这一步限定同解；不是一般逻辑证明、手写OCR、评分、授权或完整用户目标成功。超越、多变量、参数与复数域不支持。"
                else if (rationalChain)
                "仅核验有理表达式等式链在第一表达式原实定义域上的逐步恒等。初始域始终保留，约分可扩大自然域但不能填原孔洞；后续新增实数失效点即错误，Sturm证书区分无实根因子与无理孔洞。仅在全部前缀已核验后定位首错；超范围停在未知步，不判断后缀。不是方程同解、一般证明、OCR、评分、授权或完整用户目标完成。"
                else if (equation)
                "仅精确求解left=right在两侧原实定义域交集中的全部实数解，约分或交叉相乘不填孔洞。Sturm实根完备证书与精确符号分解给出有限根、空集或恒等成立的合法区间；根端点由有序实根证书定义，不是浮点近似。VERIFIED仅表示这一限定解集完整求得，不是方程等价变形、一般证明、授权或完整任务成功；超越、多变量、参数与复数域不支持。"
                else if (inequality)
                "仅精确求解单变量有理表达式与0的指定不等式，解集受原表达式非零条件限制。根端点由所列临界多项式的有序实根及Sturm隔离证书精确定义，不是浮点近似。完整实根计数和无临界根单元的连续性保证符号恒定，精确单元点只用于符号分类，不是采样证明。孔洞/极点永不纳入；超越、多变量、参数定理或一般证明不支持。"
                else if (rational)
                "仅在所列原表达式非零条件的共同实定义域核验有理函数恒等或形式导数，不声称两侧定义域相同，不填补约分后的孔洞；不是方程同解或一般证明。交叉乘差的精确系数是依据，点值只为反例；UNSUPPORTED/DISPROVED不自动成为任务成功。"
                else "仅核验Q[x]多项式恒等、形式导数或相等表达式链；不是一般证明、方程等价、OCR或评分。精确系数是依据，整数点只为反例见证；UNSUPPORTED/DISPROVED不自动成为用户目标成功。")
        }
    }

    /** Recompute from durable input, not the model's status or claimed proof. */
    suspend fun accepts(input: JsonObject, candidate: JsonObject): Boolean {
        val actual = verify(input)
        if (actual.getValue("status").jsonPrimitive.content != "VERIFIED" || candidate.keys != actual.keys) return false
        if (input["operation"]?.jsonPrimitive?.content in ProfessionalMath.operations) return actual == candidate
        if ((candidate["notice"] as? JsonPrimitive)?.isString != true) return false
        return actual.all { (key, value) -> key == "notice" || candidate[key] == value }
    }
}
