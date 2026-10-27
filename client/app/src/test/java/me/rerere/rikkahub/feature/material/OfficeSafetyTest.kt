package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OfficeSafetyTest {
    private val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val pkg = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun zip(file: File, parts: Map<String, ByteArray>) = ZipOutputStream(file.outputStream()).use { out ->
        parts.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
    }
    private fun pptParts(): Map<String, ByteArray> = mapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slides/slide9.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/></Types>""",
        "_rels/.rels" to """<Relationships xmlns="$pkg"><Relationship Id="doc" Type="$r/officeDocument" Target="ppt/presentation.xml"/></Relationships>""",
        "ppt/presentation.xml" to """<p:presentation xmlns:p="$p" xmlns:r="$r"><p:sldIdLst><p:sldId id="256" r:id="nine"/><p:sldId id="257" r:id="one"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""",
        "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="$pkg"><Relationship Id="one" Type="$r/slide" Target="slides/slide1.xml"/><Relationship Id="nine" Type="$r/slide" Target="slides/slide9.xml"/></Relationships>""",
        "ppt/slides/slide9.xml" to slide("Logical first", true),
        "ppt/slides/slide1.xml" to slide("Logical second", false),
    ).mapValues { it.value.toByteArray() }
    private fun slide(title: String, hidden: Boolean) = """<p:sld xmlns:p="$p" xmlns:a="$a" show="${if (hidden) 0 else 1}"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/><p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr b="1"/><a:t>$title</a:t></a:r><a:r><a:t> Adjacent</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    private fun parts(file: File) = ZipFile(file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } } }
    private fun spec(slide: Int = 1, expected: String = "Logical first") = buildJsonObject {
        put("slide", slide); put("index", 1); put("expectedText", expected); put("text", "Changed <&> title")
    }
    @Test fun logicalSlideEditPreservesSourceAndOtherParts() {
        val dir = Files.createTempDirectory("office-order").toFile()
        try {
            val source = File(dir, "source.pptx"); val output = File(dir, "output.pptx")
            val before = pptParts(); zip(source, before); val original = source.readBytes()
            val run = MaterialEditor.textRuns(source, "pptx", 1, 0, 10)["runs"]!!.jsonArray.first().jsonObject
            assertEquals("Logical first", run["text"]!!.jsonPrimitive.content)
            MaterialEditor.replaceTextRun(source, output, "pptx", spec())
            assertArrayEquals(original, source.readBytes())
            val after = parts(output)
            assertEquals(before.keys, after.keys)
            before.filterKeys { it != "ppt/slides/slide9.xml" }.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after[name]) }
            assertEquals("Changed <&> title", MaterialEditor.textRuns(output, "pptx", 1, 0, 10)["runs"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
            assertTrue(after.getValue("ppt/slides/slide9.xml").toString(Charsets.UTF_8).contains("Adjacent"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun utf16DtdIsRejectedAtRealEditorEntry() {
        val dir = Files.createTempDirectory("office-entity").toFile()
        try {
            val source = File(dir, "source.docx")
            // Internal synthetic entity: no private file or network access in this negative control.
            val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE w:document [<!ENTITY e "SYNTHETIC_SENTINEL">]><w:document xmlns:w="$w"><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:document>"""
            zip(source, mapOf("word/document.xml" to xml.toByteArray(Charsets.UTF_16)))
            assertTrue("UTF16 DTD must be rejected", runCatching { MaterialEditor.wordParagraphs(source, 0, 10) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun readerAndEditorAgreeOnHiddenLogicalSlidesAndRejectStaleOrSourceOutput() = runBlocking {
        val dir = Files.createTempDirectory("office-reader").toFile()
        try {
            val file = File(dir, "source.pptx"); val output = File(dir, "out.pptx"); zip(file, pptParts())
            val original = file.readBytes()
            val text = MaterialReader.read(file, "pptx").text
            assertTrue(text.indexOf("Logical first") < text.indexOf("Logical second"))
            assertTrue(text.contains("隐藏"))
            assertTrue(MaterialEditor.textRuns(file, "pptx", 1, 0, 1)["hidden"]!!.jsonPrimitive.boolean)
            assertTrue(runCatching { MaterialEditor.replaceTextRun(file, output, "pptx", spec(expected="stale")) }.isFailure)
            assertFalse(output.exists())
            assertTrue(runCatching { MaterialEditor.replaceTextRun(file, file, "pptx", spec()) }.isFailure)
            val alias = File(dir, "hardlink.pptx")
            Files.createLink(alias.toPath(), file.toPath())
            assertTrue(runCatching { MaterialEditor.replaceTextRun(file, alias, "pptx", spec()) }.isFailure)
            assertTrue(runCatching { MaterialEditor.textRuns(file, "pptx", 3, 0, 1) }.isFailure)
            assertArrayEquals(original, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun validUtf16WordIsReadableAndEditableWithNonTargetPartsPreserved() = runBlocking {
        val dir = Files.createTempDirectory("office-utf16").toFile()
        try {
            val source = File(dir, "source.docx"); val output = File(dir, "output.docx")
            val xml = """<?xml version="1.0" encoding="UTF-16"?><w:document xmlns:w="$w"><w:body><w:p><w:r><w:t>中文原文</w:t></w:r><w:r><w:t>相邻</w:t></w:r></w:p></w:body></w:document>"""
            val originalParts = mapOf("word/document.xml" to xml.toByteArray(Charsets.UTF_16), "word/styles.xml" to "<styles/>".toByteArray())
            zip(source, originalParts); val original = source.readBytes()
            assertTrue(MaterialReader.read(source, "docx").text.contains("中文原文"))
            MaterialEditor.replaceTextRun(source, output, "docx", buildJsonObject { put("index", 1); put("expectedText", "中文原文"); put("text", "新文字<&>") })
            assertTrue(MaterialReader.read(output, "docx").text.contains("新文字<&>相邻"))
            assertArrayEquals(originalParts["word/styles.xml"], parts(output)["word/styles.xml"])
            assertArrayEquals(original, source.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsBrokenAmbiguousExternalOrMissingSlideRelations() {
        val original = pptParts()
        val rels = original.getValue("ppt/_rels/presentation.xml.rels").toString(Charsets.UTF_8)
        val variants = listOf(
            original + ("ppt/_rels/presentation.xml.rels" to rels.replace("Id=\"one\"", "Id=\"nine\"").toByteArray()),
            original + ("ppt/_rels/presentation.xml.rels" to rels.replace("slides/slide9.xml", "../escape.xml").toByteArray()),
            original + ("ppt/_rels/presentation.xml.rels" to rels.replace("slides/slide9.xml", "slides/missing.xml").toByteArray()),
            original + ("ppt/_rels/presentation.xml.rels" to rels.replace("slides/slide9.xml\"", "slides/slide9.xml\" TargetMode=\"External\"").toByteArray()),
            original + ("ppt/_rels/presentation.xml.rels" to rels.replace("slides/slide9.xml", "slides/slide1.xml").toByteArray()),
            original + ("ppt/presentation.xml" to original.getValue("ppt/presentation.xml").toString(Charsets.UTF_8).replace("r:id=\"one\"", "r:id=\"nine\"").toByteArray()),
        )
        variants.forEach { parts -> assertTrue(runCatching { PresentationSlideOrder.resolve(parts, 1) }.isFailure) }
    }
}
