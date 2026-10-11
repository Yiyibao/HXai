package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordFormulaAssertionTest {
    @Test fun missingImageConfigurationIsFailureAndCreatesNoSuccessfulImage() = kotlinx.coroutines.runBlocking {
        val koin = org.koin.core.context.GlobalContext.get()
        val original = kotlinx.coroutines.withTimeout(20000) {
            koin.get<me.rerere.rikkahub.data.datastore.SettingsStore>().settingsFlow.first { !it.init }
        }
        val owner = java.util.UUID.randomUUID().toString()
        val store = koin.get<me.rerere.rikkahub.feature.material.MaterialFiles>()
        val config = original.copy(imageGenerationModelId = kotlin.uuid.Uuid.random(), providers = emptyList())
        val tool = me.rerere.rikkahub.feature.material.materialTools(store, owner, config, koin.get(), me.rerere.ai.provider.Model())
            .single { it.name == "generate_material_image" }
        val result = tool.execute(kotlinx.serialization.json.buildJsonObject { put("prompt", kotlinx.serialization.json.JsonPrimitive("synthetic QA")) })
        assertTrue(result.none { it is me.rerere.ai.ui.UIMessagePart.Image })
        val json = org.json.JSONObject(result.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text)
        org.junit.Assert.assertEquals("FAILED", json.getString("status"))
        assertTrue(json.getString("error").contains("未配置"))
        // No list() call: even an empty QA materials directory must not be created by this failure.
        val root = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "materials/$owner")
        assertTrue(!root.exists())
    }
    @Test fun tableCellsRejectDeletionReorderingAndValueChanges() = kotlinx.coroutines.runBlocking {
        val evidence = QaEvidenceRun("WordFormulaAssertionTest.tableCellsRejectDeletionReorderingAndValueChanges")
        val rows = listOf(listOf("名称", "数值"), listOf("样例", "19"), listOf("第二行", "中文 & <内容>"))
        val source = evidence.file("source.docx")
        me.rerere.rikkahub.feature.material.MaterialWriter.write(source, "docx", "synthetic QA", rows, emptyList())
        assertWordTablesMatch(source, source)
        org.junit.Assert.assertEquals(listOf(rows), wordTableCells(source))
        var status = "FAILED"
        try {
            for ((name, changed) in listOf("value" to rows.map { it.map { value -> if (value == "19") "20" else value } },
                "deleted-row" to rows.dropLast(1), "reordered" to rows.reversed(), "empty" to emptyList())) {
                val output = evidence.file("$name.docx")
                me.rerere.rikkahub.feature.material.MaterialWriter.write(output, "docx", "synthetic QA", changed, emptyList())
                assertTrue("$name must fail", runCatching { assertWordTablesMatch(source, output) }.exceptionOrNull() is AssertionError)
            }
            status = "PASS"
        } finally { evidence.finish(status) }
    }

    @Test fun perRunMaterialSamplesExistAndContainSyntheticContent() = kotlinx.coroutines.runBlocking {
        val evidence = QaEvidenceRun("WordFormulaAssertionTest.perRunMaterialSamplesExistAndContainSyntheticContent")
        var status = "FAILED"
        try {
            for (extension in listOf("md", "docx", "xlsx", "pptx", "pdf")) {
                val sample = evidence.file("sample.$extension")
                writeSyntheticMaterialSample(sample, extension)
                val read = me.rerere.rikkahub.feature.material.MaterialReader.read(sample, extension)
                assertTrue("$extension contains synthetic data", read.text.contains("三角形") || read.text.contains("样例"))
            }
            status = "PASS"
        } finally { evidence.finish(status) }
    }
    @Test fun rejectsMissingAndChangedImagesWithProductionReader() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.io.File(context.cacheDir, "qa-image-negative-${java.util.UUID.randomUUID()}").apply { mkdir() }
        val source = java.io.File(folder, "source.docx")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val image = java.io.ByteArrayOutputStream()
        try { bitmap.eraseColor(android.graphics.Color.BLUE); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, image) } finally { bitmap.recycle() }
        try {
            kotlinx.coroutines.runBlocking { me.rerere.rikkahub.feature.material.MaterialWriter.write(source, "docx", "synthetic image", emptyList(), emptyList(), images = listOf(image.toByteArray())) }
            assertWordImagesMatch(source, source)
            for (kind in listOf("missing", "changed")) {
                val output = java.io.File(folder, "$kind.docx")
                java.util.zip.ZipFile(source).use { zip -> java.util.zip.ZipOutputStream(output.outputStream()).use { out ->
                    zip.entries().asSequence().forEach { entry ->
                        if (kind == "missing" && entry.name.startsWith("word/media/")) return@forEach
                        val bytes = zip.getInputStream(entry).readBytes()
                        out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        out.write(if (kind == "changed" && entry.name.startsWith("word/media/")) bytes + byteArrayOf(1) else bytes)
                        out.closeEntry()
                    }
                } }
                assertTrue("$kind image must fail", runCatching { assertWordImagesMatch(source, output) }.isFailure)
            }
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
    private fun xml(num: String = "x+1", den: String = "2", base: String = "y", power: String = "3", table: Boolean = true) =
        """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><w:body><m:oMath><m:f><m:num><m:r><m:t>$num</m:t></m:r></m:num><m:den><m:r><m:t>$den</m:t></m:r></m:den></m:f><m:sSup><m:e><m:r><m:t>$base</m:t></m:r></m:e><m:sup><m:r><m:t>$power</m:t></m:r></m:sup></m:sSup></m:oMath>${if (table) "<w:tbl/>" else ""}</w:body></w:document>"""

    @Test fun acceptsEditableStructureAndRedundantOuterParentheses() {
        assertWordFormulaXml(xml())
        assertWordFormulaXml(xml("((x + 1))", "(2)", "(y)", "((3))"))
    }

    @Test fun rejectsWrongOperandsAndMissingTable() {
        for (bad in listOf(xml(num = "x+2"), xml(den = "3"), xml(base = "z"), xml(power = "2"), xml(table = false))) {
            assertTrue("Invalid Word must fail", runCatching { assertWordFormulaXml(bad) }.exceptionOrNull() is AssertionError)
        }
    }

    @Test fun rejectsPlainTextAndNestedFormulaImpostors() {
        val plain = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>(x+1)/(2) y^3</w:t></w:r></w:p><w:tbl/></w:body></w:document>"""
        assertTrue(runCatching { assertWordFormulaXml(plain) }.exceptionOrNull() is AssertionError)
        val nested = xml().replace("<m:t>x+1</m:t>", "<m:f><m:num><m:r><m:t>x+1</m:t></m:r></m:num><m:den><m:r><m:t>1</m:t></m:r></m:den></m:f>")
        assertTrue(runCatching { assertWordFormulaXml(nested) }.exceptionOrNull() is AssertionError)
    }
}
