package me.rerere.rikkahub.feature.material

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

private object FactoryProbe {
    lateinit var delegate: DocumentBuilderFactory
    var builders = 0
}

/** Desktop emulation of Android's absent optional XInclude methods. */
class UnsupportedXIncludeFactory : DocumentBuilderFactory() {
    override fun newDocumentBuilder() = FactoryProbe.delegate.apply {
        isNamespaceAware = this@UnsupportedXIncludeFactory.isNamespaceAware
        isExpandEntityReferences = false
    }.newDocumentBuilder().also { FactoryProbe.builders++ }
    override fun setAttribute(name: String, value: Any) = FactoryProbe.delegate.setAttribute(name, value)
    override fun getAttribute(name: String): Any = FactoryProbe.delegate.getAttribute(name)
    override fun setFeature(name: String, value: Boolean) = FactoryProbe.delegate.setFeature(name, value)
    override fun getFeature(name: String): Boolean = FactoryProbe.delegate.getFeature(name)
    override fun setXIncludeAware(value: Boolean) { throw UnsupportedOperationException() }
    override fun isXIncludeAware(): Boolean { throw UnsupportedOperationException() }
}

class SafeOfficeXmlTest {
    @Test fun absentOptionalFlagAcceptsNormalInputButRejectsEncodedIncludeBeforeBuilderCreation() {
        val key = "javax.xml.parsers.DocumentBuilderFactory"
        val previous = System.getProperty(key)
        FactoryProbe.delegate = DocumentBuilderFactory.newInstance(); FactoryProbe.builders = 0
        try {
            System.setProperty(key, UnsupportedXIncludeFactory::class.java.name)
            assertEquals("ok", SafeOfficeXml.parse("<root>ok</root>".toByteArray()).textContent)
            assertEquals(1, FactoryProbe.builders)
            val xml = """<root xmlns:x="&#104;&#x74;tp://www.w3.org/2001/XIncl&#117;de"><x:include href="synthetic"/></root>"""
            assertTrue(runCatching { SafeOfficeXml.parse(xml.toByteArray()) }.isFailure)
            assertEquals("must reject before an include-aware builder can see the input", 1, FactoryProbe.builders)
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
    @Test fun validUtf8AndBothUtf16ByteOrdersRetainChineseAndEscapedText() {
        listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE).forEach { encoding ->
            val declaration = if (encoding == Charsets.UTF_8) "UTF-8" else "UTF-16"
            val raw = """<?xml version="1.0" encoding="$declaration"?><root>中文 &amp; &lt; 𝑥</root>""".toByteArray(encoding)
            val bytes = when (encoding) {
                Charsets.UTF_16LE -> byteArrayOf(-1, -2) + raw
                Charsets.UTF_16BE -> byteArrayOf(-2, -1) + raw
                else -> raw
            }
            assertEquals("中文 & < 𝑥", SafeOfficeXml.parse(bytes).textContent)
        }
    }
    @Test fun rejectsDtdExternalEntityAndEncodedXincludeBeforeAnySuccess() {
        val unsafe = listOf(
            """<!DOCTYPE root [<!ENTITY x SYSTEM "file:///synthetic-only">]><root>&x;</root>""",
            """<!DOCTYPE root SYSTEM "https://invalid.example/synthetic.dtd"><root/>""",
            """<root xmlns:x="http://www.w3.org/2001/XIncl&#117;de"><x:include href="synthetic"/></root>""",
            """<x:include xmlns:x="http://www.w3.org/2001/XIncl&#117;de" href="synthetic"/>""",
            """<root xmlns:x="&#104;&#x74;tp://www.w3.org/2001/XInclude"><x:include href="synthetic"/></root>""",
        )
        unsafe.forEach { xml -> listOf(Charsets.UTF_8, Charsets.UTF_16).forEach { encoding ->
            assertTrue(runCatching { SafeOfficeXml.parse(xml.toByteArray(encoding)) }.isFailure)
        } }
    }
    @Test fun rejectsMalformedUnsupportedAndMismatchedEncoding() {
        val bytes = listOf(
            byteArrayOf(0xC3.toByte(), 0x28), "<root>".toByteArray(),
            """<?xml version="1.0" encoding="ISO-8859-1"?><root/>""".toByteArray(),
            """<?xml version="1.0" encoding="UTF-16"?><root/>""".toByteArray(),
            byteArrayOf(0, 0, -2, -1, 0, 0, 0, 60),
        )
        bytes.forEach { assertTrue(runCatching { SafeOfficeXml.parse(it) }.isFailure) }
    }
}
