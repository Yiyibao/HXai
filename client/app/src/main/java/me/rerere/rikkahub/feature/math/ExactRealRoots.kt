package me.rerere.rikkahub.feature.math

import java.math.BigInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal object RealRootLimits {
    const val DEGREE = 32
    const val DEPTH = 256
    const val WORK_NODES = 8192
    const val COORDINATE_BITS = 4096
    const val EVALUATION_BITS = (DEGREE + 1) * QxLimits.BITS + DEGREE * (QxLimits.BITS + DEPTH + 4) + 64
    val HALF = Rational.fraction(BigInteger.ONE, BigInteger.valueOf(2))
    fun coordinate(value: Rational): Rational {
        if (maxOf(value.numerator.abs().bitLength(), value.denominator.bitLength()) > COORDINATE_BITS) throw MathUnsupported("root_coordinate_limit")
        return value
    }
}

/** Zero polynomials contribute no finite critical roots. Multiplicities are removed before union. */
internal object CriticalPolynomials {
    fun squareFree(value: Polynomial): Polynomial {
        if (value.degree == 0) return Polynomial.ONE
        return value.exactQuotient(value.gcd(value.derivative())).monic()
    }
    fun union(left: Polynomial, right: Polynomial): Polynomial {
        if (right.degree == 0) return left
        if (left.degree == 0) return right
        val common = left.gcd(right)
        if (left.degree + right.degree - common.degree > RealRootLimits.DEGREE) throw MathUnsupported("critical_degree_limit")
        return left.exactQuotient(common).multiply(right).monic()
    }
}

internal data class SturmEvaluation(val variations: Int, val isRoot: Boolean)

/** Only positive scaling is allowed: monic normalization of a negative remainder changes root counts. */
internal class SturmSequence private constructor(val polynomials: List<Polynomial>) {
    fun at(value: Rational): SturmEvaluation {
        RealRootLimits.coordinate(value)
        var previous = 0
        var variations = 0
        var root = false
        for ((index, polynomial) in polynomials.withIndex()) {
            val sign = polynomial.value(value, RealRootLimits.EVALUATION_BITS).sign
            if (index == 0) root = sign == 0
            if (sign == 0) continue
            if (previous != 0 && sign != previous) variations++
            previous = sign
        }
        return SturmEvaluation(variations, root)
    }
    fun countOpen(lower: Rational, upper: Rational): Int {
        require(lower < upper)
        val a = at(lower); val b = at(upper)
        // Ignoring zeros gives the right-hand variation at a simple root; subtract a root at b.
        return a.variations - b.variations - if (b.isRoot) 1 else 0
    }
    fun json(): JsonArray = JsonArray(polynomials.map { it.json() })
    companion object {
        suspend fun build(squareFree: Polynomial): SturmSequence {
            check(!squareFree.isZero)
            val sequence = ArrayList<Polynomial>()
            sequence.add(squareFree)
            var a = squareFree
            var b = squareFree.derivative().positiveScaleNormalized()
            while (!b.isZero) {
                currentCoroutineContext().ensureActive()
                sequence.add(b)
                val remainder = a.remainder(b).negate().positiveScaleNormalized()
                a = b; b = remainder
            }
            return SturmSequence(sequence)
        }
    }
}

/** A point is exact; an open interval is certified to contain exactly one root of the common polynomial. */
internal data class IsolatedRealRoot(val lower: Rational, val upper: Rational) {
    val isExact get() = lower.compareTo(upper) == 0
    fun json(index: Int): JsonObject = buildJsonObject {
        put("index", index)
        put("isolation", if (isExact) buildJsonObject {
            put("kind", "rational_point"); put("value", lower.toString())
        } else buildJsonObject {
            put("kind", "open_rational_interval"); put("lower", lower.toString()); put("upper", upper.toString()); put("root_count", 1)
        })
    }
}

internal data class RootIsolation(val roots: List<IsolatedRealRoot>, val bound: Rational, val lowerVariations: Int, val upperVariations: Int, val workNodes: Int)

internal object ExactRealRootIsolation {
    private data class Frame(val lower: Rational, val upper: Rational, val a: SturmEvaluation, val b: SturmEvaluation, val depth: Int)
    suspend fun isolate(polynomial: Polynomial, sturm: SturmSequence): RootIsolation {
        val bound = RealRootLimits.coordinate(Rational.integer(polynomial.strictRootBound()))
        val lower = bound.negate()
        val a = sturm.at(lower); val b = sturm.at(bound)
        check(!a.isRoot && !b.isRoot)
        val expected = a.variations - b.variations
        check(expected in 0..polynomial.degree)
        val roots = ArrayList<IsolatedRealRoot>(expected)
        val pending = ArrayDeque<Frame>()
        pending.addLast(Frame(lower, bound, a, b, 0))
        var work = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++work > RealRootLimits.WORK_NODES) throw MathUnsupported("root_work_limit")
            val frame = pending.removeLast()
            val count = frame.a.variations - frame.b.variations - if (frame.b.isRoot) 1 else 0
            check(count in 0..polynomial.degree)
            if (count == 0) continue
            if (count == 1 && !frame.a.isRoot && !frame.b.isRoot) {
                roots.add(IsolatedRealRoot(frame.lower, frame.upper)); continue
            }
            if (frame.depth >= RealRootLimits.DEPTH) throw MathUnsupported("root_isolation_depth_limit")
            val middle = RealRootLimits.coordinate(frame.lower.add(frame.upper).multiply(RealRootLimits.HALF))
            val m = sturm.at(middle)
            if (m.isRoot) roots.add(IsolatedRealRoot(middle, middle))
            pending.addLast(Frame(middle, frame.upper, m, frame.b, frame.depth + 1))
            pending.addLast(Frame(frame.lower, middle, frame.a, m, frame.depth + 1))
        }
        check(roots.size == expected) { "Sturm isolation did not cover every real root" }
        roots.sortBy { it.lower }
        for (index in 1 until roots.size) check(roots[index - 1].upper <= roots[index].lower)
        return RootIsolation(roots, bound, a.variations, b.variations, work)
    }
}
