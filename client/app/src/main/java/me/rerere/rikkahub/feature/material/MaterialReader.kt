package me.rerere.rikkahub.feature.material

import com.artifex.mupdf.fitz.PDFDocument
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

@kotlinx.serialization.Serializable data class MaterialContent(
    val text: String, val coverage: String,
    val totalPages: Int? = null, val extractedPages: Int? = null,
    val pagesWithoutExtractableText: List<Int> = emptyList(),
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
            for (i in 0 until minOf(total, 200)) {
                currentCoroutineContext().ensureActive()
                val page = doc.loadPage(i)
                try {
                    val structured = page.toStructuredText()
                    try {
                        // Android PDF font subsets may map ordinary CJK glyphs to Kangxi radicals.
                        // Normalize that range only; global NFKC would destroy superscript math.
                        val body = structured.asText().map { ch -> if (ch.code in 0x2F00..0x2FD5) java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFKC) else ch.toString() }.joinToString("")
                        if (body.isBlank()) withoutText += i + 1
                        text.append("\n[物理页 ${i + 1}]\n").append(if (body.isBlank()) "[无可提取文本；扫描/图像内容未识别]" else body)
                    }
                    finally { structured.destroy() }
                } finally { page.destroy() }
                read++
                if (text.length >= LIMIT) break
            }
            return MaterialContent(text.toString().take(LIMIT), "共 $total 页；尝试提取前 $read 页，最多 $LIMIT 字符；图片、扫描、公式视觉及布局未处理，末页可能截断", total, read, withoutText)
        } finally { doc.destroy() }
    }
    private suspend fun office(file: File, ext: String): MaterialContent = ZipFile(file).use { zip ->
        require(zip.size() <= 2000) { "Office 包条目过多" }
        var expanded = 0L
        fun xml(name: String): String {
            val entry = zip.getEntry(name) ?: error("Office 包缺少 $name")
            require(entry.size in 0..(4L * 1024 * 1024)) { "Office XML 过大或大小未知" }
            expanded += entry.size; require(expanded <= 16L * 1024 * 1024) { "Office 解压内容超限" }
            return zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192); val output = java.io.ByteArrayOutputStream()
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 4 * 1024 * 1024) { "Office XML 实际内容超限" }; output.write(buffer, 0, n) }
                output.toString("UTF-8")
            }.also {
                require(!it.contains("<!DOCTYPE", true) && !it.contains("<!ENTITY", true)) { "不允许外部实体" }
            }
        }
        fun parse(source: String, onTag: (XmlPullParser) -> Unit) {
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(source.reader())
            while (parser.eventType != XmlPullParser.END_DOCUMENT) { onTag(parser); parser.next() }
        }
        val text = StringBuilder()
        when (ext) {
            "docx" -> text.append(MaterialWordContent.text(xml("word/document.xml")))
            "pptx" -> {
                // Follow presentation relationships, rather than assuming slideN order.
                val rels = mutableMapOf<String, String>()
                parse(xml("ppt/_rels/presentation.xml.rels")) { p -> if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val target = p.getAttributeValue(null, "Target") ?: ""
                    if (target.matches(Regex("slides/slide\\d+\\.xml"))) rels[p.getAttributeValue(null, "Id")] = "ppt/$target"
                } }
                val slides = mutableListOf<String>()
                parse(xml("ppt/presentation.xml")) { p -> if (p.eventType == XmlPullParser.START_TAG && p.name == "sldId") {
                    val id = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    slides += rels[id] ?: error("无法解析幻灯片关系")
                } }
                for ((i, slide) in slides.withIndex()) {
                    currentCoroutineContext().ensureActive(); if (text.length >= LIMIT) break
                    text.append("\n[幻灯片 ${i + 1}]\n")
                    parse(xml(slide)) { p -> if (p.eventType == XmlPullParser.START_TAG && p.name == "t") text.append(p.nextText()).append('\n') }
                }
            }
            "xlsx" -> {
                val strings = mutableListOf<String>(); var shared = StringBuilder()
                if (zip.getEntry("xl/sharedStrings.xml") != null) parse(xml("xl/sharedStrings.xml")) { p ->
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "si") shared = StringBuilder()
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "t") shared.append(p.nextText())
                    if (p.eventType == XmlPullParser.END_TAG && p.name == "si") strings += shared.toString()
                }
                val relationships = mutableMapOf<String, String>()
                parse(xml("xl/_rels/workbook.xml.rels")) { p ->
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" && p.getAttributeValue(null, "TargetMode") != "External") {
                        val target = p.getAttributeValue(null, "Target").orEmpty()
                        val path = if (target.startsWith("/xl/")) target.drop(1) else "xl/$target"
                        if (path.matches(Regex("xl/worksheets/[A-Za-z0-9_.-]+\\.xml"))) relationships[p.getAttributeValue(null, "Id")] = path
                    }
                }
                val sheets = mutableListOf<Pair<String, String>>()
                parse(xml("xl/workbook.xml")) { p -> if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    val id = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    sheets += (p.getAttributeValue(null, "name") ?: "未命名") to (relationships[id] ?: error("无法解析工作表关系"))
                } }
                require(sheets.isNotEmpty()) { "没有可读取工作表" }
                for ((name, sheet) in sheets) {
                    currentCoroutineContext().ensureActive(); if (text.length >= LIMIT) break
                    text.append("\n[工作表 $name]\n")
                    var type = ""; var cell = ""
                    parse(xml(sheet)) { p ->
                        if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                            "c" -> { type = p.getAttributeValue(null, "t") ?: "n"; cell = p.getAttributeValue(null, "r") ?: "?"; text.append("$cell [${when(type) { "n" -> "number"; "b" -> "boolean"; "e" -> "error"; "d" -> "date"; else -> "string" }}]: ") }
                            "f" -> text.append("公式=").append(p.nextText()).append("；缓存值=")
                            "v" -> { val value = p.nextText(); text.append(when(type) { "s" -> strings.getOrNull(value.toIntOrNull() ?: -1) ?: error("无效共享字符串索引"); "b" -> if (value == "1") "true" else "false"; else -> value }) }
                            "t" -> text.append(p.nextText())
                        }
                        if (p.eventType == XmlPullParser.END_TAG && p.name == "c") text.append('\n')
                    }
                }
            }
        }
        MaterialContent(text.toString().take(LIMIT), "Office 文本提取，最多 $LIMIT 字符${if (text.length >= LIMIT) "，内容已截断" else ""}；Word 常见 OMML 结构转为括号表达，图片以关系 ID 标记并可单独读取；复杂公式、图表、样式、合并单元格、日期格式和演示备注未保证；DOCX 无页码，Excel 公式未重算")
    }
}
