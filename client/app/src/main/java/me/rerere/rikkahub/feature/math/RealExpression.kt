package me.rerere.rikkahub.feature.math

/** One bounded grammar for point enclosures and interval automatic differentiation. */
internal interface RealAlgebra<T> {
    fun rational(value: Rational): T
    fun constant(name: String): T
    fun negate(a: T): T
    fun add(a: T, b: T): T
    fun subtract(a: T, b: T): T
    fun multiply(a: T, b: T): T
    fun divide(a: T, b: T): T
    fun power(a: T, exponent: Int): T
    fun function(name: String, a: T): T
}

internal class RealExpression private constructor(private val root: Node) {
    private sealed interface Node {
        class Literal(val value: Rational) : Node
        class Name(val name: String) : Node
        class Unary(val function: String, val child: Node) : Node
        class Binary(val operation: Char, val left: Node, val right: Node) : Node
        class Power(val child: Node, val exponent: Int) : Node
    }
    fun <T> evaluate(algebra: RealAlgebra<T>, variables: Map<String, T>): T {
        fun value(node: Node): T = when (node) {
            is Node.Literal -> algebra.rational(node.value)
            is Node.Name -> if (node.name == "pi" || node.name == "e") algebra.constant(node.name)
                else variables[node.name] ?: throw MathUnsupported("unknown_numeric_variable")
            is Node.Unary -> if (node.function == "-") algebra.negate(value(node.child))
                else algebra.function(node.function, value(node.child))
            is Node.Binary -> {
                val a = value(node.left); val b = value(node.right)
                when (node.operation) { '+' -> algebra.add(a, b); '-' -> algebra.subtract(a, b); '*' -> algebra.multiply(a, b); else -> algebra.divide(a, b) }
            }
            is Node.Power -> algebra.power(value(node.child), node.exponent)
        }
        return value(root)
    }
    companion object {
        private val number = Regex("^(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        private val name = Regex("^[A-Za-z][A-Za-z0-9_]*")
        private val variable = Regex("[A-Za-z][A-Za-z0-9_]{0,15}")
        private val functions = setOf("sin", "cos", "tan", "ln", "log", "exp", "sqrt", "abs")
        fun checkVariable(name: String) {
            if (!name.matches(variable) || name == "pi" || name == "e" || name in functions) throw MathUnsupported("invalid_numeric_variable")
        }
        fun compile(source: String): RealExpression {
            if (source.length !in 1..4096) throw MathUnsupported("numeric_input_limit")
            var position = 0; var nodes = 0; var depth = 0
            fun whitespace() { while (position < source.length && source[position].isWhitespace()) position++ }
            fun token(c: Char): Boolean { whitespace(); if (position < source.length && source[position] == c) { position++; return true }; return false }
            fun count() { if (++nodes > 256) throw MathUnsupported("numeric_node_limit") }
            lateinit var sum: () -> Node
            lateinit var unary: () -> Node
            fun atom(): Node {
                count()
                if (token('(')) { val node = sum(); if (!token(')')) throw MathUnsupported("missing_numeric_parenthesis"); return node }
                whitespace(); val rest = source.substring(position)
                number.find(rest)?.let { position += it.value.length; return Node.Literal(ProfessionalScalar.parse(it.value)) }
                val identifier = name.find(rest)?.value ?: throw MathUnsupported("invalid_numeric_expression")
                position += identifier.length
                if (token('(')) {
                    if (identifier !in functions) throw MathUnsupported("unsupported_numeric_function")
                    val child = sum(); if (!token(')')) throw MathUnsupported("missing_numeric_parenthesis")
                    return Node.Unary(identifier, child)
                }
                return Node.Name(identifier)
            }
            unary = {
                if (++depth > 32) throw MathUnsupported("numeric_depth_limit")
                try {
                    when {
                        token('+') -> unary()
                        token('-') -> { count(); Node.Unary("-", unary()) }
                        else -> {
                            var node = atom()
                            if (token('^')) {
                                val sign = if (token('-')) -1 else { token('+'); 1 }
                                whitespace(); val start = position
                                while (position < source.length && source[position].isDigit()) position++
                                val exponent = source.substring(start, position).toIntOrNull() ?: throw MathUnsupported("numeric_integer_power_required")
                                if (exponent !in 0..32) throw MathUnsupported("numeric_power_limit")
                                count(); node = Node.Power(node, sign * exponent)
                            }
                            node
                        }
                    }
                } finally { depth-- }
            }
            fun product(): Node {
                var node = unary()
                while (true) {
                    val operation = if (token('*')) '*' else if (token('/')) '/' else return node
                    count(); node = Node.Binary(operation, node, unary())
                }
            }
            sum = {
                var node = product()
                var more = true
                while (more) {
                    val operation = if (token('+')) '+' else if (token('-')) '-' else null
                    if (operation == null) more = false else { count(); node = Node.Binary(operation, node, product()) }
                }
                node
            }
            val result = sum(); whitespace()
            if (position != source.length) throw MathUnsupported("invalid_numeric_expression")
            return RealExpression(result)
        }
    }
}
