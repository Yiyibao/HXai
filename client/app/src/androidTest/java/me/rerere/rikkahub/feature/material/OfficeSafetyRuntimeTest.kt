package me.rerere.rikkahub.feature.material

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class OfficeSafetyRuntimeTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun zip(file: File, parts: Map<String, ByteArray>) = ZipOutputStream(file.outputStream()).use { out ->
        parts.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
    }
    @Test fun androidParserAcceptsValidUtf16AndRejectsDtdXincludeAndMalformedInput() {
        val normal = """<?xml version="1.0" encoding="UTF-16"?><w:document xmlns:w="$w"><w:body><w:p><w:r><w:t>中文 &amp; 𝑥</w:t></w:r></w:p></w:body></w:document>"""
        assertTrue(MaterialWordContent.text(SafeOfficeXml.parse(normal.toByteArray(Charsets.UTF_16))).contains("中文 & 𝑥"))
        assertEquals("中文", SafeOfficeXml.parse("<root>中文</root>".toByteArray()).textContent)
        listOf(
            """<!DOCTYPE root [<!ENTITY e "SYNTHETIC_ONLY">]><root>&e;</root>""",
            """<!DOCTYPE root [<!ENTITY e SYSTEM "file:///synthetic-not-real">]><root>&e;</root>""",
            """<root xmlns:x="http://www.w3.org/2001/XIncl&#117;de"><x:include href="synthetic"/></root>""",
        ).forEach { raw -> listOf(Charsets.UTF_8, Charsets.UTF_16).forEach { charset -> assertTrue(runCatching { SafeOfficeXml.parse(raw.toByteArray(charset)) }.isFailure) } }
        assertTrue(runCatching { SafeOfficeXml.parse(byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure)
    }
    @Test fun realToolsEditHiddenLogicalSlideIntoVerifiedNewVersionAndRejectWrongOwnerHashOrText() = runBlocking {
        val dir = File(target.cacheDir, "office-safety-${UUID.randomUUID()}").apply { mkdirs() }
        // Constructor recovery is isolated from the user's real materials directory.
        val context = object : ContextWrapper(target) { override fun getFilesDir(): File = dir }
        try {
            val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
            val source = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "reordered.pptx", "pptx")) { file ->
                val temp = File(dir, "writer.pptx")
                MaterialWriter.write(temp, "pptx", "", emptyList(), listOf("Logical second", "Logical first"))
                val parts = OfficePackage.read(temp)
                parts["ppt/slides/slide9.xml"] = parts.remove("ppt/slides/slide2.xml")!!.toString(Charsets.UTF_8).replace("<p:sld ", "<p:sld show=\"0\" ").toByteArray()
                parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").toString(Charsets.UTF_8)
                    .replace("/ppt/slides/slide2.xml", "/ppt/slides/slide9.xml").toByteArray()
                parts["ppt/presentation.xml"] = parts.getValue("ppt/presentation.xml").toString(Charsets.UTF_8).replace(
                    "<p:sldId id=\"256\" r:id=\"rId1\"/><p:sldId id=\"257\" r:id=\"rId2\"/>",
                    "<p:sldId id=\"257\" r:id=\"rId2\"/><p:sldId id=\"256\" r:id=\"rId1\"/>").toByteArray()
                parts["ppt/_rels/presentation.xml.rels"] = parts.getValue("ppt/_rels/presentation.xml.rels").toString(Charsets.UTF_8).replace("slides/slide2.xml", "slides/slide9.xml").toByteArray()
                zip(file, parts); "synthetic source"
            }
            val input = store.verifiedFile(owner, source.id); val original = input.readBytes(); val before = OfficePackage.read(input)
            val tools = materialEditTools(store, owner)
            val inspect = tools.single { it.name == "inspect_material_edit" }
            val inspected = inspect.execute(buildJsonObject { put("id", source.id); put("selection", """{"slide":1,"count":1}""") }).single() as UIMessagePart.Text
            val structure = Json.parseToJsonElement(inspected.text).jsonObject["untrusted_structure"]!!.jsonObject
            assertEquals("ppt/slides/slide9.xml", structure["part"]!!.jsonPrimitive.content)
            assertTrue(structure["hidden"]!!.jsonPrimitive.boolean)
            val editor = tools.single { it.name == "edit_material_file" }
            fun args(hash: String = source.sha256, expected: String = "Logical first") = buildJsonObject {
                put("id", source.id); put("expected_sha256", hash); put("operation", "replace_text_run"); put("title", "edited")
                put("spec", """{"slide":1,"index":1,"expectedText":"$expected","text":"Edited logical first"}""")
            }
            fun assertFailed(parts: List<UIMessagePart>) {
                assertFalse(parts.any { it is UIMessagePart.Document })
                assertEquals("FAILED", Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject["status"]!!.jsonPrimitive.content)
            }
            assertFailed(editor.execute(args(hash = "wrong")))
            assertFailed(editor.execute(args(expected = "stale")))
            assertFailed(materialEditTools(store, UUID.randomUUID().toString()).single { it.name == "edit_material_file" }.execute(args()))
            assertFalse(store.list(owner).any { it.output && it.state == "SUCCEEDED" })
            assertTrue(editor.execute(args()).any { it is UIMessagePart.Document })
            val artifact = store.list(owner).single { it.output && it.state == "SUCCEEDED" }
            assertEquals(source.id, artifact.sourceId)
            val output = store.verifiedFile(owner, artifact.id)
            val after = OfficePackage.read(output)
            assertEquals(before.keys, after.keys)
            before.filterKeys { it != "ppt/slides/slide9.xml" }.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after[name]) }
            assertArrayEquals(original, input.readBytes())
            assertEquals(source.sha256, MaterialFiles.digest(input))
            assertTrue(store.readContent(owner, artifact.id).text.contains("Edited logical first"))
            assertTrue(editor.execute(args()).any { it is UIMessagePart.Document })
            assertEquals(1, store.list(owner).count { it.output && it.state == "SUCCEEDED" })
            // Export only synthetic fixtures and hashes for independent host inspection.
            val evidence = File(target.cacheDir, "file-05-evidence").apply { mkdirs() }
            input.copyTo(File(evidence, "source.pptx"), overwrite = true)
            output.copyTo(File(evidence, "output.pptx"), overwrite = true)
            File(evidence, "hashes.json").writeText(buildJsonObject { put("source_sha256", source.sha256); put("output_sha256", artifact.sha256) }.toString())
        } finally { dir.deleteRecursively() }
    }
    @Test fun unsafeUtf16PackageFailsThroughToolWithoutPublishingArtifactAndOldReadCacheIsNotReused() = runBlocking {
        val dir = File(target.cacheDir, "office-reject-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir(): File = dir }
        try {
            val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
            val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "unsafe.docx", "docx")) { file ->
                val xml = """<!DOCTYPE w:document [<!ENTITY e "SYNTHETIC">]><w:document xmlns:w="$w"><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:document>"""
                zip(file, mapOf("word/document.xml" to xml.toByteArray(Charsets.UTF_16))); "source retained"
            }
            val original = store.verifiedFile(owner, item.id).readBytes()
            File(dir, "materials/$owner/${item.id}.read-v4.cache").writeText("""{"text":"cached fake success","coverage":"legacy"}""")
            assertTrue(runCatching { store.readContent(owner, item.id) }.isFailure)
            val result = materialEditTools(store, owner).single { it.name == "edit_material_file" }.execute(buildJsonObject {
                put("id", item.id); put("expected_sha256", item.sha256); put("operation", "replace_text_run"); put("title", "blocked")
                put("spec", """{"index":1,"expectedText":"SYNTHETIC","text":"changed"}""")
            })
            assertFalse(result.any { it is UIMessagePart.Document })
            assertFalse(store.list(owner).any { it.output && it.state == "SUCCEEDED" })
            assertArrayEquals(original, store.verifiedFile(owner, item.id).readBytes())
        } finally { dir.deleteRecursively() }
    }
}
