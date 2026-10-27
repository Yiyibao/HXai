package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Decides a supplied bound, not an optimal bound. Roots are complete before cell signs are used. */
internal object PolynomialSturmBound {
    const val MAX_CERTIFICATE_CHARS = 1048576
    private object BoundArithmetic : ExpressionArithmetic<Polynomial> by PolynomialArithmetic {
        override fun power(value: Polynomial, exponent: Int): Polynomial {
            if (exponent == 0 && value.isZero) throw MathUnsupported("undefined_zero_power")
            return PolynomialArithmetic.power(value, exponent)
        }
    }
    private data class Frame(val lower: Rational, val upper: Rational, val a: SturmEvaluation, val b: SturmEvaluation, val depth: Int)
    data class SignProof(val certificate: JsonObject, val negativeAt: Rational?)
    private fun midpoint(a: Rational, b: Rational) = RealRootLimits.coordinate(a.add(b).multiply(RealRootLimits.HALF))
    private fun absolute(value: Rational) = if (value.sign < 0) value.negate() else value
    private fun endpoint(index: Int, roots: Int): JsonObject = buildJsonObject {
        put("kind", if (index < 0) "lower_endpoint" else if (index == roots) "upper_endpoint" else "root")
        if (index in 0 until roots) put("index", index)
    }

    /** Restrict isolation to [a,b]; no comparison between an algebraic root and an unrefined rational endpoint. */
    suspend fun nonnegative(polynomial: Polynomial, a: Rational, b: Rational): SignProof {
        val av = polynomial.value(a, RealRootLimits.EVALUATION_BITS)
        val bv = polynomial.value(b, RealRootLimits.EVALUATION_BITS)
        var negativeAt = if (av.sign < 0) a else if (bv.sign < 0) b else null
        if (a.compareTo(b) == 0) return SignProof(buildJsonObject {
            put("proof_kind", "exact_point_evaluation"); put("coefficients", polynomial.json())
            put("endpoint_values", buildJsonObject { put("lower", av.toString()); put("upper", bv.toString()) })
            put("nonnegative", negativeAt == null)
        }, negativeAt)
        currentCoroutineContext().ensureActive()
        val critical = CriticalPolynomials.squareFree(polynomial)
        val sturm = SturmSequence.build(critical)
        val sa = sturm.at(a); val sb = sturm.at(b)
        val expectedOpen = sa.variations - sb.variations - if (sb.isRoot) 1 else 0
        check(expectedOpen in 0..critical.degree)
        val roots = ArrayList<IsolatedRealRoot>(expectedOpen + 2)
        if (sa.isRoot) roots.add(IsolatedRealRoot(a, a))
        if (sb.isRoot) roots.add(IsolatedRealRoot(b, b))
        val pending = ArrayDeque<Frame>()
        pending.addLast(Frame(a, b, sa, sb, 0))
        var work = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++work > RealRootLimits.WORK_NODES) throw MathUnsupported("root_work_limit")
            val frame = pending.removeLast()
            val count = frame.a.variations - frame.b.variations - if (frame.b.isRoot) 1 else 0
            check(count in 0..critical.degree)
            if (count == 0) continue
            if (count == 1 && !frame.a.isRoot && !frame.b.isRoot) {
                roots.add(IsolatedRealRoot(frame.lower, frame.upper)); continue
            }
            if (frame.depth >= RealRootLimits.DEPTH) throw MathUnsupported("root_isolation_depth_limit")
            val middle = midpoint(frame.lower, frame.upper)
            val sm = sturm.at(middle)
            if (sm.isRoot) roots.add(IsolatedRealRoot(middle, middle))
            pending.addLast(Frame(middle, frame.upper, sm, frame.b, frame.depth + 1))
            pending.addLast(Frame(frame.lower, middle, frame.a, sm, frame.depth + 1))
        }
        check(roots.size == expectedOpen + (if (sa.isRoot) 1 else 0) + (if (sb.isRoot) 1 else 0))
        roots.sortBy { it.lower }
        for (i in 1 until roots.size) check(roots[i - 1].upper <= roots[i].lower)
        val cells = ArrayList<JsonElement>(roots.size + 1)
        for (i in 0..roots.size) {
            currentCoroutineContext().ensureActive()
            // A root at a closed endpoint leaves no exterior cell in the requested interval.
            if (i == 0 && roots.firstOrNull()?.let { it.isExact && it.lower.compareTo(a) == 0 } == true) continue
            if (i == roots.size && roots.lastOrNull()?.let { it.isExact && it.upper.compareTo(b) == 0 } == true) continue
            val sample = when {
                roots.isEmpty() -> midpoint(a, b)
                i == 0 -> if (roots.first().isExact) midpoint(a, roots.first().lower) else roots.first().lower
                i == roots.size -> if (roots.last().isExact) midpoint(roots.last().upper, b) else roots.last().upper
                else -> midpoint(roots[i - 1].upper, roots[i].lower)
            }
            val value = polynomial.value(sample, RealRootLimits.EVALUATION_BITS)
            check(polynomial.isZero || !value.isZero)
            if (negativeAt == null && value.sign < 0) negativeAt = sample
            cells.add(buildJsonObject {
                put("index", i); put("lower", endpoint(i - 1, roots.size)); put("upper", endpoint(i, roots.size))
                put("sample", sample.toString()); put("value", value.toString()); put("sign", value.sign)
            })
        }
        return SignProof(buildJsonObject {
            put("proof_kind", "complete_closed_interval_sturm_sign_decomposition")
            put("coefficients", polynomial.json()); put("square_free_coefficients", critical.json()); put("sturm_sequence", sturm.json())
            put("endpoint_values", buildJsonObject { put("lower", av.toString()); put("upper", bv.toString()) })
            put("lower_variations", sa.variations); put("upper_variations", sb.variations)
            put("lower_is_root", sa.isRoot); put("upper_is_root", sb.isRoot); put("open_interval_root_count", expectedOpen)
            put("closed_interval_root_count", roots.size); put("isolation_work_nodes", work)
            put("roots", JsonArray(roots.mapIndexed { i, r -> r.json(i) })); put("cells", JsonArray(cells))
            put("nonnegative", negativeAt == null)
        }, negativeAt)
    }

    /** Conservative escaped JSON size without allocating a second serialized certificate. */
    fun size(value: JsonElement): Long = when (value) {
        is JsonObject -> 2L + value.entries.sumOf { 6L * it.key.length + 4 + size(it.value) }
        is JsonArray -> 2L + value.sumOf { 1 + size(it) }
        else -> 2L + 6L * value.jsonPrimitive.content.length
    }

    suspend fun verify(expression: String, approximation: String, lower: String, upper: String,
        claimedBound: String, nodes: MutableList<Int>): JsonObject {
        val a = PolynomialIntegral.number(lower); val b = PolynomialIntegral.number(upper); val claim = PolynomialIntegral.number(claimedBound)
        if (a > b) throw MathUnsupported("ordered_finite_interval_required")
        if (claim.sign < 0) throw MathUnsupported("nonnegative_uniform_bound_required")
        val source = ExactExpressionParser(expression, BoundArithmetic).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        val approximate = ExactExpressionParser(approximation, BoundArithmetic).parse().also { nodes += it.astNodes }.value
        val error = source.add(approximate, subtract = true)
        val constant = Polynomial.constant(claim.numerator).divide(Polynomial.constant(claim.denominator))
        val upperProof = nonnegative(constant.add(error, subtract = true), a, b)
        val lowerProof = nonnegative(constant.add(error), a, b)
        val witness = upperProof.negativeAt ?: lowerProof.negativeAt
        val certificate = buildJsonObject {
            put("status", if (witness == null) "VERIFIED" else "DISPROVED"); put("verified", witness == null)
            put("claim_status", if (witness == null) "VERIFIED" else "DISPROVED")
            put("proof_kind", "exact_two_sided_closed_interval_sturm_nonnegativity")
            put("source_coefficients", source.json()); put("approximation_coefficients", approximate.json()); put("error_coefficients", error.json())
            put("lower", a.toString()); put("upper", b.toString()); put("claimed_bound", claim.toString())
            put("upper_obligation", upperProof.certificate); put("lower_obligation", lowerProof.certificate)
            if (witness != null) {
                val value = error.value(witness, RealRootLimits.EVALUATION_BITS)
                check(absolute(value) > claim)
                put("counterexample", buildJsonObject {
                    put("x", witness.toString()); put("error", value.toString()); put("absolute_error", absolute(value).toString())
                })
            }
        }
        // Reserve room for the common contract metadata, limits and notice. No partial proof escapes.
        if (size(certificate) + 8192 > MAX_CERTIFICATE_CHARS) throw MathUnsupported("sturm_bound_certificate_size_limit")
        return certificate
    }
}
