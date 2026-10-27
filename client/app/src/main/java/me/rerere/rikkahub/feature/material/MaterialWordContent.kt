package me.rerere.rikkahub.feature.material

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File

/** Structural OMML reading; parentheses keep fraction/power boundaries explicit. */
object MaterialWordContent {
    private const val MATH = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private fun document(xml: String): Element = SafeOfficeXml.parse(xml.toByteArray(Charsets.UTF_8))
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
    fun text(xml: String): String = text(document(xml))
    fun text(root: Element): String {
        fun render(n: Element): String = when {
            n.namespaceURI == MATH && n.localName == "oMath" -> "[公式 ${math(n)}]"
            n.localName == "t" -> n.textContent
            n.localName == "blip" -> "[图片关系 ${n.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")}]"
            n.localName == "tab" -> "\t"
            n.localName == "br" -> "\n"
            n.localName == "tr" -> children(n).filter { it.localName == "tc" }.joinToString("\t") { render(it).trim().replace("\n", " / ") } + "\n"
            else -> children(n).joinToString("") { render(it) } + if (n.localName == "p") "\n" else ""
        }
        return render(root)
    }
    fun images(file: File, start: Int, count: Int): List<Pair<String, ByteArray>> {
        require(start >= 0 && count in 1..5)
        val parts = OfficePackage.read(file)
        val body = SafeOfficeXml.parse(parts.getValue("word/document.xml"))
        val references = body.getElementsByTagNameNS("http://schemas.openxmlformats.org/drawingml/2006/main", "blip")
        val ids = (0 until references.length).map { (references.item(it) as Element).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed") }.distinct()
        require(start < ids.size) { "没有此序号的内嵌图片" }
        val rels = SafeOfficeXml.parse(parts.getValue("word/_rels/document.xml.rels"))
        val relationships = children(rels).associateBy { it.getAttribute("Id") }
        return ids.drop(start).take(count).map { id ->
            val rel = relationships[id] ?: error("缺少图片关系")
            require(rel.getAttribute("TargetMode") != "External") { "不读取外链图片" }
            val target = rel.getAttribute("Target").removePrefix("/word/")
            require(target.matches(Regex("media/[A-Za-z0-9_.-]+\\.(png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE))) { "仅支持包内 PNG/JPEG/WebP 图片" }
            val bytes = parts["word/$target"] ?: error("图片部件缺失")
            require(bytes.size in 1..8388608)
            id to bytes
        }
    }
}
