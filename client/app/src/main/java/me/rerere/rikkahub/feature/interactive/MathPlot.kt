package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*
import kotlin.math.*

data class MathPoint(val x: Double, val y: Double)
data class MathCurve(val label: String, val segments: List<List<MathPoint>>)

/** Explicit/parametric curves and marching-squares zero contours in a fixed viewport. */
object MathPlot {
    val fields = setOf("xMin", "xMax", "yMin", "yMax", "curves", "points", "equalAspect")
    fun number(obj: JsonObject, key: String): Double = obj.getValue(key).jsonPrimitive.also { require(!it.isString) }.double.also {
        require(it.isFinite() && abs(it) <= 1e6) { "坐标或参数范围无效" }
    }
    fun validate(plot: JsonObject, parameters: Set<String>) {
        require(plot.keys.all { it in fields + setOf("id", "type") })
        require(parameters.none { it in setOf("x", "y", "t", "pi", "e", "sin", "cos", "tan", "sqrt", "abs", "ln", "log", "exp") }) { "滑块名称不能与数学变量或函数重名" }
        require(number(plot, "xMax") - number(plot, "xMin") in 1e-4..1e6)
        require(number(plot, "yMax") - number(plot, "yMin") in 1e-4..1e6)
        plot["equalAspect"]?.let { require(!it.jsonPrimitive.isString && it.jsonPrimitive.booleanOrNull != null) }
        val curves = plot.getValue("curves").jsonArray
        require(curves.size in 1..6)
        curves.forEach { item ->
            val c = item.jsonObject
            val kind = c.getValue("kind").jsonPrimitive.content
            require(c.getValue("label").jsonPrimitive.let { it.isString && it.content.length in 1..100 })
            val names = when (kind) {
                "function" -> setOf("expression", "min", "max")
                "parametric" -> setOf("x", "y", "min", "max")
                "implicit" -> setOf("expression")
                else -> error("不支持的数学曲线")
            }
            require(c.keys.all { it in names + setOf("kind", "label") })
            val vars = parameters + when (kind) { "function" -> setOf("x"); "parametric" -> setOf("t"); else -> setOf("x", "y") }
            (if (kind == "parametric") listOf("x", "y") else listOf("expression")).forEach { key ->
                MathExpression.parse(c.getValue(key).jsonPrimitive.also { require(it.isString) }.content, vars)
            }
            if (kind == "parametric" || c.containsKey("min") || c.containsKey("max")) require(number(c, "max") > number(c, "min"))
        }
        plot["points"]?.jsonArray?.also { require(it.size <= 20) }?.forEach {
            val p = it.jsonObject
            require(p.keys == setOf("x", "y", "label"))
            number(p, "x"); number(p, "y")
            require(p.getValue("label").jsonPrimitive.let { s -> s.isString && s.content.length in 1..80 })
        }
    }

    fun sample(plot: JsonObject, values: Map<String, Double>): List<MathCurve> {
        validate(plot, values.keys)
        val x0 = number(plot, "xMin"); val x1 = number(plot, "xMax")
        val y0 = number(plot, "yMin"); val y1 = number(plot, "yMax")
        return plot.getValue("curves").jsonArray.map { item ->
            val c = item.jsonObject; val kind = c.getValue("kind").jsonPrimitive.content
            val vars = values.keys + setOf("x", "y", "t")
            fun expr(key: String) = MathExpression.parse(c.getValue(key).jsonPrimitive.content, vars)
            val segments = mutableListOf<List<MathPoint>>()
            if (kind == "implicit") {
                val f = expr("expression"); val n = 100
                fun p(i: Int, j: Int) = MathPoint(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * j / n)
                fun value(p: MathPoint) = f.value(values + mapOf("x" to p.x, "y" to p.y))
                val grid = Array(n + 1) { i -> DoubleArray(n + 1) { j -> value(p(i, j)) } }
                for (i in 0 until n) for (j in 0 until n) {
                    val corners = listOf(p(i,j), p(i+1,j), p(i+1,j+1), p(i,j+1))
                    val v = listOf(grid[i][j], grid[i+1][j], grid[i+1][j+1], grid[i][j+1])
                    if (v.any { !it.isFinite() }) continue
                    val hits = mutableListOf<MathPoint>()
                    for (k in 0..3) {
                        val next = (k + 1) % 4
                        if ((v[k] < 0) != (v[next] < 0)) {
                            val a = corners[k]; val b = corners[next]
                            var low = 0.0; var high = 1.0; var lowValue = v[k]
                            repeat(24) {
                                val ratio = (low+high)/2
                                val at = value(MathPoint(a.x+ratio*(b.x-a.x),a.y+ratio*(b.y-a.y)))
                                if ((at < 0) == (lowValue < 0)) { low=ratio; lowValue=at } else high=ratio
                            }
                            val ratio = (low+high)/2
                            val hit = MathPoint(a.x + ratio * (b.x-a.x), a.y + ratio * (b.y-a.y))
                            // A sign change across a pole is not a zero contour.
                            val residual = value(hit)
                            if (residual.isFinite() && abs(residual) <= max(1e-8, v.maxOf { abs(it) } * 1e-5)) hits.add(hit)
                        }
                    }
                    if (hits.size == 2) segments.add(hits)
                    // Ambiguous saddle cells are omitted rather than joining the wrong branches.
                }
            } else {
                val fx = if (kind == "parametric") expr("x") else null
                val fy = expr(if (kind == "parametric") "y" else "expression")
                val start = c["min"]?.jsonPrimitive?.double ?: x0
                val end = c["max"]?.jsonPrimitive?.double ?: x1
                var current = mutableListOf<MathPoint>()
                fun flush() { if (current.size >= 2) segments.add(current); current = mutableListOf() }
                for (i in 0..1200) {
                    val t = start + (end-start) * i / 1200
                    val env = values + mapOf((if (kind == "parametric") "t" else "x") to t)
                    val p = MathPoint(fx?.value(env) ?: t, fy.value(env))
                    val previous = current.lastOrNull()
                    if (!p.x.isFinite() || !p.y.isFinite() || p.x !in x0..x1 || p.y !in y0..y1) { flush(); continue }
                    if (previous != null) {
                        val midpoint = start + (end-start) * (i-0.5) / 1200
                        val middleEnv = values + mapOf((if (kind == "parametric") "t" else "x") to midpoint)
                        val my = fy.value(middleEnv)
                        val mx = fx?.value(middleEnv) ?: midpoint
                        fun discontinuous(mid: Double, a: Double, b: Double, span: Double) =
                            abs(mid-(a+b)/2) > max(1e-8, maxOf(abs(mid),abs(a),abs(b))*0.35+span*1e-7)
                        if (!my.isFinite() || !mx.isFinite() || discontinuous(my,p.y,previous.y,y1-y0) || discontinuous(mx,p.x,previous.x,x1-x0)) flush()
                    }
                    current.add(p)
                }
                flush()
            }
            MathCurve(c.getValue("label").jsonPrimitive.content, segments)
        }
    }
}
