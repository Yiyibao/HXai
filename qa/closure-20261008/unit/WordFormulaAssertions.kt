package me.rerere.rikkahub.service

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

internal fun assertWordImagesMatch(source: File, output: File) {
    val expected = me.rerere.rikkahub.feature.material.MaterialWordContent.images(source, 0, 5)
    val actual = me.rerere.rikkahub.feature.material.MaterialWordContent.images(output, 0, 5)
    assertTrue("Source fixture must contain images", expected.isNotEmpty())
    assertEquals("All source images retained", expected.size, actual.size)
    expected.indices.forEach { index -> assertArrayEquals("Image $index unchanged", expected[index].second, actual[index].second) }
}

internal fun wordTableCells(file: File): List<List<List<String>>> = ZipFile(file).use { zip ->
    val entry = requireNotNull(zip.getEntry("word/document.xml"))
    require(entry.size in 1..4194304)
    val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
    val namespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream()).documentElement
    fun children(element: Element, name: String) = (0 until element.childNodes.length)
        .map { element.childNodes.item(it) }.filterIsInstance<Element>()
        .filter { it.namespaceURI == namespace && it.localName == name }
    val tables = root.getElementsByTagNameNS(namespace, "tbl")
    (0 until tables.length).map { index -> children(tables.item(index) as Element, "tr").map { row ->
        children(row, "tc").map { cell -> children(cell, "p").joinToString("\n") { paragraph ->
            fun content(element: Element): String {
                if (element.namespaceURI == namespace) when (element.localName) {
                    "t" -> return element.textContent
                    "tab" -> return "\t"
                    "br", "cr" -> return "\n"
                    // Formatting metadata does not contribute visible cell content.
                    "pPr", "rPr" -> return ""
                }
                return (0 until element.childNodes.length).map { element.childNodes.item(it) }
                    .filterIsInstance<Element>().joinToString("") { content(it) }
            }
            content(paragraph)
        } }
    } }
}

internal fun assertWordTablesMatch(source: File, output: File) {
    val expected = wordTableCells(source)
    assertTrue("Source must contain populated tables", expected.any { it.any { row -> row.any(String::isNotEmpty) } })
    assertEquals("All tables, rows and cells retained in order", expected, wordTableCells(output))
}

/** Only the simple explicit OMML operands used by this QA contract, not a CAS. */
internal fun assertWordFormulaStructure(file: File) {
    val xml = ZipFile(file).use { zip ->
        val entry = requireNotNull(zip.getEntry("word/document.xml"))
        require(entry.size in 1..4194304)
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    assertWordFormulaXml(xml)
}

internal fun assertWordFormulaXml(xml: String) {
    require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
    val math = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream()).documentElement
    fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .map { element.childNodes.item(it) }.filterIsInstance<Element>()
    fun operand(element: Element, name: String): String? {
        val part = children(element).singleOrNull { it.namespaceURI == math && it.localName == name } ?: return null
        val descendants = part.getElementsByTagNameNS(math, "*")
        if ((0 until descendants.length).any { descendants.item(it).localName !in setOf("r", "rPr", "t", "sty", "nor", "lit") }) return null
        val texts = part.getElementsByTagNameNS(math, "t")
        var value = (0 until texts.length).joinToString("") { texts.item(it).textContent }.filterNot(Char::isWhitespace)
        while (value.startsWith("(") && value.endsWith(")")) {
            var depth = 0
            val encloses = value.withIndex().all { (index, char) ->
                if (char == '(') depth++ else if (char == ')') depth--
                depth >= 0 && (index == value.lastIndex || depth > 0)
            } && depth == 0
            if (!encloses) break
            value = value.substring(1, value.lastIndex)
        }
        return value
    }
    fun has(type: String, first: String, a: String, second: String, b: String): Boolean {
        val nodes = root.getElementsByTagNameNS(math, type)
        return (0 until nodes.length).any {
            val element = nodes.item(it) as Element
            operand(element, first) == a && operand(element, second) == b
        }
    }
    assertTrue("Editable OMML fraction x+1 over 2", has("f", "num", "x+1", "den", "2"))
    assertTrue("Editable OMML power y to 3", has("sSup", "e", "y", "sup", "3"))
    assertTrue("Basic Word table retained", root.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "tbl").length > 0)
}
