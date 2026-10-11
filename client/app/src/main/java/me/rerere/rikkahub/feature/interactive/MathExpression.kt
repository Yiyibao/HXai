package me.rerere.rikkahub.feature.interactive

import kotlin.math.*

/** A bounded mathematical grammar, never a script evaluator. Multiplication must be explicit. */
class MathExpression private constructor(private val evaluate: (Map<String, Double>) -> Double) {
    fun value(variables: Map<String, Double>): Double = runCatching { evaluate(variables) }.getOrDefault(Double.NaN)

    companion object {
        fun parse(source: String, variables: Set<String>): MathExpression {
            require(source.length in 1..256) { "公式长度必须为 1–256" }
            return MathExpression(Parser(source, variables).parse())
        }
    }

    private class Parser(val source: String, val variables: Set<String>) {
        var position = 0
        var nodes = 0
        var depth = 0
        fun token(c: Char): Boolean {
            while (position < source.length && source[position].isWhitespace()) position++
            if (position < source.length && source[position] == c) { position++; return true }
            return false
        }
        fun parse(): (Map<String, Double>) -> Double {
            val result = sum()
            token(' ')
            require(position == source.length) { "公式含未支持的符号或缺少 *" }
            return result
        }
        fun sum(): (Map<String, Double>) -> Double {
            var result = product()
            while (true) {
                val op = when { token('+') -> '+'; token('-') -> '-'; else -> return result }
                val left = result; val right = product(); count()
                result = { v -> if (op == '+') left(v) + right(v) else left(v) - right(v) }
            }
        }
        fun product(): (Map<String, Double>) -> Double {
            var result = unary()
            while (true) {
                val op = when { token('*') -> '*'; token('/') -> '/'; else -> return result }
                val left = result; val right = unary(); count()
                result = { v -> if (op == '*') left(v) * right(v) else left(v) / right(v) }
            }
        }
        fun unary(): (Map<String, Double>) -> Double {
            enter()
            try {
                if (token('+')) return unary()
                if (token('-')) { val arg = unary(); count(); return { -arg(it) } }
                return power()
            } finally { depth-- }
        }
        fun power(): (Map<String, Double>) -> Double {
            val base = atom()
            if (!token('^')) return base
            val exponent = unary(); count()
            return { base(it).pow(exponent(it)) }
        }
        fun atom(): (Map<String, Double>) -> Double {
            count()
            if (token('(')) { val result = sum(); require(token(')')) { "缺少右括号" }; return result }
            token(' ')
            val rest = source.substring(position)
            Regex("^(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?").find(rest)?.let {
                position += it.value.length
                val n = it.value.toDouble(); require(n.isFinite() && abs(n) <= 1e12)
                return { n }
            }
            val name = Regex("^[A-Za-z][A-Za-z0-9_]*").find(rest)?.value ?: error("无效公式")
            position += name.length
            if (token('(')) {
                require(name in setOf("sin", "cos", "tan", "sqrt", "abs", "ln", "log", "exp")) { "不支持函数 $name" }
                val arg = sum(); require(token(')'))
                return { v -> val x = arg(v); when (name) {
                    "sin" -> sin(x); "cos" -> cos(x); "tan" -> tan(x); "sqrt" -> sqrt(x)
                    "abs" -> abs(x); "ln" -> ln(x); "log" -> log10(x); else -> exp(x)
                } }
            }
            if (name == "pi") return { PI }
            if (name == "e") return { E }
            require(name in variables) { "未知变量 $name" }
            return { it[name] ?: Double.NaN }
        }
        fun count() { require(++nodes <= 96) { "公式过于复杂" } }
        fun enter() { require(++depth <= 16) { "公式嵌套过深" } }
    }
}
