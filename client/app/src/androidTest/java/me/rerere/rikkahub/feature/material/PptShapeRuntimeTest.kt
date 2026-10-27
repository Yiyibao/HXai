package me.rerere.rikkahub.feature.material

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PptShapeRuntimeTest {
    private val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private fun shape(root: Element, id: String): Element {
        val nodes = root.getElementsByTagNameNS(p, "sp")
        return (0 until nodes.length).map { nodes.item(it) as Element }.single { (it.getElementsByTagNameNS(p, "cNvPr").item(0) as Element).getAttribute("id") == id }
    }
    @Test fun realToolUsesShapeIdRatherThanRepeatedTextAndPreservesAllOtherStructure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val settings = GlobalContext.get().get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val store = GlobalContext.get().get<MaterialFiles>(); val owner = settings.createLearnerProfile("B1d synthetic PPT QA")
        val evidence = File(context.cacheDir, "b1d-shapes-evidence").apply { mkdirs() }
        try {
            settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
            val input = File(context.cacheDir, "b1d-shapes-source.pptx")
            InstrumentationRegistry.getInstrumentation().context.assets.open("ppt_shapes/complex-original.pptx").use { fixture ->
                input.outputStream().use { fixture.copyTo(it) }
            }
            val source = store.import(owner, input.toUri(), "Complex original.pptx")
            val sourceBytes = store.verifiedFile(owner, source.id).readBytes()
            val tools = materialEditTools(store, owner)
            val inspect = tools.single { it.name == "inspect_material_edit" }
            suspend fun inspection(selection: String) = inspect.execute(buildJsonObject { put("id", source.id); put("selection", selection) })
                .filterIsInstance<UIMessagePart.Text>().single().text.let { Json.parseToJsonElement(it).jsonObject.getValue("untrusted_structure").jsonObject }
            val listing = inspection("{\"slide\":2,\"shapes\":true,\"offset\":0,\"count\":20}")
            assertEquals("ppt/slides/slide9.xml", listing.getValue("part").jsonPrimitive.content)
            assertTrue(listing.getValue("shapes").jsonArray.any { it.jsonObject.getValue("shapeId").jsonPrimitive.content == "17" })
            val selected = inspection("{\"slide\":2,\"shapeId\":\"17\",\"offset\":0,\"count\":10}")
            assertEquals("DUPLICATE", selected.getValue("runs").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content)
            assertTrue(inspection("{\"slide\":1,\"offset\":0,\"count\":1}").getValue("hidden").jsonPrimitive.boolean)
            val spec = buildJsonObject { put("slide", 2); put("shapeId", "17"); put("index", 1); put("expectedText", "DUPLICATE"); put("text", "Edited selected shape") }
            fun args(value: JsonObject = spec, hash: String = source.sha256, id: String = source.id) = buildJsonObject {
                put("id", id); put("expected_sha256", hash); put("operation", "replace_text_run"); put("spec", value.toString()); put("title", "Shape edit version")
            }
            val edit = tools.single { it.name == "edit_material_file" }
            assertTrue(edit.execute(args()).any { it is UIMessagePart.Document })
            val output = store.list(owner).single { it.output && it.state == "SUCCEEDED" }
            assertEquals(source.id, output.sourceId)
            assertEquals(setOf(source.id, output.id), MaterialVersions.family(store.list(owner), output.id).map { it.id }.toSet())
            assertArrayEquals(sourceBytes, store.verifiedFile(owner, source.id).readBytes())
            val actual = store.verifiedFile(owner, output.id)
            val before = OfficePackage.read(input); val after = OfficePackage.read(actual)
            assertEquals(before.keys, after.keys)
            before.filterKeys { it != "ppt/slides/slide9.xml" }.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after.getValue(name)) }
            val originalRoot = SafeOfficeXml.parse(before.getValue("ppt/slides/slide9.xml"))
            val actualRoot = SafeOfficeXml.parse(after.getValue("ppt/slides/slide9.xml"))
            assertEquals("DUPLICATE", shape(actualRoot, "2").getElementsByTagNameNS(a, "t").item(0).textContent)
            assertEquals("DUPLICATE", shape(actualRoot, "4").getElementsByTagNameNS(a, "t").item(0).textContent)
            val changed = shape(actualRoot, "17").getElementsByTagNameNS(a, "t").item(0) as Element
            val old = shape(originalRoot, "17").getElementsByTagNameNS(a, "t").item(0) as Element
            assertEquals("Edited selected shape", changed.textContent)
            changed.textContent = old.textContent
            val xmlNs = "http://www.w3.org/XML/1998/namespace"
            if (old.hasAttributeNS(xmlNs, "space")) changed.setAttributeNS(xmlNs, "xml:space", old.getAttributeNS(xmlNs, "space")) else changed.removeAttributeNS(xmlNs, "space")
            assertTrue("Unrelated slide DOM must remain identical", originalRoot.isEqualNode(actualRoot))
            suspend fun rejected(value: JsonObject = spec, hash: String = source.sha256, toolOwner: String = owner) {
                val reply = materialEditTools(store, toolOwner).single { it.name == "edit_material_file" }.execute(args(value, hash))
                assertFalse(reply.any { it is UIMessagePart.Document })
                assertEquals("FAILED", Json.parseToJsonElement(reply.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject.getValue("status").jsonPrimitive.content)
            }
            rejected(hash = "0".repeat(64)); rejected(toolOwner = UUID.randomUUID().toString())
            rejected(value = JsonObject(spec + ("shapeId" to JsonPrimitive("999"))))
            rejected(value = JsonObject(spec + ("expectedText" to JsonPrimitive("wrong"))))
            rejected(value = JsonObject(spec + ("slide" to JsonPrimitive(1))))
            val field = inspection("{\"slide\":2,\"shapeId\":\"90\",\"offset\":0,\"count\":1}").getValue("runs").jsonArray.single().jsonObject
            assertFalse(field.getValue("editable").jsonPrimitive.boolean)
            rejected(value = JsonObject(spec + mapOf("shapeId" to JsonPrimitive("90"), "expectedText" to JsonPrimitive("DATE FIELD"))))
            val duplicateRoot = SafeOfficeXml.parse(before.getValue("ppt/slides/slide9.xml"))
            (shape(duplicateRoot, "4").getElementsByTagNameNS(p, "cNvPr").item(0) as Element).setAttribute("id", "17")
            val buffer = ByteArrayOutputStream()
            javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(javax.xml.transform.dom.DOMSource(duplicateRoot.ownerDocument), javax.xml.transform.stream.StreamResult(buffer))
            val duplicated = File(evidence, "duplicate-id.pptx")
            ZipOutputStream(duplicated.outputStream()).use { zip -> before.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(if (name == "ppt/slides/slide9.xml") buffer.toByteArray() else bytes); zip.closeEntry() } }
            val malformed = store.import(owner, duplicated.toUri(), "Ambiguous shape IDs.pptx")
            val refused = edit.execute(args(id = malformed.id, hash = malformed.sha256))
            assertFalse(refused.any { it is UIMessagePart.Document })
            assertEquals("FAILED", Json.parseToJsonElement(refused.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(setOf(output.id), store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet())
            input.copyTo(File(evidence, "source.pptx"), overwrite = true); actual.copyTo(File(evidence, "edited.pptx"), overwrite = true)
            File(evidence, "result.json").writeText(buildJsonObject {
                put("status", "PASS"); put("sourceSha256", source.sha256); put("artifactSha256", output.sha256)
                put("sourceId", source.id); put("artifactId", output.id); put("logicalSlide", 2); put("part", "ppt/slides/slide9.xml"); put("shapeId", "17")
                put("unrelatedPartsBytePreserved", true); put("unrelatedSlideDomPreserved", true); put("layout", "NOT_VERIFIED")
            }.toString())
        } finally {
            me.rerere.rikkahub.service.restoreMemoryProfileFixture(original, settings)
            File(context.filesDir, "materials/$owner").deleteRecursively()
        }
    }
}
