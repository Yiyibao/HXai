package me.rerere.rikkahub.feature.math

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Maximal connected nondegenerate branches; stationary roots do not automatically split monotonicity. */
internal object RationalMonotonicity {
    private val empty = JsonArray(emptyList())

    suspend fun verify(expression: String, nodes: MutableList<Int>): JsonObject {
        val policy = RationalFunctionArithmetic()
        val value = ExactExpressionParser(expression, policy).parse().also { nodes += it.astNodes }.value
        currentCoroutineContext().ensureActive()
        val derivative = value.derivative()
        val evidence = RationalRealSolver.solve(derivative, policy.nonzeroPolynomials, "==")
        val roots = evidence.getValue("roots").jsonArray
        val cells = evidence.getValue("cells").jsonArray
        val constant = derivative.numerator.isZero
        val increasing: JsonArray
        val decreasing: JsonArray
        val points = ArrayList<JsonElement>()
        if (constant) {
            increasing = empty; decreasing = empty
        } else {
            val positive = BooleanArray(roots.size * 2 + 1)
            val negative = BooleanArray(roots.size * 2 + 1)
            val signs = IntArray(cells.size)
            for (index in cells.indices) {
                signs[index] = cells[index].jsonObject.getValue("sign").jsonPrimitive.int
                check(signs[index] != 0) { "Nonzero derivative vanishes inside a certified root-free cell" }
                positive[index * 2] = signs[index] > 0
                negative[index * 2] = signs[index] < 0
            }
            for (index in roots.indices) {
                currentCoroutineContext().ensureActive()
                if (roots[index].jsonObject.getValue("excluded").jsonPrimitive.boolean) continue
                val left = signs[index]; val right = signs[index + 1]
                // Include a legal stationary endpoint in each adjacent strict branch;
                // if signs agree, this joins both cells into one maximal branch.
                positive[index * 2 + 1] = left > 0 || right > 0
                negative[index * 2 + 1] = left < 0 || right < 0
                points.add(buildJsonObject {
                    put("root_index", index)
                    put("classification", when {
                        left > 0 && right < 0 -> "local_maximum"
                        left < 0 && right > 0 -> "local_minimum"
                        else -> "stationary_non_extremum"
                    })
                })
            }
            increasing = RationalRealSolver.intervals(positive, roots.size)
            decreasing = RationalRealSolver.intervals(negative, roots.size)
        }
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true)
            put("proof_kind", "exact_derivative_sturm_monotonic_branches")
            put("branch_scope", "maximal_connected_non_degenerate_intervals_in_original_real_domain")
            put("canonical_rational_function", value.json()); put("derivative_evidence", evidence)
            put("increasing_intervals", increasing); put("decreasing_intervals", decreasing)
            put("constant_intervals", if (constant) evidence.getValue("solution_intervals") else empty)
            put("stationary_everywhere", constant); put("stationary_points", JsonArray(points))
        }
    }
}
