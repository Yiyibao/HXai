package me.rerere.rikkahub.feature.interactive

import android.graphics.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.*

/** A real PNG for delivery; sampled paths are shared with mathematical validation tests. */
object MathPlotImage {
    private val colors = intArrayOf(Color.rgb(35, 100, 205), Color.rgb(220, 75, 55), Color.rgb(20, 145, 100), Color.rgb(145, 80, 190), Color.rgb(205, 135, 20), Color.rgb(35, 145, 165))
    fun write(file: File, title: String, plot: JsonObject, values: Map<String, Double>) {
        val curves = MathPlot.sample(plot, values)
        require(curves.any { it.segments.isNotEmpty() }) { "当前视窗没有可绘制的曲线，请核对公式和视窗" }
        val bitmap = createBitmap(1000, 1140)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(35, 40, 50); paint.textSize = 30f
            canvas.drawText(title.take(28), 70f, 45f, paint)
            val x0 = MathPlot.number(plot, "xMin"); val x1 = MathPlot.number(plot, "xMax")
            val y0 = MathPlot.number(plot, "yMin"); val y1 = MathPlot.number(plot, "yMax")
            var sx = 850.0/(x1-x0); var sy = 850.0/(y1-y0)
            if (plot["equalAspect"]?.jsonPrimitive?.booleanOrNull != false) { val s = min(sx,sy); sx=s; sy=s }
            val left = 75+(850-(x1-x0)*sx)/2; val top = 80+(850-(y1-y0)*sy)/2
            fun x(n: Double) = (left+(n-x0)*sx).toFloat()
            fun y(n: Double) = (top+(y1-n)*sy).toFloat()
            paint.strokeWidth=1f; paint.textSize=18f
            for (i in 0..10) {
                val vx=x0+(x1-x0)*i/10; val vy=y0+(y1-y0)*i/10
                paint.color=Color.rgb(230,233,238)
                canvas.drawLine(x(vx), y(y0), x(vx), y(y1), paint)
                canvas.drawLine(x(x0), y(vy), x(x1), y(vy), paint)
                paint.color=Color.DKGRAY
                canvas.drawText("%.2g".format(java.util.Locale.US,vx), x(vx)-15, y(y0)+25, paint)
                canvas.drawText("%.2g".format(java.util.Locale.US,vy), x(x0)-55, y(vy)+6, paint)
            }
            paint.color=Color.GRAY; paint.strokeWidth=2f
            if (0.0 in x0..x1) canvas.drawLine(x(0.0),y(y0),x(0.0),y(y1),paint)
            if (0.0 in y0..y1) canvas.drawLine(x(x0),y(0.0),x(x1),y(0.0),paint)
            canvas.withClip(x(x0),y(y1),x(x1),y(y0)) {
            curves.forEachIndexed { index, curve ->
                paint.color=colors[index]; paint.strokeWidth=3f; paint.style=Paint.Style.STROKE
                curve.segments.forEach { points ->
                    val path=Path(); points.forEachIndexed { i,p -> if(i==0) path.moveTo(x(p.x),y(p.y)) else path.lineTo(x(p.x),y(p.y)) }; canvas.drawPath(path,paint)
                }
            }
            paint.style=Paint.Style.FILL; paint.color=Color.BLACK; paint.textSize=22f
            plot["points"]?.jsonArray?.forEach { item ->
                val p=item.jsonObject; val px=x(MathPlot.number(p,"x")); val py=y(MathPlot.number(p,"y"))
                canvas.drawCircle(px,py,5f,paint); canvas.drawText(p.getValue("label").jsonPrimitive.content,px+8,py-8,paint)
            }
            }
            curves.forEachIndexed { index,curve ->
                paint.color=colors[index]; paint.textSize=20f
                val count = paint.breakText(curve.label,true,410f,null)
                val label = if(count < curve.label.length) curve.label.take(max(0,count-1))+"…" else curve.label
                canvas.drawText(label,75f+(index%2)*440,975f+(index/2)*32,paint)
            }
            paint.color=Color.DKGRAY; paint.textSize=18f
            canvas.drawText("数值绘图辅助理解；精确结论以推导为准。",75f,1090f,paint)
            canvas.drawText("x / y 坐标；参数：${values.entries.joinToString { "${it.key}=${it.value}" }}".take(90),75f,1120f,paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
}
