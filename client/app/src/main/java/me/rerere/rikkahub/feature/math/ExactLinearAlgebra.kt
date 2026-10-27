package me.rerere.rikkahub.feature.math

import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Bounded scalar input, shared by exact linear algebra and proof certificates. */
internal object ProfessionalScalar {
    const val BITS = 16384
    fun checked(value: Rational): Rational {
        if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength()) > BITS) throw MathUnsupported("professional_coefficient_limit")
        return value
    }
    fun parse(text: String): Rational {
        if (text.length !in 1..512) throw MathUnsupported("scalar_length_limit")
        val pieces = text.split('/')
        if (pieces.size !in 1..2) throw MathUnsupported("rational_literal_required")
        fun decimal(s: String): Rational {
            if (!s.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]{1,3})?"))) throw MathUnsupported("rational_literal_required")
            val value = BigDecimal(s)
            if (kotlin.math.abs(value.scale()) > 512) throw MathUnsupported("scalar_exponent_limit")
            return checked(if (value.scale() >= 0) Rational.fraction(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
                else Rational.integer(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale()))))
        }
        val a = decimal(pieces[0])
        return if (pieces.size == 1) a else checked(a.multiply(decimal(pieces[1]).reciprocal()))
    }
    fun read(value: JsonElement): Rational = parse((value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw MathUnsupported("scalar_string_required"))
    fun add(a: Rational, b: Rational) = checked(a.add(b))
    fun subtract(a: Rational, b: Rational) = checked(a.add(b.negate()))
    fun multiply(a: Rational, b: Rational) = checked(a.multiply(b))
    fun divide(a: Rational, b: Rational) = checked(a.multiply(b.reciprocal()))
}

/** Exact row reduction, including singular/rectangular systems and conditioning. */
internal object ExactLinearAlgebra {
    suspend fun verify(input: JsonObject): JsonObject {
        val operation = input.getValue("action").jsonPrimitive.content
        val fields = if (operation == "solve") setOf("action", "matrix", "rhs") else setOf("action", "matrix")
        if (input.keys != fields || operation !in setOf("solve", "rref", "inverse", "determinant")) throw MathUnsupported("invalid_linear_algebra_fields")
        val rows = input.getValue("matrix") as? JsonArray ?: throw MathUnsupported("matrix_required")
        if (rows.size !in 1..12) throw MathUnsupported("matrix_dimension_limit")
        val columns = (rows.first() as? JsonArray)?.size ?: throw MathUnsupported("matrix_rows_required")
        if (columns !in 1..12 || rows.any { it !is JsonArray || it.size != columns }) throw MathUnsupported("rectangular_matrix_required")
        if (operation in setOf("inverse", "determinant") && rows.size != columns) throw MathUnsupported("square_matrix_required")
        val original = Array(rows.size) { r -> Array(columns) { c -> ProfessionalScalar.read(rows[r].jsonArray[c]) } }
        val right = if (operation == "solve") {
            val rhs = input.getValue("rhs") as? JsonArray ?: throw MathUnsupported("rhs_vector_required")
            if (rhs.size != rows.size) throw MathUnsupported("rhs_dimension_mismatch")
            Array(rows.size) { ProfessionalScalar.read(rhs[it]) }
        } else null
        val extra = if (operation == "inverse") rows.size else if (right != null) 1 else 0
        val reduced = Array(rows.size) { r -> Array(columns + extra) { c ->
            when { c < columns -> original[r][c]; right != null -> right[r]; c - columns == r -> Rational.ONE; else -> Rational.ZERO }
        } }
        val pivots = ArrayList<Int>()
        var determinant = Rational.ONE
        var pivotRow = 0
        for (column in 0 until columns) {
            currentCoroutineContext().ensureActive()
            val selected = (pivotRow until rows.size).firstOrNull { !reduced[it][column].isZero } ?: continue
            if (selected != pivotRow) {
                val saved = reduced[pivotRow]; reduced[pivotRow] = reduced[selected]; reduced[selected] = saved
                determinant = determinant.negate()
            }
            val pivot = reduced[pivotRow][column]
            determinant = ProfessionalScalar.multiply(determinant, pivot)
            for (c in column until columns + extra) reduced[pivotRow][c] = ProfessionalScalar.divide(reduced[pivotRow][c], pivot)
            for (r in reduced.indices) if (r != pivotRow && !reduced[r][column].isZero) {
                val factor = reduced[r][column]
                for (c in column until columns + extra) reduced[r][c] = ProfessionalScalar.subtract(reduced[r][c], ProfessionalScalar.multiply(factor, reduced[pivotRow][c]))
            }
            pivots += column
            if (++pivotRow == rows.size) break
        }
        if (pivots.size < columns) determinant = Rational.ZERO
        fun vector(values: Array<Rational>) = JsonArray(values.map { JsonPrimitive(it.toString()) })
        fun matrix(values: Array<Array<Rational>>) = JsonArray(values.map(::vector))
        val singular = rows.size == columns && pivots.size < columns
        return buildJsonObject {
            put("status", "VERIFIED"); put("verified", true)
            put("proof_kind", "exact_rational_row_operations_with_residual_readback")
            put("rank", pivots.size); put("rows", rows.size); put("columns", columns)
            put("pivot_columns", JsonArray(pivots.map { JsonPrimitive(it + 1) }))
            put("rref", matrix(Array(rows.size) { r -> Array(columns) { c -> reduced[r][c] } }))
            if (rows.size == columns) { put("determinant", determinant.toString()); put("singular", singular) }
            if (operation == "inverse" && !singular) {
                val inverse = Array(rows.size) { r -> Array(columns) { c -> reduced[r][columns + c] } }
                for (r in original.indices) for (c in original.indices) {
                    var value = Rational.ZERO
                    for (i in 0 until columns) value = ProfessionalScalar.add(value, ProfessionalScalar.multiply(original[r][i], inverse[i][c]))
                    check(value.sameAs(if (r == c) Rational.ONE else Rational.ZERO))
                }
                fun norm(a: Array<Array<Rational>>): Rational = a.maxOf { row -> row.fold(Rational.ZERO) { sum, v -> ProfessionalScalar.add(sum, if (v.sign < 0) v.negate() else v) } }
                put("inverse", matrix(inverse))
                put("condition_number_infinity_exact", ProfessionalScalar.multiply(norm(original), norm(inverse)).toString())
                put("inverse_residual", "0")
            }
            if (operation == "inverse" && singular) { put("inverse", JsonNull); put("classification", "SINGULAR_NO_INVERSE") }
            if (right != null) {
                val inconsistent = reduced.any { row -> (0 until columns).all { row[it].isZero } && !row[columns].isZero }
                put("classification", if (inconsistent) "INCONSISTENT" else if (pivots.size == columns) "UNIQUE" else "AFFINE_FAMILY")
                if (!inconsistent) {
                    val particular = Array(columns) { Rational.ZERO }
                    pivots.forEachIndexed { r, c -> particular[c] = reduced[r][columns] }
                    val free = (0 until columns).filter { it !in pivots }
                    val basis = free.map { f -> Array(columns) { c -> if (c == f) Rational.ONE else Rational.ZERO }.also { v ->
                        pivots.forEachIndexed { r, c -> v[c] = reduced[r][f].negate() }
                    } }
                    fun residual(v: Array<Rational>, rhs: Array<Rational>?) {
                        for (r in original.indices) {
                            var sum = Rational.ZERO
                            for (c in 0 until columns) sum = ProfessionalScalar.add(sum, ProfessionalScalar.multiply(original[r][c], v[c]))
                            check(sum.sameAs(rhs?.get(r) ?: Rational.ZERO))
                        }
                    }
                    residual(particular, right); basis.forEach { residual(it, null) }
                    put("particular_solution", vector(particular)); put("nullspace_basis", JsonArray(basis.map(::vector)))
                    put("free_columns", JsonArray(free.map { JsonPrimitive(it + 1) })); put("exact_residual", "0")
                }
            }
            put("notice", "精确行运算只认证所给矩阵/向量；病态性不因高精度而消失。奇异/无解是已核验的分类，不是虚构逆矩阵；不验证题目建模或自然语言证明。")
        }
    }
    private fun Rational.sameAs(other: Rational) = numerator == other.numerator && denominator == other.denominator
}
