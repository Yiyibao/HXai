package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.io.File
import java.math.BigDecimal
import java.util.zip.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Bounded edits on a copy. Original package parts outside the selected XML stay byte-identical. */
object MaterialEditor {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val X = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private fun editableTextRun(run: Element): Boolean {
        var ancestor = run.parentNode
        while (ancestor is Element) {
            if (ancestor.namespaceURI == W && ancestor.localName == "fldSimple") return false
            if (ancestor.namespaceURI == W && ancestor.localName == "p" && (elements(ancestor, W, "fldChar").isNotEmpty() || elements(ancestor, W, "instrText").isNotEmpty())) return false
            ancestor = ancestor.parentNode
        }
        return true
    }

    /** Edit a single inspected text run, retaining adjacent styles, fields, drawings and equations. */
    fun textRuns(file: File, extension: String, slide: Int, offset: Int, count: Int): JsonObject {
        require(offset >= 0 && count in 1..50)
        val path = if (extension == "docx") "word/document.xml" else { require(extension == "pptx" && slide in 1..200); "ppt/slides/slide$slide.xml" }
        val ns = if (extension == "docx") W else A
        val runs = elements(xml(parts(file).getValue(path)), ns, "t")
        require(offset <= runs.size)
        return buildJsonObject { put("total", runs.size); put("slide", slide); put("runs", buildJsonArray {
            runs.drop(offset).take(count).forEachIndexed { i, run -> add(buildJsonObject { put("index", offset + i + 1); put("text", run.textContent.take(4000)); put("truncated", run.textContent.length > 4000); put("editable", editableTextRun(run)) }) }
        }) }
    }

    fun replaceTextRun(source: File, output: File, extension: String, spec: JsonObject) {
        require(spec.keys == (if (extension == "pptx") setOf("slide", "index", "expectedText", "text") else setOf("index", "expectedText", "text")))
        val slide = if (extension == "pptx") spec.getValue("slide").jsonPrimitive.int.also { require(it in 1..200) } else 0
        val path = if (extension == "docx") "word/document.xml" else { require(extension == "pptx"); "ppt/slides/slide$slide.xml" }
        val ns = if (extension == "docx") W else A
        val replacement = spec.getValue("text").jsonPrimitive.also { require(it.isString) }.content
        require(replacement.length <= 4000 && replacement.none { it.code < 32 }) { "文本运行不能包含控制字符；请编辑其他运行或重新生成段落" }
        val packageParts = parts(source); val root = xml(packageParts.getValue(path))
        val run = elements(root, ns, "t").getOrNull(spec.getValue("index").jsonPrimitive.int - 1) ?: error("文本运行不存在")
        require(editableTextRun(run)) { "域结果应通过Office更新，不能作为普通文字修改" }
        require(spec.getValue("expectedText").jsonPrimitive.isString && run.textContent == spec.getValue("expectedText").jsonPrimitive.content) { "原文已改变，请重新检查" }
        run.textContent = replacement
        run.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
        packageParts[path] = serialize(root); write(packageParts, output)
    }
    private fun children(e: Element) = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()
    private fun xml(bytes: ByteArray): Element {
        require(bytes.size <= 4 * 1024 * 1024)
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "不支持实体声明" }
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream()).documentElement
    }
    private fun serialize(e: Element): ByteArray = java.io.ByteArrayOutputStream().also { output ->
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(e.ownerDocument), StreamResult(output))
    }.toByteArray()
    private fun elements(e: Element, ns: String, tag: String) = e.getElementsByTagNameNS(ns, tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    private fun parts(file: File): MutableMap<String, ByteArray> = ZipFile(file).use { zip ->
        require(zip.size() in 1..2000 && file.length() <= 30L * 1024 * 1024)
        val result = linkedMapOf<String, ByteArray>(); var expanded = 0L
        zip.entries().asSequence().forEach { entry ->
            require(entry.name !in result && !entry.name.startsWith('/') && !entry.name.split('/').any { it == ".." } && !entry.name.contains('\\'))
            require(!entry.name.startsWith("_xmlsignatures/") && !entry.name.endsWith("vbaProject.bin", true)) { "不编辑签名或宏文档" }
            require(entry.size in 0..(16L * 1024 * 1024)); expanded += entry.size; require(expanded <= 64L * 1024 * 1024)
            val bytes = zip.getInputStream(entry).use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= entry.size); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            result[entry.name] = bytes
        }
        result
    }
    private fun write(parts: Map<String, ByteArray>, output: File) {
        ZipOutputStream(output.outputStream()).use { zip -> parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
        ZipFile(output).use { zip -> require(zip.size() == parts.size); parts.forEach { (name, bytes) -> require(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }.contentEquals(bytes)) } }
    }
    private fun paragraphText(p: Element) = elements(p, W, "t").joinToString("") { it.textContent }
    private fun runStyle(run: Element) = children(run).firstOrNull { it.localName == "rPr" }?.let { style -> java.io.StringWriter().also { TransformerFactory.newInstance().newTransformer().transform(DOMSource(style), StreamResult(it)) }.toString() }
    private fun editable(p: Element): Boolean = children(p).all { child -> child.namespaceURI == W && (child.localName == "pPr" || child.localName == "r" && children(child).all { it.namespaceURI == W && it.localName in setOf("rPr", "t") }) } && children(p).filter { it.localName == "r" }.map(::runStyle).distinct().size <= 1

    fun wordParagraphs(file: File, offset: Int, count: Int): JsonObject {
        require(offset >= 0 && count in 1..50)
        val paragraphs = elements(xml(parts(file).getValue("word/document.xml")), W, "p")
        require(offset <= paragraphs.size)
        return buildJsonObject {
            put("total", paragraphs.size); put("offset", offset)
            put("paragraphs", buildJsonArray { paragraphs.drop(offset).take(count).forEachIndexed { i, p -> add(buildJsonObject { put("index", offset + i + 1); put("text", paragraphText(p).take(2000)); put("text_truncated", paragraphText(p).length > 2000); put("editable", editable(p)) }) } })
        }
    }
    fun replaceParagraph(source: File, output: File, spec: JsonObject) {
        require(spec.keys == setOf("index", "expectedText", "text"))
        val index = spec.getValue("index").jsonPrimitive.also { require(!it.isString) }.int
        val expected = spec.getValue("expectedText").jsonPrimitive.also { require(it.isString) }.content
        val text = spec.getValue("text").jsonPrimitive.also { require(it.isString) }.content
        require(text.length <= 4000 && text.none { it == '\n' || it == '\r' || it.code < 32 }) { "首批仅替换一个简单文本段落" }
        val parts = parts(source); val root = xml(parts.getValue("word/document.xml"))
        val p = elements(root, W, "p").getOrNull(index - 1) ?: error("段落序号不存在")
        require(editable(p) && paragraphText(p) == expected) { "原文不匹配或包含公式/图片/域/链接等复杂内容" }
        val runs = children(p).filter { it.localName == "r" }
        val run = runs.firstOrNull() ?: root.ownerDocument.createElementNS(W, "w:r").also { p.appendChild(it) }
        runs.drop(1).forEach { p.removeChild(it) }; children(run).filter { it.localName != "rPr" }.forEach { run.removeChild(it) }
        run.appendChild(root.ownerDocument.createElementNS(W, "w:t").apply { setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve"); textContent = text })
        parts["word/document.xml"] = serialize(root); write(parts, output)
        require(wordParagraphs(output, index - 1, 1).getValue("paragraphs").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content == text.take(2000))
    }
    private fun sheet(parts: Map<String, ByteArray>, name: String): String {
        val workbook = xml(parts.getValue("xl/workbook.xml"))
        val sheet = elements(workbook, X, "sheet").singleOrNull { it.getAttribute("name") == name } ?: error("工作表不存在")
        val id = sheet.getAttributeNS(R, "id")
        val relation = children(xml(parts.getValue("xl/_rels/workbook.xml.rels"))).single { it.getAttribute("Id") == id }
        require(relation.getAttribute("TargetMode") != "External")
        val target = relation.getAttribute("Target").removePrefix("/xl/")
        require(target.matches(Regex("worksheets/[A-Za-z0-9_.-]+\\.xml"))) { "不支持该工作表路径" }
        return "xl/$target"
    }
    private fun cellValue(cell: Element?, parts: Map<String, ByteArray>): JsonPrimitive {
        if (cell == null) return JsonNull
        require(elements(cell, X, "f").isEmpty()) { "不改公式单元格" }
        val raw = children(cell).firstOrNull { it.localName == "v" }?.textContent
        return when (cell.getAttribute("t")) {
            "inlineStr" -> JsonPrimitive(elements(cell, X, "t").joinToString("") { it.textContent })
            "s" -> { val strings = elements(xml(parts.getValue("xl/sharedStrings.xml")), X, "si"); JsonPrimitive(elements(strings[raw!!.toInt()], X, "t").joinToString("") { it.textContent }) }
            "str" -> JsonPrimitive(raw.orEmpty())
            "b" -> { require(raw in setOf("0", "1")); JsonPrimitive(raw == "1") }
            "", "n" -> if (raw.isNullOrBlank()) JsonNull else Json.parseToJsonElement(BigDecimal(raw).toPlainString()).jsonPrimitive
            else -> error("暂不编辑日期/错误类型单元格")
        }
    }
    fun excelCells(file: File, sheetName: String, offset: Int, count: Int): JsonObject {
        require(offset >= 0 && count in 1..50)
        val parts = parts(file); val root = xml(parts.getValue(sheet(parts, sheetName))); val cells = elements(root, X, "c")
        require(offset <= cells.size)
        return buildJsonObject { put("sheet", sheetName); put("total", cells.size); put("cells", buildJsonArray { cells.drop(offset).take(count).forEach { c -> add(buildJsonObject { put("cell", c.getAttribute("r")); if (elements(c, X, "f").isNotEmpty()) { put("editable", false); put("formula", elements(c, X, "f").single().textContent) } else { put("editable", true); put("value", cellValue(c, parts)) } }) } }) }
    }
    fun setCells(source: File, output: File, spec: JsonObject) {
        require(spec.keys == setOf("sheet", "changes"))
        val parts = parts(source)
        val path = sheet(parts, spec.getValue("sheet").jsonPrimitive.content); val root = xml(parts.getValue(path))
        val data = elements(root, X, "sheetData").single()
        val changes = spec.getValue("changes").jsonArray; require(changes.size in 1..200)
        val addresses = mutableSetOf<String>()
        changes.forEach { item ->
            val change = item.jsonObject; require(change.keys == setOf("cell", "expected", "value"))
            val address = change.getValue("cell").jsonPrimitive.content
            require(address.matches(Regex("[A-Z]{1,3}[1-9][0-9]{0,5}")) && addresses.add(address))
            val column = address.takeWhile(Char::isLetter).fold(0) { n, c -> n * 26 + c.code - 64 }; require(column <= 16384)
            val number = address.dropWhile(Char::isLetter).toInt(); require(number <= 100000)
            var cell = elements(root, X, "c").singleOrNull { it.getAttribute("r") == address }
            val expected = change.getValue("expected").jsonPrimitive; val actual = cellValue(cell, parts)
            fun equal(a: JsonPrimitive, b: JsonPrimitive): Boolean = if (!a.isString && !b.isString && a.doubleOrNull != null && b.doubleOrNull != null) BigDecimal(a.content).compareTo(BigDecimal(b.content)) == 0 else a == b
            require(equal(expected, actual)) { "单元格 $address 原值不匹配" }
            val value = change.getValue("value").jsonPrimitive
            require(value.content.length <= 4000 && (value == JsonNull || value.isString || value.booleanOrNull != null || value.doubleOrNull?.let { it.isFinite() && kotlin.math.abs(it) <= 1e12 } == true))
            if (cell == null) {
                val row = children(data).singleOrNull { it.getAttribute("r") == number.toString() } ?: root.ownerDocument.createElementNS(X, "row").apply {
                    setAttribute("r", number.toString()); data.insertBefore(this, children(data).firstOrNull { it.getAttribute("r").toInt() > number })
                }
                cell = root.ownerDocument.createElementNS(X, "c").apply { setAttribute("r", address) }
                fun col(c: Element) = c.getAttribute("r").takeWhile(Char::isLetter).fold(0) { n, ch -> n * 26 + ch.code - 64 }
                row.insertBefore(cell, children(row).firstOrNull { col(it) > column })
            }
            children(cell).forEach { cell.removeChild(it) }; cell.removeAttribute("t")
            if (value != JsonNull) {
                when { value.isString -> {
                    cell.setAttribute("t", "inlineStr")
                    cell.appendChild(root.ownerDocument.createElementNS(X, "is").apply { appendChild(root.ownerDocument.createElementNS(X, "t").apply { setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve"); textContent = value.content }) })
                }; value.booleanOrNull != null -> { cell.setAttribute("t", "b"); cell.appendChild(root.ownerDocument.createElementNS(X, "v").apply { textContent = if (value.boolean) "1" else "0" }) }
                else -> cell.appendChild(root.ownerDocument.createElementNS(X, "v").apply { textContent = value.content }) }
            }
        }
        // Remove stale dimension hints; readers derive the used range from cells.
        elements(root, X, "dimension").forEach { it.parentNode.removeChild(it) }
        parts[path] = serialize(root)
        // Preserve formulas and styles, but never deliver stale cached results as current values.
        var formulas = false
        parts.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.forEach { sheetPath ->
            val worksheet = xml(parts.getValue(sheetPath))
            val formulaCells = elements(worksheet, X, "c").filter { elements(it, X, "f").isNotEmpty() }
            if (formulaCells.isNotEmpty()) {
                formulas = true
                formulaCells.forEach { cell -> children(cell).filter { it.localName == "v" }.forEach { cell.removeChild(it) } }
                parts[sheetPath] = serialize(worksheet)
            }
        }
        if (formulas) {
            val workbook = xml(parts.getValue("xl/workbook.xml"))
            val calc = elements(workbook, X, "calcPr").firstOrNull() ?: workbook.ownerDocument.createElementNS(X, "calcPr").also { calc -> workbook.insertBefore(calc, children(workbook).firstOrNull { it.localName in setOf("oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr", "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst") }) }
            calc.setAttribute("calcMode", "auto"); calc.setAttribute("fullCalcOnLoad", "1"); calc.setAttribute("forceFullCalc", "1")
            parts["xl/workbook.xml"] = serialize(workbook)
        }
        write(parts, output)
    }
    suspend fun pdfPages(sources: List<Pair<File, List<Int>>>, output: File) {
        require(sources.size in 1..20 && sources.sumOf { it.second.size } in 1..200)
        val result = com.artifex.mupdf.fitz.PDFDocument()
        try {
            sources.forEach { (file, pages) ->
                val source = com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath) as? com.artifex.mupdf.fitz.PDFDocument ?: error("文件不是PDF")
                try {
                    require(!source.needsPassword() && source.countPages() in 1..200)
                    pages.forEach { page -> currentCoroutineContext().ensureActive(); require(page in 1..source.countPages()); result.graftPage(-1, source, page - 1) }
                } finally { source.destroy() }
            }
            result.save(output.absolutePath, "garbage=4,compress")
        } finally { result.destroy() }
        val reopened = com.artifex.mupdf.fitz.Document.openDocument(output.absolutePath)
        try { require(reopened.countPages() == sources.sumOf { it.second.size }) } finally { reopened.destroy() }
    }
}
