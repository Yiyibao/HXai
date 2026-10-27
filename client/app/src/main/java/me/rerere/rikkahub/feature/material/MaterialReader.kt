package me.rerere.rikkahub.feature.material

import com.artifex.mupdf.fitz.PDFDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@kotlinx.serialization.Serializable data class MaterialTextLocation(
    val kind: String, val number: Int, val start: Int, val end: Int,
    val part: String? = null, val hidden: Boolean? = null,
)

@kotlinx.serialization.Serializable data class MaterialContent(
    val text: String, val coverage: String,
    val totalPages: Int? = null, val extractedPages: Int? = null,
    val pagesWithoutExtractableText: List<Int> = emptyList(),
    val textLocations: List<MaterialTextLocation> = emptyList(),
)

object MaterialReader {
    const val LIMIT = 1000000
    suspend fun read(file: File, extension: String): MaterialContent {
        require(file.length() <= 30L * 1024 * 1024) { "文件超过读取上限" }
        currentCoroutineContext().ensureActive()
        return when (extension) {
            "doc", "xls", "ppt" -> error("旧版二进制 Office 格式暂不解析，请另存为 docx/xlsx/pptx")
            "docx", "xlsx", "pptx" -> office(file, extension)
            "pdf" -> pdf(file)
            "png", "jpg", "jpeg", "webp" -> error("图片需要已验证的视觉模型；不能作为文本读取")
            "txt", "md", "csv", "json" -> {
                require(file.length() <= 1024 * 1024) { "文本过长，请拆分为小于 1 MiB 的文件" }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.readBytes())).toString()
                MaterialContent(text.take(LIMIT), if (text.length > LIMIT) "仅前 $LIMIT 字符，后续未处理" else "完整 UTF-8 文本；未解释 CSV 类型或公式")
            }
            else -> error("不支持此格式")
        }
    }
    private suspend fun pdf(file: File): MaterialContent {
        val doc = PDFDocument.openDocument(file.absolutePath)
        try {
            require(!doc.needsPassword()) { "PDF 已加密，请先解密" }
            val total = doc.countPages(); val text = StringBuilder(); var read = 0
            val withoutText = mutableListOf<Int>()
            val locations = mutableListOf<MaterialTextLocation>()
            for (i in 0 until minOf(total, 200)) {
                currentCoroutineContext().ensureActive()
                val page = doc.loadPage(i)
                try {
                    val structured = page.toStructuredText()
                    try {
                        // Android PDF font subsets may map ordinary CJK glyphs to Kangxi radicals.
                        // Normalize that range only; global NFKC would destroy superscript math.
                        val body = structured.asText().map { ch -> if (ch.code in 0x2F00..0x2FD5) java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFKC) else ch.toString() }.joinToString("")
                        val blank = body.isBlank()
                        if (blank) withoutText += i + 1
                        val marker = "\n[物理页 ${i + 1}]\n"
                        text.append(marker, 0, minOf(marker.length, LIMIT - text.length))
                        val start = text.length
                        val extracted = if (blank) "[无可提取文本；扫描/图像内容未识别]" else body
                        text.append(extracted, 0, minOf(extracted.length, LIMIT - text.length))
                        if (!blank && start < text.length) locations += MaterialTextLocation("pdf_physical_page", i + 1, start, text.length)
                    }
                    finally { structured.destroy() }
                } finally { page.destroy() }
                read++
                if (text.length >= LIMIT) break
            }
            return MaterialContent(text.toString(), "共 $total 页；尝试提取前 $read 页，最多 $LIMIT 字符；图片、扫描、公式视觉及布局未处理，末页可能截断", total, read, withoutText, locations)
        } finally { doc.destroy() }
    }
    private suspend fun office(file: File, ext: String): MaterialContent {
        val parts = OfficePackage.read(file)
        fun xml(name: String) = SafeOfficeXml.parse(parts.getValue(name))
        fun elements(root: org.w3c.dom.Element, ns: String, name: String) = root.getElementsByTagNameNS(ns, name).let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        }
        fun children(root: org.w3c.dom.Element) = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        val x = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val text = StringBuilder()
        val locations = mutableListOf<MaterialTextLocation>()
        when (ext) {
            "docx" -> text.append(MaterialWordContent.text(xml("word/document.xml")))
            "pptx" -> {
                for ((i, slide) in PresentationSlideOrder.slides(parts).withIndex()) {
                    currentCoroutineContext().ensureActive(); if (text.length >= LIMIT) break
                    val marker = "\n[幻灯片 ${i + 1}；逻辑顺序含隐藏页；${if (slide.hidden) "隐藏" else "可见"}]\n"
                    text.append(marker, 0, minOf(marker.length, LIMIT - text.length))
                    val start = text.length
                    var hasBody = false
                    elements(xml(slide.part), "http://schemas.openxmlformats.org/drawingml/2006/main", "t").forEach {
                        if (text.length < LIMIT) {
                            val body = it.textContent
                            if (body.isNotEmpty()) hasBody = true
                            text.append(body, 0, minOf(body.length, LIMIT - text.length))
                            if (text.length < LIMIT) text.append('\n')
                        }
                    }
                    if (hasBody && start < text.length) locations += MaterialTextLocation("ppt_logical_slide", i + 1, start, text.length, slide.part, slide.hidden)
                }
            }
            "xlsx" -> {
                val strings = if ("xl/sharedStrings.xml" in parts) elements(xml("xl/sharedStrings.xml"), x, "si").map { si ->
                    elements(si, x, "t").joinToString("") { it.textContent }
                } else emptyList()
                val rels = children(xml("xl/_rels/workbook.xml.rels")).associateBy { it.getAttribute("Id") }
                val sheets = elements(xml("xl/workbook.xml"), x, "sheet")
                require(sheets.isNotEmpty()) { "没有可读取工作表" }
                for (sheet in sheets) {
                    currentCoroutineContext().ensureActive(); if (text.length >= LIMIT) break
                    val relation = rels[sheet.getAttributeNS(r, "id")] ?: error("无法解析工作表关系")
                    require(relation.getAttribute("Type") == "$r/worksheet") { "invalid_worksheet_relationship" }
                    val target = relation.getAttribute("Target")
                    val path = if (target.startsWith("/xl/")) target.drop(1) else "xl/$target"
                    require(path.matches(Regex("xl/worksheets/[A-Za-z0-9_.-]+\\.xml"))) { "invalid_worksheet_path" }
                    text.append("\n[工作表 ${sheet.getAttribute("name")}]\n")
                    for (cell in elements(xml(path), x, "c")) {
                        if (text.length >= LIMIT) break
                        val type = cell.getAttribute("t").ifBlank { "n" }
                        text.append("${cell.getAttribute("r")} [${when(type) { "n" -> "number"; "b" -> "boolean"; "e" -> "error"; "d" -> "date"; else -> "string" }}]: ")
                        for (node in children(cell)) when (node.localName) {
                            "f" -> text.append("公式=").append(node.textContent).append("；缓存值=")
                            "v" -> {
                                val value = node.textContent
                                text.append(when(type) {
                                    "s" -> strings.getOrNull(value.toIntOrNull() ?: -1) ?: error("无效共享字符串索引")
                                    "b" -> { require(value in setOf("0", "1")); if (value == "1") "true" else "false" }
                                    else -> value
                                })
                            }
                            "is" -> text.append(elements(node, x, "t").joinToString("") { it.textContent })
                        }
                        text.append('\n')
                    }
                }
            }
        }
        return MaterialContent(text.toString().take(LIMIT), "Office 文本提取，最多 $LIMIT 字符${if (text.length >= LIMIT) "，内容已截断" else ""}；PPT 按 presentation.xml 逻辑顺序，包含隐藏页，不等于可见页或 PDF 页码；Word 常见 OMML 结构转为括号表达，图片以关系 ID 标记并可单独读取；复杂公式、图表、样式、合并单元格、日期格式和演示备注未保证；DOCX 无页码；本次文本提取不执行 Excel 公式重算，实际引擎重算状态以绑定版本的核验记录为准", textLocations = locations)
    }
}
