package me.rerere.rikkahub.feature.material

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Bound and validate the exact Reader passed to JAXP, not a differently decoded preview. */
object SafeOfficeXml {
    fun parse(bytes: ByteArray): Element {
        require(bytes.size <= 4 * 1024 * 1024) { "office_xml_limit" }
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255)
        val charset = when {
            b(0) == 0 && b(1) == 0 && b(2) == 254 && b(3) == 255 -> error("unsupported_xml_encoding")
            b(0) == 255 && b(1) == 254 && b(2) == 0 && b(3) == 0 -> error("unsupported_xml_encoding")
            b(0) == 255 && b(1) == 254 -> Charsets.UTF_16LE
            b(0) == 254 && b(1) == 255 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            .toString().removePrefix("\uFEFF")
        require('\u0000' !in text) { "unsupported_xml_encoding" }
        // Mandatory, fail-closed lexical gate on exactly the same character stream.
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "unsafe_office_xml" }
        val declared = Regex("""^\s*<\?xml\s+[^?]*encoding\s*=\s*['\"]([^'\"]+)['\"]""",
            RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.uppercase()
        require(declared == null || when (charset) {
            Charsets.UTF_8 -> declared == "UTF-8"
            Charsets.UTF_16LE -> declared in setOf("UTF-16", "UTF-16LE")
            Charsets.UTF_16BE -> declared in setOf("UTF-16", "UTF-16BE")
            else -> false
        }) { "unsupported_or_mismatched_xml_encoding" }
        // XML comments containing these declarations are deliberately rejected too.
        // XInclude is not part of the supported Office edit contract.
        // Namespace values can contain numeric character references. Reject every alias
        // BEFORE constructing the DOM, even on Android where XInclude flags are absent.
        val namespaceProjection = Regex("""&#(x[0-9a-fA-F]+|[0-9]+);""").replace(text) { match ->
            val value = match.groupValues[1]
            val code = if (value.startsWith('x')) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
            require(code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) { "invalid_xml_character_reference" }
            String(Character.toChars(code))
        }
        require(!namespaceProjection.contains("http://www.w3.org/2001/XInclude", true)) { "xinclude_not_supported" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Additional defenses vary between Android JAXP and desktop JAXP.
            // None replace the mandatory same-stream DTD/entity rejection above.
            runCatching { isXIncludeAware = false }
            // Harmony's JAXP getter throws UnsupportedOperationException for this optional
            // feature. The mandatory namespace gate above already excludes XInclude from
            // the exact validated input; flags remain an additional defense.
            val includeAware = try { isXIncludeAware } catch (_: UnsupportedOperationException) { null }
            require(includeAware != true) { "parser_xinclude_must_be_disabled" }
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            ).forEach { (name, value) -> runCatching { setFeature(name, value) } }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("external_xml_entity_rejected") }
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(e: SAXParseException) { throw SAXException("invalid_office_xml") }
            override fun fatalError(e: SAXParseException) { throw SAXException("invalid_office_xml") }
        })
        val root = builder.parse(InputSource(StringReader(text))).documentElement
        // Namespace character references may evade a raw-text namespace check.
        // XInclude namespace aliases were rejected BEFORE parsing; verify the DOM too.
        val xi = "http://www.w3.org/2001/XInclude"
        require(root.namespaceURI != xi && root.getElementsByTagNameNS(xi, "*").length == 0) { "xinclude_not_supported" }
        return root
    }
}
