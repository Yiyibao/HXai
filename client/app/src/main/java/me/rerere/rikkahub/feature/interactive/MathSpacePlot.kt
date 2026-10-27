package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*
import kotlin.math.*

/** Orthographic 3D visualization. Sampling is evidence for a picture, never a proof. */
object MathSpacePlot {
    val fields = setOf("xMin", "xMax", "yMin", "yMax", "zMin", "zMax", "azimuth", "elevation", "curves")
    fun validate(plot: JsonObject, parameters: Set<String>) {
        require(plot.keys.all { it in fields + setOf("id", "type") })
        require(parameters.none { it in setOf("x", "y", "z", "t", "pi", "e", "sin", "cos", "tan", "sqrt", "abs", "ln", "log", "exp") })
        for (axis in listOf("x", "y", "z")) require(MathPlot.number(plot, "${axis}Max") - MathPlot.number(plot, "${axis}Min") in 1e-4..1e6)
        require(MathPlot.number(plot, "azimuth") in -360.0..360.0 && MathPlot.number(plot, "elevation") in -85.0..85.0)
        val curves = plot.getValue("curves").jsonArray; require(curves.size in 1..4)
        for (item in curves) {
            val c = item.jsonObject; val kind = c.getValue("kind").jsonPrimitive.content
            require(c.getValue("label").jsonPrimitive.let { it.isString && it.content.length in 1..100 })
            val keys = when (kind) { "surface" -> setOf("expression"); "space_curve" -> setOf("x", "y", "z", "min", "max"); else -> error("不支持的三维图形") }
            require(c.keys == keys + setOf("kind", "label"))
            val variables = parameters + if (kind == "surface") setOf("x", "y") else setOf("t")
            for (key in if (kind == "surface") listOf("expression") else listOf("x", "y", "z"))
                MathExpression.parse(c.getValue(key).jsonPrimitive.also { require(it.isString) }.content, variables)
            if (kind == "space_curve") require(MathPlot.number(c, "max") > MathPlot.number(c, "min"))
        }
    }
    private class WorldPoint(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0)
    private class Projection(plot: JsonObject, azimuth: Double, elevation: Double) {
        private val x0 = MathPlot.number(plot, "xMin"); private val sx = 2 / (MathPlot.number(plot, "xMax") - x0)
        private val y0 = MathPlot.number(plot, "yMin"); private val sy = 2 / (MathPlot.number(plot, "yMax") - y0)
        private val z0 = MathPlot.number(plot, "zMin"); private val sz = 2 / (MathPlot.number(plot, "zMax") - z0)
        private val ca = cos(azimuth * PI / 180); private val sa = sin(azimuth * PI / 180)
        private val ce = cos(elevation * PI / 180); private val se = sin(elevation * PI / 180)
        fun point(x: Double, y: Double, z: Double): MathPoint {
            val nx = (x - x0) * sx - 1; val ny = (y - y0) * sy - 1; val nz = (z - z0) * sz - 1
            return MathPoint(ca * nx - sa * ny, -se * (sa * nx + ca * ny) + ce * nz)
        }
    }
    fun sample(plot: JsonObject, parameters: Map<String, Double>, azimuth: Double = MathPlot.number(plot, "azimuth"), elevation: Double = MathPlot.number(plot, "elevation")): List<MathCurve> {
        validate(plot, parameters.keys)
        require(parameters.values.all { it.isFinite() && abs(it) <= 1e12 })
        val x0 = MathPlot.number(plot, "xMin"); val x1 = MathPlot.number(plot, "xMax")
        val y0 = MathPlot.number(plot, "yMin"); val y1 = MathPlot.number(plot, "yMax")
        val z0 = MathPlot.number(plot, "zMin"); val z1 = MathPlot.number(plot, "zMax")
        val projection = Projection(plot, azimuth, elevation)
        return plot.getValue("curves").jsonArray.map { item ->
            val curve = item.jsonObject; val surface = curve.getValue("kind").jsonPrimitive.content == "surface"
            val variables = parameters.keys + if (surface) setOf("x", "y") else setOf("t")
            fun expression(key: String) = MathExpression.parse(curve.getValue(key).jsonPrimitive.content, variables)
            val fx = if (surface) null else expression("x"); val fy = if (surface) null else expression("y")
            val fz = expression(if (surface) "expression" else "z")
            val segments = ArrayList<List<MathPoint>>()
            val environment = parameters.toMutableMap()
            val current = WorldPoint(); val middle = WorldPoint()
            fun trace(count: Int, point: (Double, WorldPoint) -> Unit) {
                var run = ArrayList<MathPoint>(count + 1)
                var hasPrevious = false; var previousX = 0.0; var previousY = 0.0; var previousZ = 0.0
                fun flush() { if (run.size >= 2) { segments += run; run = ArrayList(count + 1) } else run.clear(); hasPrevious = false }
                fun jumps(mid: Double, now: Double, previous: Double, range: Double): Boolean =
                    !mid.isFinite() || abs(mid - (now + previous) / 2) > max(1e-8, maxOf(abs(mid), abs(now), abs(previous)) * .35 + range * 1e-7)
                for (i in 0..count) {
                    point(i.toDouble() / count, current)
                    val x = current.x; val y = current.y; val z = current.z
                    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || x !in x0..x1 || y !in y0..y1 || z !in z0..z1) { flush(); continue }
                    if (hasPrevious) {
                        point((i - 0.5) / count, middle)
                        if (jumps(middle.x, x, previousX, x1 - x0) || jumps(middle.y, y, previousY, y1 - y0) || jumps(middle.z, z, previousZ, z1 - z0)) flush()
                    }
                    run += projection.point(x, y, z); previousX = x; previousY = y; previousZ = z; hasPrevious = true
                }
                flush()
            }
            if (surface) {
                for (axis in 0..1) for (line in 0..20) trace(60) { fraction, output ->
                    output.x = x0 + (x1 - x0) * if (axis == 0) fraction else line / 20.0
                    output.y = y0 + (y1 - y0) * if (axis == 0) line / 20.0 else fraction
                    environment["x"] = output.x; environment["y"] = output.y
                    output.z = fz.value(environment)
                }
            } else {
                val low = MathPlot.number(curve, "min"); val high = MathPlot.number(curve, "max")
                trace(800) { fraction, output ->
                    environment["t"] = low + (high - low) * fraction
                    output.x = fx!!.value(environment); output.y = fy!!.value(environment); output.z = fz.value(environment)
                }
            }
            MathCurve(curve.getValue("label").jsonPrimitive.content, segments)
        }
    }
}
