package me.rerere.rikkahub.feature.interactive

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.rikkahub.feature.material.MaterialWriter
import me.rerere.rikkahub.feature.material.SafeOfficeXml
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** One bounded sample per export batch; actual vectors, not raster renamed as SVG/PDF/PPTX. */
class MathPlotExport(private val title: String, plot: JsonObject, values: Map<String, Double>) {
    private data class Stroke(val color: String, val width: Double, val segments: List<List<MathPoint>>)
    private data class Label(val text: String, val x: Double, val y: Double, val size: Int = 14, val color: String = "232832")
    private data class Marker(val x: Double, val y: Double, val open: Boolean)
    private val strokes = ArrayList<Stroke>()
    private val labels = ArrayList<Label>()
    private val markers = ArrayList<Marker>()
    init {
        labels += Label(title, 40.0, 38.0, 24)
        val space = plot["type"] == JsonPrimitive("math_plot_3d")
        val sampled = if (space) MathSpacePlot.sample(plot, values) else MathPlot.sample(plot, values)
        require(sampled.any { it.segments.isNotEmpty() }) { "所选域和视窗没有可交付图形" }
        val x0 = MathPlot.number(plot, "xMin"); val x1 = MathPlot.number(plot, "xMax")
        val y0 = MathPlot.number(plot, "yMin"); val y1 = MathPlot.number(plot, "yMax")
        var sx = 840.0 / (x1 - x0); var sy = 480.0 / (y1 - y0)
        if (plot["equalAspect"]?.jsonPrimitive?.booleanOrNull != false) { val scale = min(sx, sy); sx = scale; sy = scale }
        val left = 80 + (840 - (x1 - x0) * sx) / 2; val top = 80 + (480 - (y1 - y0) * sy) / 2
        fun screen(x: Double, y: Double) = if (space) MathPoint(500 + x * 140, 320 - y * 140) else MathPoint(left + (x - x0) * sx, top + (y1 - y) * sy)
        fun line(a: MathPoint, b: MathPoint) { strokes += Stroke("B8BDC7", 1.0, listOf(listOf(a, b))) }
        if (space) {
            val a = MathPlot.number(plot, "azimuth") * PI / 180; val e = MathPlot.number(plot, "elevation") * PI / 180
            val axes = listOf(MathPoint(cos(a), -sin(e) * sin(a)), MathPoint(-sin(a), -sin(e) * cos(a)), MathPoint(0.0, cos(e)))
            axes.forEachIndexed { i, p -> line(screen(-p.x, -p.y), screen(p.x, p.y)); val end = screen(p.x, p.y); labels += Label(listOf("x", "y", "z")[i], end.x + 5, end.y - 5) }
        } else {
            for (i in 0..4) {
                val x = x0 + (x1 - x0) * i / 4; val y = y0 + (y1 - y0) * i / 4
                line(screen(x, y0), screen(x, y1)); line(screen(x0, y), screen(x1, y))
                val xp = screen(x, y0); val yp = screen(x0, y)
                labels += Label(String.format(Locale.US, "%.4g", x), xp.x - 14, xp.y + 18, 12)
                labels += Label(String.format(Locale.US, "%.4g", y), yp.x - 43, yp.y + 4, 12)
            }
            if (x0 <= 0 && x1 >= 0) { line(screen(0.0, y0), screen(0.0, y1)); val end = screen(0.0, y1); labels += Label("y", end.x + 5, end.y + 15) }
            if (y0 <= 0 && y1 >= 0) { line(screen(x0, 0.0), screen(x1, 0.0)); val end = screen(x1, 0.0); labels += Label("x", end.x + 5, end.y) }
            for (value in plot["points"]?.jsonArray.orEmpty()) {
                val point = value.jsonObject; val position = screen(MathPlot.number(point, "x"), MathPlot.number(point, "y"))
                if (position.x in left..(left + (x1 - x0) * sx) && position.y in top..(top + (y1 - y0) * sy)) {
                    markers += Marker(position.x, position.y, point["open"] == JsonPrimitive(true))
                    labels += Label(point.getValue("label").jsonPrimitive.content, position.x + 8, position.y - 8)
                }
            }
        }
        sampled.forEachIndexed { index, curve ->
            strokes += Stroke(COLORS[index], 2.0, curve.segments.map { segment -> segment.map { screen(it.x, it.y) } })
            labels += Label(curve.label, (40 + index % 2 * 470).toDouble(), (610 + index / 2 * 22).toDouble(), 14, COLORS[index])
        }
        val domain = if (space) "3D orthographic; x=[$x0,$x1], y=[$y0,$y1], z=[${MathPlot.number(plot,"zMin")},${MathPlot.number(plot,"zMax")}]" else "x=[$x0,$x1], y=[$y0,$y1]"
        labels += Label(domain, 40.0, 668.0, 11)
        labels += Label("Numeric sampled geometry, not a proof; undeclared holes may be missed. Editable paths are not analytic equations.", 40.0, 688.0, 11)
    }
    private fun svg() = buildString {
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1000 700\" width=\"1000\" height=\"700\"><title>${escape(title)}</title><rect width=\"1000\" height=\"700\" fill=\"white\"/>")
        for (stroke in strokes) {
            append("<path fill=\"none\" stroke=\"#${stroke.color}\" stroke-width=\"${stroke.width}\" d=\"")
            for (segment in stroke.segments) segment.forEachIndexed { i, p -> append(if (i == 0) 'M' else 'L'); append(coordinate(p.x)); append(' '); append(coordinate(p.y)); append(' ') }
            append("\"/>")
        }
        for (p in markers) append("<circle cx=\"${coordinate(p.x)}\" cy=\"${coordinate(p.y)}\" r=\"4\" fill=\"${if(p.open) "white" else "#232832"}\" stroke=\"#232832\" stroke-width=\"2\"/>")
        for (label in labels) append("<text x=\"${coordinate(label.x)}\" y=\"${coordinate(label.y)}\" font-size=\"${label.size}\" fill=\"#${label.color}\">${escape(label.text)}</text>")
        append("</svg>")
    }
    private fun canvas(canvas: Canvas) {
        canvas.drawColor(Color.WHITE); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (stroke in strokes) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke.width.toFloat(); paint.color = Color.parseColor("#${stroke.color}")
            for (segment in stroke.segments) { val path = Path(); segment.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x.toFloat(),p.y.toFloat()) else path.lineTo(p.x.toFloat(),p.y.toFloat()) }; canvas.drawPath(path, paint) }
        }
        for (p in markers) { paint.color = Color.rgb(35,40,50); paint.strokeWidth = 2f; paint.style = if (p.open) Paint.Style.STROKE else Paint.Style.FILL; canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),4f,paint) }
        paint.style = Paint.Style.FILL
        for (label in labels) { paint.color = Color.parseColor("#${label.color}"); paint.textSize = label.size.toFloat(); canvas.drawText(label.text,label.x.toFloat(),label.y.toFloat(),paint) }
    }
    suspend fun write(file: File, extension: String) {
        currentCoroutineContext().ensureActive()
        when (extension) {
            "svg" -> file.writeText(svg(), Charsets.UTF_8)
            "pdf" -> { val document = PdfDocument(); try { val page = document.startPage(PdfDocument.PageInfo.Builder(1000,700,1).create()); canvas(page.canvas); document.finishPage(page); file.outputStream().use(document::writeTo) } finally { document.close() } }
            "png" -> { val bitmap = createBitmap(1000,700); try { canvas(Canvas(bitmap)); file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() } }
            "pptx" -> pptx(file)
            else -> error("未支持的数学图形导出格式")
        }
        verify(file,extension); currentCoroutineContext().ensureActive()
    }
    /** Independently reopen the delivered bytes; metadata or a successful writer is insufficient. */
    fun verify(file: File, extension: String) {
        when (extension) {
            "svg" -> { val bytes = file.readBytes(); SafeOfficeXml.parse(bytes); check(bytes.contentEquals(svg().toByteArray(Charsets.UTF_8))) }
            "pdf" -> { val document = com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath); try { check(document.countPages() == 1); val page = document.loadPage(0); try { val box = page.bounds; check(abs(box.x1-box.x0-1000) < 1 && abs(box.y1-box.y0-700) < 1) } finally { page.destroy() } } finally { document.destroy() } }
            "png" -> { val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)); try { check(bitmap.width == 1000 && bitmap.height == 700) } finally { bitmap.recycle() } }
            "pptx" -> ZipFile(file).use { archive ->
                val xml = archive.getInputStream(checkNotNull(archive.getEntry("ppt/slides/slide1.xml"))).use { it.readBytes() }; val parsed = SafeOfficeXml.parse(xml)
                check(parsed.getElementsByTagNameNS(DRAWING, "custGeom").length == strokes.size)
                check(parsed.getElementsByTagNameNS(DRAWING, "blip").length == 0)
                check(xml.toString(Charsets.UTF_8).contains(pptxShapes()))
            }
        }
    }
    private fun pptxShapes() = buildString {
        var id = 3
        fun begin(name: String) { append("<p:sp><p:nvSpPr><p:cNvPr id=\"${id++}\" name=\"${escape(name)}\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>") }
        for ((index, stroke) in strokes.withIndex()) {
            begin("sampled-path-$index")
            append("<a:xfrm><a:off x=\"${PPT_OFFSET_X.roundToInt()}\" y=\"0\"/><a:ext cx=\"${(PPT_SCALE*1000).roundToInt()}\" cy=\"6858000\"/></a:xfrm><a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l=\"0\" t=\"0\" r=\"r\" b=\"b\"/><a:pathLst><a:path w=\"1000000\" h=\"1000000\" fill=\"none\" stroke=\"1\" extrusionOk=\"0\">")
            for (segment in stroke.segments) segment.forEachIndexed { i,p -> append(if(i==0) "<a:moveTo>" else "<a:lnTo>"); append("<a:pt x=\"${(p.x*1000).roundToInt()}\" y=\"${(p.y/700*1000000).roundToInt()}\"/>"); append(if(i==0) "</a:moveTo>" else "</a:lnTo>") }
            append("</a:path></a:pathLst></a:custGeom><a:noFill/><a:ln w=\"${(stroke.width*PPT_SCALE).roundToInt()}\"><a:solidFill><a:srgbClr val=\"${stroke.color}\"/></a:solidFill></a:ln></p:spPr></p:sp>")
        }
        for (point in markers) {
            begin("${if(point.open) "excluded" else "fixed"}-point")
            append("<a:xfrm><a:off x=\"${(PPT_OFFSET_X+(point.x-4)*PPT_SCALE).roundToInt()}\" y=\"${((point.y-4)*PPT_SCALE).roundToInt()}\"/><a:ext cx=\"${(8*PPT_SCALE).roundToInt()}\" cy=\"${(8*PPT_SCALE).roundToInt()}\"/></a:xfrm><a:prstGeom prst=\"ellipse\"><a:avLst/></a:prstGeom>")
            append(if(point.open) "<a:noFill/>" else "<a:solidFill><a:srgbClr val=\"232832\"/></a:solidFill>")
            append("<a:ln w=\"19050\"><a:solidFill><a:srgbClr val=\"232832\"/></a:solidFill></a:ln></p:spPr></p:sp>")
        }
        for (label in labels) {
            begin(label.text.take(100))
            append("<a:xfrm><a:off x=\"${(PPT_OFFSET_X+label.x*PPT_SCALE).roundToInt()}\" y=\"${((label.y-label.size)*PPT_SCALE).roundToInt()}\"/><a:ext cx=\"${((1000-label.x)*PPT_SCALE).roundToInt()}\" cy=\"${((label.size+10)*PPT_SCALE).roundToInt()}\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr wrap=\"none\" lIns=\"0\" rIns=\"0\" tIns=\"0\" bIns=\"0\"/><a:lstStyle/><a:p><a:r><a:rPr sz=\"${(label.size*PPT_SCALE/127).roundToInt()}\"><a:solidFill><a:srgbClr val=\"${label.color}\"/></a:solidFill></a:rPr><a:t>${escape(label.text)}</a:t></a:r></a:p></p:txBody></p:sp>")
        }
    }
    private suspend fun pptx(file: File) {
        val base = File.createTempFile("math-plot-", ".pptx", file.parentFile)
        try {
            MaterialWriter.write(base,"pptx","",emptyList(),listOf(title.take(100)))
            val shapes = pptxShapes()
            ZipFile(base).use { input -> ZipOutputStream(file.outputStream()).use { output -> for (entry in input.entries()) {
                currentCoroutineContext().ensureActive(); output.putNextEntry(ZipEntry(entry.name))
                if (entry.name == "ppt/slides/slide1.xml") {
                    val original = input.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val start = original.indexOf("<p:sp>"); val end = original.indexOf("</p:sp>", start)
                    check(start >= 0 && end > start && original.indexOf("<p:sp>", end) == -1)
                    val empty = original.removeRange(start, end + "</p:sp>".length)
                    check(empty.contains("</p:spTree>")); output.write(empty.replace("</p:spTree>", "$shapes</p:spTree>").toByteArray(Charsets.UTF_8))
                }
                else input.getInputStream(entry).use { it.copyTo(output) }; output.closeEntry()
            } } }
        } finally { check(base.delete() || !base.exists()) }
    }
    companion object {
        private const val DRAWING = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val PPT_SCALE = 6858000.0 / 700
        private const val PPT_OFFSET_X = (12192000 - PPT_SCALE * 1000) / 2
        private val COLORS = arrayOf("2364CD","DC4B37","149164","9150BE","CD8714","2391A5")
        private fun coordinate(value: Double) = String.format(Locale.US,"%.3f",value)
        private fun escape(text: String): String { require(text.none { it.code < 32 && it !in "\n\r\t" }); return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;") }
    }
}
