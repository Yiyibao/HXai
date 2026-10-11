package me.rerere.rikkahub.feature.material

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Structural OMML reading; parentheses keep fraction/power boundaries explicit. */
object MaterialWordContent {
    private const val MATH = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private fun document(xml: String): Element {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream()).documentElement
    }
    private fun children(n: Node) = (0 until n.childNodes.length).map { n.childNodes.item(it) }.filterIsInstance<Element>()
    private fun math(n: Element): String {
        fun part(name: String) = children(n).firstOrNull { it.localName == name }?.let(::math).orEmpty()
        return when(n.localName) {
            "t" -> n.textContent
            "f" -> "(${part("num")})/(${part("den")})"
            "sSup" -> "(${part("e")})^(${part("sup")})"
            "sSub" -> "(${part("e")})_(${part("sub")})"
            "sSubSup" -> "(${part("e")})_(${part("sub")})^(${part("sup")})"
            "rad" -> "root[${part("deg").ifBlank { "2" }}](${part("e")})"
            else -> children(n).joinToString("") { math(it) }
        }
    }
    fun text(xml: String): String {
        fun render(n: Element): String = when {
            n.namespaceURI == MATH && n.localName == "oMath" -> "[公式 ${math(n)}]"
            n.localName == "t" -> n.textContent
            n.localName == "blip" -> "[图片关系 ${n.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")}]"
            n.localName == "tab" -> "\t"
            n.localName == "br" -> "\n"
            n.localName == "tr" -> children(n).filter { it.localName == "tc" }.joinToString("\t") { render(it).trim().replace("\n", " / ") } + "\n"
            else -> children(n).joinToString("") { render(it) } + if (n.localName == "p") "\n" else ""
        }
        return render(document(xml))
    }
    fun images(file: File, start: Int, count: Int): List<Pair<String, ByteArray>> = ZipFile(file).use { zip ->
        require(start >= 0 && count in 1..5)
        require(zip.size() <= 2000)
        fun xml(name: String): String {
            val entry = zip.getEntry(name) ?: error("Word 缺少 $name")
            require(entry.size in 0..4194304)
            return zip.getInputStream(entry).use { it.readBytesBounded(4194304).toString(Charsets.UTF_8) }
        }
        val body = document(xml("word/document.xml"))
        val references = body.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main", "blip")
        val ids = (0 until references.length).map { (references.item(it) as Element).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed") }.distinct()
        require(start < ids.size) { "没有此序号的内嵌图片" }
        val rels = document(xml("word/_rels/document.xml.rels"))
        val relationships = children(rels).associateBy { it.getAttribute("Id") }
        ids.drop(start).take(count).map { id ->
            val rel = relationships[id] ?: error("缺少图片关系")
            require(rel.getAttribute("TargetMode") != "External") { "不读取外链图片" }
            val target = rel.getAttribute("Target").removePrefix("/word/")
            require(target.matches(Regex("media/[A-Za-z0-9_.-]+\\.(png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE))) { "仅支持包内 PNG/JPEG/WebP 图片" }
            val entry = zip.getEntry("word/$target") ?: error("图片部件缺失")
            require(entry.size in 1..8388608)
            id to zip.getInputStream(entry).use { it.readBytesBounded(8388608) }
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; require(output.size() + n <= limit); output.write(buffer, 0, n) }
        return output.toByteArray()
    }
}
