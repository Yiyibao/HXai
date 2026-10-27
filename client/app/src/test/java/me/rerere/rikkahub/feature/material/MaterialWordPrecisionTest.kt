package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MaterialWordPrecisionTest {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val m = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private fun run(text: String) = "<w:r><w:t>$text</w:t></w:r>"
    private fun field(type: String) = "<w:r><w:fldChar w:fldCharType=\"$type\"/></w:r>"
    private fun spec(text: String, start: Int, end: Int, replacement: String = "new") = buildJsonObject { put("index", 1); put("expectedText", text); put("start", start); put("end", end); put("text", replacement) }
    private fun source(folder: File, body: String, utf16: Boolean = false): File {
        val text = "<?xml version=\"1.0\" encoding=\"${if (utf16) "UTF-16" else "UTF-8"}\"?><w:document xmlns:w=\"$w\" xmlns:m=\"$m\"><w:body>$body</w:body></w:document>"
        return File(folder, "source.docx").also { file -> ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(text.toByteArray(if (utf16) Charsets.UTF_16 else Charsets.UTF_8)); zip.closeEntry() } }
    }
    private fun inFolder(block: (File) -> Unit) {
        val folder = java.nio.file.Files.createTempDirectory("word-precision").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }
    @Test fun normalUtf16AndAstralCharactersAcceptWholeCodePointsButRejectSplitPairs() = inFolder { folder ->
        val source = source(folder, "<w:p>${run("a😀b")}</w:p>", utf16 = true)
        val output = File(folder, "out.docx")
        assertTrue(runCatching { MaterialEditor.replaceParagraphText(source, output, spec("a😀b", 2, 3)) }.isFailure)
        assertFalse(output.exists())
        MaterialEditor.replaceParagraphText(source, output, spec("a😀b", 1, 3, "Ω𝑥"))
        assertEquals("aΩ𝑥b", MaterialEditor.wordParagraphs(output, 0, 1).getValue("paragraphs").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
    }
    @Test fun structuralBarriersCannotBeCrossedButDoNotBlockAdjacentPlainText() = inFolder { folder ->
        val barriers = listOf(
            "<w:bookmarkStart w:id=\"1\" w:name=\"anchor\"/>", "<m:oMath><m:r><m:t>x</m:t></m:r></m:oMath>",
            "<w:r><w:drawing/></w:r>", "<w:hyperlink>${run("linked")}</w:hyperlink>", "<w:commentRangeStart w:id=\"1\"/>",
            "<w:r><w:br/></w:r>", "<w:r><w:tab/></w:r>", "<w:unknown/>", "<w:fldSimple w:instr=\"PAGE\">${run("1")}</w:fldSimple>"
        )
        barriers.forEachIndexed { index, barrier ->
            val source = source(folder, "<w:p>${run("left")}$barrier${run("right")}</w:p>")
            val inspected = MaterialEditor.wordParagraphs(source, 0, 1).getValue("paragraphs").jsonArray.single().jsonObject
            val text = inspected.getValue("text").jsonPrimitive.content
            val output = File(folder, "barrier-$index.docx")
            assertTrue(barrier, runCatching { MaterialEditor.replaceParagraphText(source, output, spec(text, 2, text.length - 2)) }.isFailure)
            assertFalse(output.exists())
            MaterialEditor.replaceParagraphText(source, output, spec(text, 0, 4, "safe"))
            assertEquals("safe" + text.substring(4), MaterialEditor.wordParagraphs(output, 0, 1).getValue("paragraphs").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        }
    }
    @Test fun nestedCrossParagraphFieldsStayProtectedUntilTheOuterEnd() = inFolder { folder ->
        val source = source(folder, "<w:p>${field("begin")}</w:p><w:p>${field("begin")}${field("separate")}</w:p><w:p>${run("inner")}</w:p><w:p>${field("end")}</w:p><w:p>${run("outer")}</w:p><w:p>${field("end")}</w:p><w:p>${run("body")}</w:p>")
        val runs = MaterialEditor.textRuns(source, "docx", 0, 0, 20).getValue("runs").jsonArray.map { it.jsonObject }
        assertEquals(listOf(false, false, true), runs.map { it.getValue("editable").jsonPrimitive.boolean })
        assertEquals(listOf("inner", "outer", "body"), runs.map { it.getValue("text").jsonPrimitive.content })
        MaterialEditor.replaceTextRun(source, File(folder, "body.docx"), "docx", buildJsonObject { put("index", 3); put("expectedText", "body"); put("text", "safe body") })
    }
    @Test fun malformedFieldRangesFailClosedEvenForOtherwiseSimpleParagraphs() = inFolder { folder ->
        for (fields in listOf(field("begin"), field("end"), field("separate"), field("begin") + field("separate") + field("separate") + field("end"))) {
            val source = source(folder, "<w:p>$fields</w:p><w:p>${run("body")}</w:p>")
            val paragraph = MaterialEditor.wordParagraphs(source, 0, 10).getValue("paragraphs").jsonArray.last().jsonObject
            assertFalse(paragraph.getValue("editable").jsonPrimitive.boolean)
            assertFalse(paragraph.getValue("has_editable_text_ranges").jsonPrimitive.boolean)
            val output = File(folder, "not-written.docx")
            assertTrue(runCatching { MaterialEditor.replaceTextRun(source, output, "docx", buildJsonObject { put("index", 1); put("expectedText", "body"); put("text", "corrupt") }) }.isFailure)
            assertFalse(output.exists())
        }
    }
    @Test fun deletionMovedTextAndLockedControlsAreNotEditableThroughAlternateOperations() = inFolder { folder ->
        val paragraphs = "<w:p>${run("protected")}</w:p><w:p><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></w:p>"
        for (body in listOf("<w:del>$paragraphs</w:del>", "<w:moveFrom>$paragraphs</w:moveFrom>", "<w:sdt><w:sdtPr><w:lock w:val=\"contentLocked\"/></w:sdtPr><w:sdtContent>$paragraphs</w:sdtContent></w:sdt>")) {
            val source = source(folder, body); val output = File(folder, "not-written.docx")
            assertFalse(MaterialEditor.wordParagraphs(source, 0, 1).getValue("paragraphs").jsonArray.single().jsonObject.getValue("editable").jsonPrimitive.boolean)
            val basic = buildJsonObject { put("index", 1); put("expectedText", "protected"); put("text", "corrupt") }
            assertTrue(runCatching { MaterialEditor.replaceParagraph(source, output, basic) }.isFailure)
            assertTrue(runCatching { MaterialEditor.replaceTextRun(source, output, "docx", basic) }.isFailure)
            assertTrue(runCatching { MaterialEditor.replaceParagraphText(source, output, spec("protected", 0, 9)) }.isFailure)
            assertTrue(runCatching { MaterialEditor.replaceEquation(source, output, buildJsonObject { put("index", 1); put("expectedText", "[公式 x]"); put("equation", buildJsonObject { put("type", "text"); put("text", "corrupt") }) }) }.isFailure)
            assertFalse(output.exists())
        }
    }
    @Test fun fullDeletionRetainsRunsAndStaleOrInvalidSpecificationsNeverWriteAnArtifact() = inFolder { folder ->
        val source = source(folder, "<w:p>${run("left")}${run("right")}</w:p>"); val before = source.readBytes(); val output = File(folder, "deleted.docx")
        val valid = spec("leftright", 0, 9, "")
        for (bad in listOf(JsonObject(valid + ("expectedText" to JsonPrimitive("wrong"))), JsonObject(valid + ("start" to JsonPrimitive(9))), JsonObject(valid + ("end" to JsonPrimitive(10))), JsonObject(valid + ("extra" to JsonPrimitive(1))), JsonObject(valid + ("text" to JsonPrimitive("\uD800"))))) {
            assertTrue(runCatching { MaterialEditor.replaceParagraphText(source, output, bad) }.isFailure); assertFalse(output.exists())
        }
        MaterialEditor.replaceParagraphText(source, output, valid)
        assertEquals("", MaterialEditor.wordParagraphs(output, 0, 1).getValue("paragraphs").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        val root = SafeOfficeXml.parse(OfficePackage.read(output).getValue("word/document.xml"))
        assertEquals(2, root.getElementsByTagNameNS(w, "r").length)
        assertArrayEquals(before, source.readBytes())
    }
}
