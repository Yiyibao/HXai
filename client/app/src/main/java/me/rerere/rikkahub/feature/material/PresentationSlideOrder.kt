package me.rerere.rikkahub.feature.material

import org.w3c.dom.Element

/** Logical 1-based order, including hidden slides; not filename order or rendered PDF page. */
object PresentationSlideOrder {
    data class Slide(val part: String, val hidden: Boolean)
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    private fun children(root: Element) = (0 until root.childNodes.length)
        .map { root.childNodes.item(it) }.filterIsInstance<Element>()

    fun resolve(parts: Map<String, ByteArray>, slide: Int): String {
        require(slide in 1..200) { "invalid_slide_number" }
        return slides(parts).getOrNull(slide - 1)?.part ?: error("slide_not_found")
    }

    fun slides(parts: Map<String, ByteArray>): List<Slide> {
        val root = SafeOfficeXml.parse(parts.getValue("ppt/presentation.xml"))
        require(root.namespaceURI == P && root.localName == "presentation")
        val list = children(root).single { it.namespaceURI == P && it.localName == "sldIdLst" }
        val slides = children(list).filter { it.namespaceURI == P && it.localName == "sldId" }
        require(slides.size in 1..200) { "slide_not_found" }
        require(slides.map { it.getAttribute("id") }.distinct().size == slides.size) { "ambiguous_slide_id" }
        val ids = slides.map { it.getAttributeNS(R, "id") }
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "ambiguous_slide_id" }
        val relsRoot = SafeOfficeXml.parse(parts.getValue("ppt/_rels/presentation.xml.rels"))
        require(relsRoot.namespaceURI == PKG && relsRoot.localName == "Relationships")
        val rels = children(relsRoot).filter { it.namespaceURI == PKG && it.localName == "Relationship" }
        require(rels.map { it.getAttribute("Id") }.distinct().size == rels.size) { "duplicate_relationship" }
        val ordered = ids.map { id ->
            val relation = rels.single { it.getAttribute("Id") == id }
            require(relation.getAttribute("Type") == "$R/slide" && relation.getAttribute("TargetMode") in setOf("", "Internal")) { "external_or_invalid_slide" }
            val target = relation.getAttribute("Target")
            val path = if (target.startsWith("/ppt/")) target.removePrefix("/") else "ppt/$target"
            require(path.matches(Regex("ppt/slides/[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.xml"))) { "invalid_slide_path" }
            require(path in parts) { "missing_slide_part" }
            val content = SafeOfficeXml.parse(parts.getValue(path))
            require(content.namespaceURI == P && content.localName == "sld") { "invalid_slide_part" }
            require(content.getAttribute("show") in setOf("", "0", "1", "true", "false")) { "invalid_slide_visibility" }
            Slide(path, content.getAttribute("show") in setOf("0", "false"))
        }
        require(ordered.map { it.part }.distinct().size == ordered.size) { "duplicate_slide_part" }
        return ordered
    }
}
