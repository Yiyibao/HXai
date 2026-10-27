package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Every terminal interval is retained, including those whose bound remains unproved. */
internal object PolynomialUniformSubdivision {
    const val MAX_DEPTH = 8
    const val MAX_CERTIFICATE_CHARS = 1_048_576
    private val half = Rational.fraction(BigInteger.ONE, BigInteger.valueOf(2L))
    private data class Interval(val lower: Rational, val upper: Rational, val depth: Int, val coefficients: Array<Rational>)
    private fun absolute(value: Rational) = if (value.sign < 0) value.negate() else value
    private fun bounded(value: Rational): Rational {
        if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength()) > PolynomialUniformBound.EVALUATION_BITS) throw MathUnsupported("bernstein_coefficient_limit")
        return value
    }

    suspend fun certify(initial: JsonObject, lower: Rational, upper: Rational, claim: Rational,
        coefficients: Array<Rational>, maxDepth: Int): JsonObject {
        require(maxDepth in 0..MAX_DEPTH)
        // Conservative headroom includes the wrapper's hash, limits and scope; never truncate leaves.
        var characters = initial.toString().length + 4096
        fun charge(count: Int) {
            characters += count
            if (characters > MAX_CERTIFICATE_CHARS - 4096) throw MathUnsupported("subdivision_certificate_size_limit")
        }
        charge(0)
        val pending = ArrayDeque<Interval>()
        pending.addLast(Interval(lower, upper, 0, coefficients))
        val leaves = ArrayList<JsonElement>()
        var splits = 0; var deepest = 0; var depthLimited = false
        var globalLower = Rational.ZERO; var globalUpper = Rational.ZERO
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val interval = pending.removeLast(); val values = interval.coefficients
            var low = values[0]; var high = values[0]
            for (i in 1 until values.size) { if (values[i] < low) low = values[i]; if (values[i] > high) high = values[i] }
            val bound = maxOf(absolute(low), absolute(high))
            val stop = when {
                bound <= claim -> "bound_certified"
                interval.lower.compareTo(interval.upper) == 0 -> "point_interval"
                values.size == 1 -> "constant_error"
                interval.depth == maxDepth -> "depth_limit"
                else -> null
            }
            if (stop == null) {
                val n = values.lastIndex
                val row = values.copyOf()
                val left = Array(n + 1) { Rational.ZERO }; val right = Array(n + 1) { Rational.ZERO }
                left[0] = row[0]; right[n] = row[n]
                for (level in 1..n) {
                    currentCoroutineContext().ensureActive()
                    for (i in 0..n - level) row[i] = bounded(row[i].add(row[i + 1]).multiply(half))
                    left[level] = row[0]; right[n - level] = row[n - level]
                }
                val midpoint = interval.lower.add(interval.upper).multiply(half)
                val depth = interval.depth + 1
                pending.addLast(Interval(midpoint, interval.upper, depth, right))
                pending.addLast(Interval(interval.lower, midpoint, depth, left))
                splits++
            } else {
                val leaf = buildJsonObject {
                    put("lower", interval.lower.toString()); put("upper", interval.upper.toString()); put("depth", interval.depth)
                    put("bernstein_coefficients", JsonArray(values.map { JsonPrimitive(it.toString()) }))
                    put("error_enclosure", buildJsonObject { put("lower", low.toString()); put("upper", high.toString()) })
                    put("certified_absolute_bound", bound.toString()); put("stop_reason", stop)
                }
                charge(leaf.toString().length + 1)
                if (leaves.isEmpty() || low < globalLower) globalLower = low
                if (leaves.isEmpty() || high > globalUpper) globalUpper = high
                leaves.add(leaf); deepest = maxOf(deepest, interval.depth)
                if (stop == "depth_limit") depthLimited = true
            }
        }
        val bound = maxOf(absolute(globalLower), absolute(globalUpper)); val accepted = claim >= bound
        return buildJsonObject {
            put("status", if (accepted) "VERIFIED" else "UNSUPPORTED"); put("verified", accepted)
            put("claim_status", if (accepted) "VERIFIED" else "NOT_CERTIFIED")
            if (!accepted) put("reason", "claimed_bound_not_certified_within_subdivision_limits")
            put("proof_kind", "exact_midpoint_de_casteljau_bernstein_partition_convex_hulls")
            put("bound_kind", "sufficient_not_necessarily_sharp")
            for (key in listOf("source_coefficients", "approximation_coefficients", "error_coefficients", "lower", "upper", "interval_width", "claimed_bound", "bernstein_degree", "bernstein_basis", "claim_comparison")) put(key, initial.getValue(key))
            for (key in listOf("bernstein_coefficients", "unit_interval_power_coefficients", "error_enclosure", "certified_absolute_bound")) put("initial_$key", initial.getValue(key))
            put("coordinate", "each_leaf:x=leaf.lower+(leaf.upper-leaf.lower)*t,t_in_closed_unit_interval")
            put("subdivision_strategy", "depth_first_left_to_right_split_only_uncertified_nonpoint_nonconstant_intervals")
            put("coverage", "all_terminal_closed_intervals_including_uncertified_leaves")
            put("max_depth", maxDepth); put("deepest_leaf", deepest); put("depth_limit_reached", depthLimited)
            put("split_count", splits); put("leaf_count", leaves.size); put("leaves", JsonArray(leaves))
            put("error_enclosure", buildJsonObject { put("lower", globalLower.toString()); put("upper", globalUpper.toString()) })
            put("certified_absolute_bound", bound.toString())
        }
    }
}
