package me.rerere.rikkahub.feature.material

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.withClip
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Deliberately bounded, editable basic documents. No shell, arbitrary URL, or external relationship. */
object MaterialWriter {
    fun mime(ext: String) = when (ext) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        else -> "text/markdown"
    }
    private fun escape(s: String): String {
        require(s.none { it.code < 32 && it !in "\n\r\t" }) { "正文含无效控制字符" }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
    suspend fun write(file: File, ext: String, body: String, rows: List<List<String>>, slides: List<String>, options: JsonObject = JsonObject(emptyMap()), images: List<ByteArray> = emptyList()): String {
        require(body.length + rows.sumOf { r -> r.sumOf { it.length } } + slides.sumOf { it.length } <= 60000) { "生成内容过长，请拆分" }
        currentCoroutineContext().ensureActive()
        require(images.size <= 5 && images.sumOf { it.size.toLong() } <= 16L * 1024 * 1024)
        require(ext == "docx" || (images.isEmpty() && options["equations"] == null)) { "图片与公式结构目前仅支持 Word 输出" }
        require(ext == "xlsx" || options["sheetName"] == null)
        when (ext) {
            "md" -> { require(body.isNotBlank()); file.writeText(body) }
            "pdf" -> pdf(file, body, rows)
            "docx", "xlsx", "pptx" -> office(file, ext, body, rows, slides, options, images)
            else -> error("此文件输出格式尚未实现")
        }
        return when (ext) {
            "md" -> "已生成并校验 UTF-8 Markdown；外链图片未打包"
            "pdf" -> "已生成分页 PDF；中文使用 Android 字体，公式按原文显示，非数学排版"
            "docx" -> "已生成可编辑段落、带边框表格、${images.size}张内嵌图片与显式 OMML 公式；正文中的 LaTeX 仍为文字，复杂排版需核对"
            "xlsx" -> "已生成命名工作表；JSON 数字/布尔保留类型，字符串原样保留；自动换行与横向打印适配，不执行公式、图表或宏"
            else -> "已生成可编辑逐页文字；长内容限制，未生成图片、备注或动画"
        }
    }
    private suspend fun pdf(file: File, body: String, rows: List<List<String>>) {
        require(body.isNotBlank() || rows.isNotEmpty())
        val doc = PdfDocument()
        try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = android.graphics.Color.BLACK }
            val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, 499).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
            var line = 0; var number = 1
            while (body.isNotBlank() && line < layout.lineCount) {
                currentCoroutineContext().ensureActive()
                require(number <= 100) { "PDF 超过 100 页" }
                val start = layout.getLineTop(line); var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - start <= 730) end++
                check(end > line)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number++).create())
                page.canvas.save(); page.canvas.translate(48f, 48f - start)
                page.canvas.clipRect(0f, start.toFloat(), 499f, layout.getLineTop(end).toFloat())
                layout.draw(page.canvas); page.canvas.restore(); doc.finishPage(page); line = end
            }
            if (rows.isNotEmpty()) {
                val columns = rows.maxOf { it.size }; require(columns in 1..10 && rows.size <= 1000)
                val width = 499f / columns
                val border = Paint().apply { color = android.graphics.Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
                fun layouts(row: List<String>) = (0 until columns).map { col ->
                    val value = row.getOrElse(col) { "" }
                    StaticLayout.Builder.obtain(value, 0, value.length, paint, width.toInt() - 12).setIncludePad(true).build()
                }
                val header = layouts(rows.first()); val headerHeight = header.maxOf { it.height } + 12
                require(headerHeight <= 180) { "PDF 表头过高，请缩短" }
                var page: PdfDocument.Page? = null; var y = 48f
                fun drawCells(cells: List<StaticLayout>, offset: Int, height: Float) {
                    val canvas = page!!.canvas
                    cells.forEachIndexed { col, cell ->
                        val x = 48f + col * width
                        canvas.drawRect(x, y, x + width, y + height, border)
                        canvas.withClip(x + 6, y + 6, x + width - 6, y + height - 6) {
                            translate(x + 6, y + 6 - offset)
                            cell.draw(this)
                        }
                    }; y += height
                }
                fun nextPage() {
                    page?.let(doc::finishPage); require(number <= 100)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number++).create()); y = 48f
                    drawCells(header, 0, headerHeight.toFloat())
                }
                nextPage()
                for (row in rows.drop(1)) {
                    currentCoroutineContext().ensureActive()
                    val cells = layouts(row); val height = cells.maxOf { it.height }; var offset = 0
                    while (offset < height) {
                        if (794 - y < 40) nextPage()
                        var end = offset
                        cells.forEach { cell -> for (i in 0 until cell.lineCount) {
                            val bottom = cell.getLineBottom(i)
                            if (bottom > offset && bottom - offset + 12 <= 794 - y) end = maxOf(end, bottom)
                        } }
                        // All cells share font/line spacing, so slices end on whole lines.
                        if (end == offset) { nextPage(); continue }
                        drawCells(cells, offset, (end - offset + 12).toFloat()); offset = end
                        if (offset < height) nextPage()
                    }
                }
                page?.let(doc::finishPage)
            }
            file.outputStream().use(doc::writeTo)
        } finally { doc.close() }
        val parsed = com.artifex.mupdf.fitz.PDFDocument.openDocument(file.absolutePath)
        try { check(parsed.countPages() > 0) } finally { parsed.destroy() }
    }
    private suspend fun office(file: File, ext: String, body: String, rows: List<List<String>>, slides: List<String>, options: JsonObject, images: List<ByteArray>) {
        val parts = linkedMapOf<String, String>()
        val binary = linkedMapOf<String, ByteArray>()
        val relNs = "http://schemas.openxmlformats.org/package/2006/relationships"
        val officeRel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        fun rel(id: String, type: String, target: String) = "<Relationship Id=\"$id\" Type=\"$officeRel/$type\" Target=\"$target\"/>"
        val main: String; val overrides = StringBuilder()
        fun override(path: String, type: String) { overrides.append("<Override PartName=\"/$path\" ContentType=\"application/vnd.openxmlformats-officedocument.$type+xml\"/>") }
        when (ext) {
            "docx" -> {
                require(body.isNotBlank() || rows.isNotEmpty() || images.isNotEmpty() || options["equations"] != null); main = "word/document.xml"
                fun paragraph(s: String) = "<w:p><w:r><w:t xml:space=\"preserve\">${escape(s)}</w:t></w:r></w:p>"
                fun run(s: String) = "<m:r><m:t>${escape(s)}</m:t></m:r>"
                val equations = options["equations"]?.jsonArray.orEmpty().also { require(it.size <= 100) }.joinToString("") { element ->
                    val eq = element.jsonObject; require(eq.values.sumOf { it.jsonPrimitive.content.length } <= 2000)
                    fun value(key: String) = eq[key]?.jsonPrimitive?.content ?: error("公式缺少 $key")
                    val math = when(value("type")) {
                        "fraction" -> "<m:f><m:num>${run(value("numerator"))}</m:num><m:den>${run(value("denominator"))}</m:den></m:f>"
                        "superscript" -> "<m:sSup><m:e>${run(value("base"))}</m:e><m:sup>${run(value("exponent"))}</m:sup></m:sSup>"
                        "text" -> run(value("text"))
                        else -> error("支持 fraction/superscript/text 公式")
                    }
                    "<w:p><m:oMath>$math</m:oMath></w:p>"
                }
                val pictureXml = images.mapIndexed { i, bytes ->
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 32000000)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法解码 Word 图片")
                    val output = java.io.ByteArrayOutputStream()
                    try { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) } finally { bitmap.recycle() }
                    binary["word/media/image${i + 1}.png"] = output.toByteArray()
                    val width = minOf(5486400L, bounds.outWidth * 9525L); val height = minOf(7315200L, width * bounds.outHeight / bounds.outWidth)
                    val actualWidth = height * bounds.outWidth / bounds.outHeight
                    "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"$actualWidth\" cy=\"$height\"/><wp:docPr id=\"${i+1}\" name=\"Image ${i+1}\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"${i+1}\" name=\"Image ${i+1}\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"img${i+1}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$actualWidth\" cy=\"$height\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
                }.joinToString("")
                parts["word/_rels/document.xml.rels"] = "<Relationships xmlns=\"$relNs\">" + images.indices.joinToString("") { rel("img${it+1}", "image", "media/image${it+1}.png") } + "</Relationships>"
                val borders = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\"/>" }
                val columns = rows.maxOfOrNull { it.size } ?: 0
                require(rows.isEmpty() || (rows.size <= 1000 && columns in 1..20))
                val cellWidth = if (columns == 0) 0 else 9026 / columns
                val grid = "<w:tblGrid>" + (0 until columns).joinToString("") { "<w:gridCol w:w=\"$cellWidth\"/>" } + "</w:tblGrid>"
                parts[main] = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" xmlns:r=\"$officeRel\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>" + body.lines().joinToString("") { paragraph(it) } + equations + pictureXml +
                    (if (rows.isEmpty()) "" else "<w:tbl><w:tblPr><w:tblW w:w=\"9026\" w:type=\"dxa\"/><w:tblBorders>$borders</w:tblBorders></w:tblPr>$grid" + rows.mapIndexed { index, row -> "<w:tr>${if(index == 0) "<w:trPr><w:tblHeader/></w:trPr>" else ""}" + (0 until columns).joinToString("") { col -> "<w:tc><w:tcPr><w:tcW w:w=\"$cellWidth\" w:type=\"dxa\"/></w:tcPr>${paragraph(row.getOrElse(col) { "" })}</w:tc>" } + "</w:tr>" }.joinToString("") + "</w:tbl>") +
                    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>"
                override(main, "wordprocessingml.document.main")
            }
            "xlsx" -> {
                require(rows.isNotEmpty() && rows.size <= 1000 && rows.all { it.size in 1..100 }) { "Excel 需要 1–1000 行、每行 1–100 列" }
                main = "xl/workbook.xml"
                val sheetName = options["sheetName"]?.jsonPrimitive?.content ?: "Sheet1"
                require(sheetName.length in 1..31 && sheetName.none { it in "[]:*?/\\" } && !sheetName.startsWith("'") && !sheetName.endsWith("'")) { "无效工作表名称" }
                parts[main] = "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$officeRel\"><sheets><sheet name=\"${escape(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
                parts["xl/_rels/workbook.xml.rels"] = "<Relationships xmlns=\"$relNs\">${rel("rId1", "worksheet", "worksheets/sheet1.xml")}${rel("rId2", "styles", "styles.xml")}</Relationships>"
                parts["xl/styles.xml"] = "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf></cellXfs></styleSheet>"
                // Style zero applies to every cell, including typed numbers and empty cells.
                val cellBorder = "<border>" + listOf("left", "right", "top", "bottom").joinToString("") { "<$it style=\"thin\"><color auto=\"1\"/></$it>" } + "<diagonal/></border>"
                parts["xl/styles.xml"] = parts.getValue("xl/styles.xml").replace("<border/>", cellBorder)
                    .replace("</styleSheet>", "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
                override("xl/styles.xml", "spreadsheetml.styles")
                fun column(n: Int): String = if (n < 26) ('A' + n).toString() else column(n / 26 - 1) + ('A' + n % 26)
                parts["xl/worksheets/sheet1.xml"] = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"${rows.maxOf { it.size }}\" width=\"24\" customWidth=\"1\"/></cols><sheetData>" + rows.mapIndexed { r, row -> "<row r=\"${r + 1}\">" + row.mapIndexed { c, v ->
                    val primitive = options["rows"]?.jsonArray?.get(r)?.jsonArray?.get(c)?.jsonPrimitive
                    val address = "${column(c)}${r + 1}"
                    when {
                        primitive != null && !primitive.isString && primitive.booleanOrNull != null -> "<c r=\"$address\" t=\"b\"><v>${if (primitive.boolean) 1 else 0}</v></c>"
                        primitive != null && !primitive.isString && primitive != JsonNull -> {
                            require(primitive.content.toBigDecimalOrNull() != null && primitive.doubleOrNull?.isFinite() == true) { "Excel 数值无效" }
                            require(primitive.content.toBigDecimal().precision() <= 15) { "Excel 超过15位精度的值请用字符串" }
                            "<c r=\"$address\"><v>${escape(v)}</v></c>"
                        }
                        primitive == JsonNull -> "<c r=\"$address\"/>"
                        else -> "<c r=\"$address\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(v)}</t></is></c>"
                    }
                }.joinToString("") + "</row>" }.joinToString("") + "</sheetData><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/><pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>"
                override(main, "spreadsheetml.sheet.main"); override("xl/worksheets/sheet1.xml", "spreadsheetml.worksheet")
            }
            else -> {
                require(slides.size in 1..50 && slides.all { it.length <= 1000 && it.lines().size <= 16 }) { "PPT 需要 1–50 页，每页最多 1000 字符、16 行；请拆分页内容" }
                main = "ppt/presentation.xml"
                val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
                val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
                val group = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                parts[main] = "<p:presentation xmlns:p=\"$p\" xmlns:r=\"$officeRel\"><p:sldIdLst>" + slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 1}\"/>" } + "</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"
                parts["ppt/_rels/presentation.xml.rels"] = "<Relationships xmlns=\"$relNs\">" + slides.indices.joinToString("") { rel("rId${it + 1}", "slide", "slides/slide${it + 1}.xml") } + "</Relationships>"
                slides.forEachIndexed { i, text ->
                    currentCoroutineContext().ensureActive()
                    val path = "ppt/slides/slide${i + 1}.xml"
                    parts[path] = "<p:sld xmlns:p=\"$p\" xmlns:a=\"$a\"><p:cSld><p:spTree>$group<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Text\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"457200\" y=\"457200\"/><a:ext cx=\"11277600\" cy=\"5943600\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr wrap=\"square\"><a:spAutoFit/></a:bodyPr><a:lstStyle/>" + text.lines().joinToString("") { "<a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"2400\"/><a:t>${escape(it)}</a:t></a:r></a:p>" } + "</p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
                    override(path, "presentationml.slide")
                }
                override(main, "presentationml.presentation.main")
            }
        }
        parts["_rels/.rels"] = "<Relationships xmlns=\"$relNs\">${rel("rId1", "officeDocument", main)}</Relationships>"
        parts["[Content_Types].xml"] = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/>$overrides</Types>"
        ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (name, text) ->
            currentCoroutineContext().ensureActive(); zip.putNextEntry(ZipEntry(name)); zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + text).toByteArray()); zip.closeEntry()
        }; binary.forEach { (name, bytes) -> currentCoroutineContext().ensureActive(); zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        ZipFile(file).use { zip -> check(zip.getEntry(main) != null && zip.getEntry("[Content_Types].xml") != null) }
        val parsed = MaterialReader.read(file, ext)
        check(parsed.text.isNotBlank()) { "生成文件内容无法重新读取" }
    }
}
