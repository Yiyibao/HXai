package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PresentationPrecisionTest {
    private val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun shape(id: String, text: String) = "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Synthetic shape\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>"
    private fun parts(ids: List<String> = listOf("256"), body: String = shape("17", "ordinary"), visibility: List<String?> = List(ids.size) { null }): Map<String, ByteArray> = buildMap {
        put("ppt/presentation.xml", ("<p:presentation xmlns:p=\"$p\" xmlns:r=\"$r\"><p:sldIdLst>" + ids.mapIndexed { index, id -> "<p:sldId id=\"$id\" r:id=\"s$index\"/>" }.joinToString("") + "</p:sldIdLst></p:presentation>").toByteArray())
        put("ppt/_rels/presentation.xml.rels", ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + ids.indices.joinToString("") { "<Relationship Id=\"s$it\" Type=\"$r/slide\" Target=\"slides/slide${ids.size - it}.xml\"/>" } + "</Relationships>").toByteArray())
        ids.indices.forEach { index ->
            val show = visibility[index]?.let { " show=\"$it\"" }.orEmpty()
            put("ppt/slides/slide${ids.size - index}.xml", "<p:sld xmlns:p=\"$p\" xmlns:a=\"$a\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" xmlns:future=\"urn:hxai:b03:future\"$show><p:cSld><p:spTree>$body</p:spTree></p:cSld></p:sld>".toByteArray())
        }
    }
    private fun withSource(parts: Map<String, ByteArray>, block: (File, File) -> Unit) {
        val folder = java.nio.file.Files.createTempDirectory("ppt-precision").toFile()
        try {
            val source = File(folder, "source.pptx")
            ZipOutputStream(source.outputStream()).use { zip -> parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
            block(source, File(folder, "output.pptx"))
        } finally { folder.deleteRecursively() }
    }
    @Test fun compatibilityChoiceAndFallbackCannotPretendToBeOrdinaryVisibleRunEdits() {
        val body = "<mc:AlternateContent><mc:Choice Requires=\"future\">${shape("17", "unsupported choice")}</mc:Choice><mc:Fallback>${shape("18", "visible fallback")}</mc:Fallback></mc:AlternateContent>" + shape("19", "ordinary")
        withSource(parts(body = body)) { source, output ->
            val before = source.readBytes()
            val runs = MaterialEditor.textRuns(source, "pptx", 1, 0, 20).getValue("runs").jsonArray
            assertEquals(listOf(false, false, true), runs.map { it.jsonObject.getValue("editable").jsonPrimitive.boolean })
            for (index in 1..2) assertTrue(runCatching { MaterialEditor.replaceTextRun(source, output, "pptx", buildJsonObject {
                put("slide", 1); put("index", index); put("expectedText", runs[index - 1].jsonObject.getValue("text")); put("text", "invisible false success")
            }) }.isFailure)
            assertFalse(output.exists())
            MaterialEditor.replaceTextRun(source, output, "pptx", buildJsonObject { put("slide", 1); put("shapeId", "19"); put("index", 1); put("expectedText", "ordinary"); put("text", "safe ordinary edit") })
            assertArrayEquals(before, source.readBytes())
            assertEquals("safe ordinary edit", MaterialEditor.textRuns(output, "pptx", 1, 0, 1, "19").getValue("runs").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        }
    }
    @Test fun equivalentUnsignedShapeIdsAreAmbiguousEvenWhenTheirXmlStringsDiffer() {
        withSource(parts(body = shape("17", "first") + shape("+00017", "second"))) { source, output ->
            val listing = MaterialEditor.pptShapes(source, 1, 0, 20).getValue("shapes").jsonArray
            assertTrue(listing.all { !it.jsonObject.getValue("locatable").jsonPrimitive.boolean })
            assertTrue(runCatching { MaterialEditor.replaceTextRun(source, output, "pptx", buildJsonObject { put("slide", 1); put("shapeId", "17"); put("index", 1); put("expectedText", "first"); put("text", "wrong ambiguous edit") }) }.isFailure)
            assertFalse(output.exists())
        }
    }
    @Test fun equivalentSlideIdsAreRejectedRatherThanMappedAsIndependentIdentities() {
        assertTrue(runCatching { PresentationSlideOrder.slides(parts(ids = listOf("256", "+000256"))) }.isFailure)
    }
    @Test fun legalWhitespaceBooleanAndUnsignedLexicalFormsRemainUsableWithUtf16() {
        val input = parts(ids = listOf("+000256"), body = shape("+00017", "ordinary"), visibility = listOf("  false  ")).toMutableMap()
        val path = "ppt/slides/slide1.xml"
        input[path] = ("<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + input.getValue(path).toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_16)
        withSource(input) { source, output ->
            assertTrue(PresentationSlideOrder.slides(input).single().hidden)
            MaterialEditor.replaceTextRun(source, output, "pptx", buildJsonObject { put("slide", 1); put("shapeId", "+00017"); put("index", 1); put("expectedText", "ordinary"); put("text", "legal hidden edit") })
            assertEquals("legal hidden edit", MaterialEditor.textRuns(output, "pptx", 1, 0, 1, "+00017").getValue("runs").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        }
    }
    @Test fun visibleOrderMetadataIsExplicitlyNotAnExportedPdfPageNumber() {
        withSource(parts(ids = listOf("256", "257", "258"), visibility = listOf("0", "true", "false"))) { source, _ ->
            val hidden = MaterialEditor.pptShapes(source, 1, 0, 1)
            val visible = MaterialEditor.textRuns(source, "pptx", 2, 0, 1)
            assertEquals(JsonNull, hidden.getValue("visible_slide"))
            assertEquals(1, visible.getValue("visible_slide").jsonPrimitive.int)
            assertEquals(1, visible.getValue("total_visible_slides").jsonPrimitive.int)
            assertEquals("presentation_order_excluding_hidden_not_pdf_page", visible.getValue("visible_slide_basis").jsonPrimitive.content)
            assertEquals("ppt/slides/slide2.xml", visible.getValue("part").jsonPrimitive.content)
        }
    }
}
