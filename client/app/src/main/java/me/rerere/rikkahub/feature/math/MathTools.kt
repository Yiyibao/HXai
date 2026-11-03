package me.rerere.rikkahub.feature.math

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun mathVerifyTool(): Tool = Tool(
    name = "math_verify",
    description = "执行单变量精确数学，不调用模型。operation：Q[x] identity(left,right)、derivative(expression,claimed_derivative)、equal_expression_chain(expressions)、polynomial_definite_integral(expression,lower,upper,claimed_value)核验有限有理端点的有向定积分声明，精确原函数与端点评值、不支持反常积分；Q(x) rational_identity(left,right)、rational_derivative(expression,claimed_derivative)核验共同原域；rational_inequality(expression,relation)求与0的>、>=、<、<=；rational_equation(left,right)求原域实解；rational_expression_chain(expressions)固定第一式域逐步恒等；rational_equation_equivalence(before_left,before_right,after_left,after_right)比较各自原域完整实解；rational_limit(expression,at,side)分类有理点/指定±inf极限，VERIFIED可含DNE/无穷，不证明有限极限存在；rational_monotonicity(expression)求原域最大连通非退化严格增/减/常值分支和驻点，合法同号驻点须合并、转折可闭端点、孔洞不能纳入。精确系数/gcd/Sturm/极点阶和首项证据，不以采样证明；保全部原条件。只传该操作字段。语法整数、x、四则、括号、^/**非负整数幂；每式4096字符/AST256/次数与幂32/系数1024bits/嵌套64，链2–50式；lower/upper/claimed_value为1024bits有限整数或分数字面；at可再取+inf/-inf；side=left/right/both，无穷只both。资源超限UNSUPPORTED，无eval。超越、多变量、参数/一般证明、复数域、OCR/评分不支持。scope/hash/证据不是授权或完整任务完成。" +
        " rational_primitive_integral(expression,primitive,lower,upper,claimed_value)：提供有理原函数证书，Sturm核验两式整个闭区间无原始孔洞/极点，再精确验证导数及有向端点差；错误证书DISPROVED且claim_status=NOT_EVALUATED不判积分值错，域不全定义UNSUPPORTED，不求反常积分/主值或ln原函数。" +
        " polynomial_taylor(expression,center,order,claimed_coefficients)：Q[x]在有理中心的0–32阶精确展开声明；center/order字符串，claimed_coefficients为按h=x-center升幂恰好order+1个有理数字面字符串含零项。返回计算系数与完整精确余项，非零余项不许省略；不证明一般函数Taylor定理/收敛/误差界。" +
        " polynomial_uniform_bound(expression,approximation,lower,upper,claimed_bound)：有理有序闭区间上两Q[x]差的统一绝对误差界；六字段均字符串，界非负。精确Bernstein凸包给充分但未必最紧包络，方法不足返回NOT_CERTIFIED/UNSUPPORTED不判该界错，绝不以采样作证明。" +
        " polynomial_uniform_bound_subdivision(expression,approximation,lower,upper,claimed_bound,max_depth)：同一误差界的有界精确中点细分证书；七字段均字符串，max_depth为0–8。完整覆盖所有闭子区间，只有每叶充分界都满足才VERIFIED；到限仍不足保持NOT_CERTIFIED，不能把漏叶、采样或达到深度上限当证明。" +
        " polynomial_uniform_bound_sturm(expression,approximation,lower,upper,claimed_bound)：六字符串字段；精确Sturm判定整个有理闭区间上claim±误差均非负，含端点与重根。给定正确界VERIFIED，错误界DISPROVED含精确有理反例，资源不足UNSUPPORTED；不求最优界，不以采样证明。" +
        " rational_uniform_bound_sturm(expression,approximation,lower,upper,claimed_bound)：六字符串字段，Q(x)两原式必须在整个有理闭区间有定义，约分或误差抵消不填孔洞。以差N/D的(claim*D±N)*D完整Sturm非负证书核验双侧，负分母也保持方向；乘后次数≤32。错误界有理反例，域不全定义或资源不足UNSUPPORTED，不求最优界或去孔极限。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("operation", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("identity"); add("derivative"); add("equal_expression_chain"); add("rational_identity"); add("rational_derivative"); add("rational_inequality"); add("rational_equation"); add("rational_expression_chain"); add("rational_equation_equivalence"); add("rational_limit"); add("rational_monotonicity"); add("polynomial_definite_integral"); add("rational_primitive_integral"); add("polynomial_taylor"); add("polynomial_uniform_bound"); add("polynomial_uniform_bound_subdivision"); add("polynomial_uniform_bound_sturm"); add("rational_uniform_bound_sturm") }) })
        for (key in listOf("left", "right", "expression", "claimed_derivative", "primitive", "approximation", "claimed_bound")) put(key, buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) })
        for (key in listOf("before_left", "before_right", "after_left", "after_right")) put(key, buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) })
        put("at", buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) })
        for (key in listOf("lower", "upper", "claimed_value")) put(key, buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) })
        for (key in listOf("center", "order")) put(key, buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) })
        put("max_depth", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { for (depth in 0..PolynomialUniformSubdivision.MAX_DEPTH) add(depth.toString()) }) })
        put("claimed_coefficients", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", QxLimits.DEGREE + 1); put("items", buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) }) })
        put("side", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("left"); add("right"); add("both") }) })
        put("relation", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(">"); add(">="); add("<"); add("<=") }) })
        put("expressions", buildJsonObject { put("type", "array"); put("minItems", 2); put("maxItems", 50); put("items", buildJsonObject { put("type", "string"); put("maxLength", QxLimits.CHARS) }) })
    }, required = listOf("operation")) },
    execute = { input ->
        val objectInput = input as? JsonObject ?: throw IllegalArgumentException("数学输入必须为JSON对象")
        val result = MathVerification.verify(objectInput)
        listOf(UIMessagePart.Text(result.toString()))
    },
)
