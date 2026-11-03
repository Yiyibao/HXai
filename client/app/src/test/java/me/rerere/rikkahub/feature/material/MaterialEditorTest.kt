package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.*

class MaterialEditorTest {
    @Test fun mixedStyleWordAndPptRunEditsPreserveImagesEquationsAndAdjacentRuns() {
        val dir = java.nio.file.Files.createTempDirectory("p0-runs").toFile()
        try {
            val word = File(dir, "word.docx"); val ppt = File(dir, "slide.pptx"); val output = File(dir, "out.zip")
            val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            zip(word, mapOf("word/document.xml" to """<w:document xmlns:w="$w"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:r><w:t> plain</w:t><w:drawing/></w:r><m:oMath xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><m:r><m:t>x+y</m:t></m:r></m:oMath></w:p></w:body></w:document>""", "word/media/img.png" to "image-bytes"))
            MaterialEditor.replaceTextRun(word, output, "docx", Json.parseToJsonElement("""{"index":1,"expectedText":"Bold","text":"New bold"}""").jsonObject)
            val xml = bytes(output, "word/document.xml").toString(Charsets.UTF_8)
            assertTrue(xml.contains("<w:b") && xml.contains(" plain") && xml.contains("x+y") && xml.contains("drawing"))
            assertArrayEquals(bytes(word, "word/media/img.png"), bytes(output, "word/media/img.png"))
            zip(ppt, mapOf("ppt/slides/slide1.xml" to """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:rPr b="1"/><a:t>Old title</a:t></a:r></a:p></p:txBody></p:sp><p:pic/></p:spTree></p:cSld></p:sld>""", "ppt/media/image.png" to "picture", "ppt/slides/slide2.xml" to "unchanged"))
            MaterialEditor.replaceTextRun(ppt, output, "pptx", Json.parseToJsonElement("""{"slide":1,"index":1,"expectedText":"Old title","text":"New title"}""").jsonObject)
            assertTrue(bytes(output, "ppt/slides/slide1.xml").toString(Charsets.UTF_8).contains("New title"))
            assertArrayEquals(bytes(ppt, "ppt/slides/slide2.xml"), bytes(output, "ppt/slides/slide2.xml"))
            assertArrayEquals(bytes(ppt, "ppt/media/image.png"), bytes(output, "ppt/media/image.png"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun constantEditPreservesFormulaAndInvalidatesCachedValue() {
        val dir = java.nio.file.Files.createTempDirectory("p0-formulas").toFile()
        try {
            val source = File(dir, "source.xlsx"); val output = File(dir, "out.xlsx")
            zip(source, mapOf("xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" r:id="s1"/></sheets></workbook>""", "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="s1" Target="worksheets/sheet1.xml"/></Relationships>""", "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" s="2"><v>2</v></c><c r="B1"><f>A1*3</f><v>6</v></c></row></sheetData></worksheet>"""))
            MaterialEditor.setCells(source, output, Json.parseToJsonElement("""{"sheet":"Data","changes":[{"cell":"A1","expected":2,"value":4}]}""").jsonObject)
            val xml = bytes(output, "xl/worksheets/sheet1.xml").toString(Charsets.UTF_8)
            assertTrue(xml.contains("A1*3") && xml.contains("s=\"2\"") && xml.contains("<v>4</v>")); assertFalse(xml.contains("<v>6</v>"))
            assertTrue(bytes(output, "xl/workbook.xml").toString(Charsets.UTF_8).contains("fullCalcOnLoad=\"1\""))
        } finally { dir.deleteRecursively() }
    }
    private fun zip(file: File, parts: Map<String, String>) = ZipOutputStream(file.outputStream()).use { output -> parts.forEach { (name, text) -> output.putNextEntry(ZipEntry(name)); output.write(text.toByteArray()); output.closeEntry() } }
    private fun bytes(file: File, name: String) = ZipFile(file).use { it.getInputStream(it.getEntry(name)).use { stream -> stream.readBytes() } }
    @Test fun paragraphCopyPreservesOtherPartsAndRejectsComplexOrStaleInput() {
        val directory = java.nio.file.Files.createTempDirectory("word-edit").toFile()
        try {
            val source = File(directory, "source.docx"); val output = File(directory, "out.docx")
            zip(source, mapOf("word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:jc w:val="left"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>旧段落</w:t></w:r></w:p><w:p><w:r><w:drawing/></w:r></w:p></w:body></w:document>""", "word/media/image.png" to "synthetic-binary", "word/styles.xml" to "unchanged-style"))
            val original = source.readBytes()
            val spec = Json.parseToJsonElement("""{"index":1,"expectedText":"旧段落","text":"新段落<&>"}""").jsonObject
            MaterialEditor.replaceParagraph(source, output, spec)
            assertEquals("新段落<&>", MaterialEditor.wordParagraphs(output, 0, 1)["paragraphs"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            assertArrayEquals(bytes(source, "word/media/image.png"), bytes(output, "word/media/image.png"))
            assertArrayEquals(bytes(source, "word/styles.xml"), bytes(output, "word/styles.xml"))
            assertArrayEquals(original, source.readBytes())
            assertTrue(runCatching { MaterialEditor.replaceParagraph(source, output, Json.parseToJsonElement("""{"index":1,"expectedText":"wrong","text":"new"}""").jsonObject) }.isFailure)
            assertTrue(runCatching { MaterialEditor.replaceParagraph(source, output, Json.parseToJsonElement("""{"index":2,"expectedText":"","text":"new"}""").jsonObject) }.isFailure)
        } finally { directory.deleteRecursively() }
    }
    @Test fun excelChangesRetainScalarTypesAndLiteralFormulasAndRefuseFormulaBooks() {
        val directory = java.nio.file.Files.createTempDirectory("excel-edit").toFile()
        try {
            val source = File(directory, "source.xlsx"); val output = File(directory, "out.xlsx")
            val parts = mapOf("xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="数据" r:id="s1"/></sheets></workbook>""", "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="s1" Target="worksheets/sheet1.xml"/></Relationships>""", "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>001</t></is></c><c r="B1"><v>2</v></c><c r="C1" t="b"><v>1</v></c></row></sheetData></worksheet>""", "xl/styles.xml" to "unchanged")
            zip(source, parts)
            val spec = Json.parseToJsonElement("""{"sheet":"数据","changes":[{"cell":"A1","expected":"001","value":"=1+1"},{"cell":"B1","expected":2.0,"value":3},{"cell":"C1","expected":true,"value":false},{"cell":"D2","expected":null,"value":"003"}]}""").jsonObject
            MaterialEditor.setCells(source, output, spec)
            val cells = MaterialEditor.excelCells(output, "数据", 0, 10)["cells"]!!.jsonArray.associate { it.jsonObject["cell"]!!.jsonPrimitive.content to it.jsonObject["value"] }
            assertEquals(JsonPrimitive("=1+1"), cells["A1"]); assertEquals(JsonPrimitive(3), cells["B1"]); assertEquals(JsonPrimitive(false), cells["C1"]); assertEquals(JsonPrimitive("003"), cells["D2"])
            assertArrayEquals(bytes(source, "xl/styles.xml"), bytes(output, "xl/styles.xml"))
            assertTrue(runCatching { MaterialEditor.setCells(source, output, Json.parseToJsonElement("""{"sheet":"数据","changes":[{"cell":"A1","expected":"wrong","value":"new"}]}""").jsonObject) }.isFailure)
            zip(source, parts + ("xl/worksheets/sheet1.xml" to parts.getValue("xl/worksheets/sheet1.xml").replace("<v>2</v>", "<f>SUM(A1)</f><v>2</v>")))
            assertTrue(runCatching { MaterialEditor.setCells(source, output, spec) }.isFailure)
        } finally { directory.deleteRecursively() }
    }
    @Test fun entitiesAndUnknownFieldsAreRejected() {
        val directory = java.nio.file.Files.createTempDirectory("editor-reject").toFile()
        try {
            val source = File(directory, "bad.docx")
            zip(source, mapOf("word/document.xml" to """<!DOCTYPE x [<!ENTITY e SYSTEM "file:///secret">]><x>&e;</x>"""))
            assertTrue(runCatching { MaterialEditor.wordParagraphs(source, 0, 10) }.isFailure)
        } finally { directory.deleteRecursively() }
    }
}
