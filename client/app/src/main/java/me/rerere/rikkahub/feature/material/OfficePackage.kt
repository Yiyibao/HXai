package me.rerere.rikkahub.feature.material

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Never extracts ZIP paths. Applies the same preflight to reading, editing and image access. */
object OfficePackage {
    private const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    fun read(file: File): MutableMap<String, ByteArray> = ZipFile(file).use { zip ->
        require(file.length() <= 30L * 1024 * 1024 && zip.size() in 1..2000) { "office_package_limit" }
        val result = linkedMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        var expanded = 0L
        zip.entries().asSequence().forEach { entry ->
            val name = entry.name
            val segments = name.removeSuffix("/").split('/')
            require(names.add(name) && !name.startsWith('/') && segments.all { it.isNotEmpty() && it !in setOf(".", "..") } &&
                name.none { it == '\\' || it == ':' || it.isISOControl() }) { "unsafe_office_zip_path_or_duplicate" }
            val lower = name.lowercase(Locale.ROOT)
            require(!lower.startsWith("_xmlsignatures/") && !lower.endsWith("vbaproject.bin") &&
                !lower.endsWith("vbadata.xml")) { "signed_or_macro_office_not_supported" }
            val isXml = lower.endsWith(".xml") || lower.endsWith(".rels")
            val limit = if (isXml) 4L * 1024 * 1024 else 16L * 1024 * 1024
            require(entry.size in 0..limit) { "office_part_limit" }
            expanded += entry.size
            require(expanded <= 64L * 1024 * 1024) { "office_expanded_limit" }
            val bytes = zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size().toLong() + n <= entry.size) { "office_actual_size_limit" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) { "office_zip_integrity" }
            if (isXml) {
                val root = SafeOfficeXml.parse(bytes)
                if (lower.endsWith(".rels")) {
                    require(root.namespaceURI == PKG && root.localName == "Relationships") { "invalid_office_relationships" }
                    val rels = root.getElementsByTagNameNS(PKG, "Relationship")
                    val ids = mutableSetOf<String>()
                    for (i in 0 until rels.length) {
                        val rel = rels.item(i) as org.w3c.dom.Element
                        require(rel.getAttribute("Id").isNotBlank() && ids.add(rel.getAttribute("Id"))) { "duplicate_relationship" }
                        require(rel.getAttribute("TargetMode") in setOf("", "Internal")) { "external_office_relationship_not_supported" }
                        val target = rel.getAttribute("Target")
                        require(target.isNotBlank() && ':' !in target && '\\' !in target && target.none(Char::isISOControl)) { "invalid_office_relationship_target" }
                        val type = rel.getAttribute("Type").lowercase(Locale.ROOT)
                        require(!type.contains("vbaproject") && !type.contains("digital-signature")) { "signed_or_macro_office_not_supported" }
                    }
                }
                if (name == "[Content_Types].xml") {
                    val nodes = root.getElementsByTagName("*")
                    for (i in 0 until nodes.length) {
                        val type = (nodes.item(i) as org.w3c.dom.Element).getAttribute("ContentType").lowercase(Locale.ROOT)
                        require(listOf("macroenabled", "vbaproject", "digital-signature").none(type::contains)) { "signed_or_macro_office_not_supported" }
                    }
                }
            }
            result[name] = bytes
        }
        result
    }
}
