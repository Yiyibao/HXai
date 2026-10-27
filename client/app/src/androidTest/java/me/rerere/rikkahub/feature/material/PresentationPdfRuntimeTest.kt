package me.rerere.rikkahub.feature.material

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PresentationPdfRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = File("/data/local/tmp/b03-fixtures")
    private val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private fun input(name: String): File {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val file = File(fixture, name)
        val manifest = Json.parseToJsonElement(File(fixture, "FIXTURE.json").readText()).jsonObject
        val key = if (name == "source.pptx") "sourceSha256" else "geometrySha256"
        check(MaterialFiles.digest(file) == manifest.getValue(key).jsonPrimitive.content)
        return file
    }
    private fun shape(root: Element, id: String): Element {
        val nodes = root.getElementsByTagNameNS(p, "sp")
        return (0 until nodes.length).map { nodes.item(it) as Element }.single {
            (it.getElementsByTagNameNS(p, "cNvPr").item(0) as Element).getAttribute("id") == id
        }
    }
    @Test fun logicalHiddenAndVisibleEditsPreserveMasterImagesAndRejectUnrenderedCompatibilityBranches() = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val evidence = File(context.cacheDir, "b03-presentation-evidence").apply { mkdirs() }
        try {
            val source = store.import(owner, input("source.pptx").toUri(), "B03 original presentation.pptx")
            val sourceBytes = store.verifiedFile(owner, source.id).readBytes()
            val tools = materialEditTools(store, owner)
            val inspect = tools.single { it.name == "inspect_material_edit" }
            val edit = tools.single { it.name == "edit_material_file" }
            suspend fun inspection(selection: JsonObject) = inspect.execute(buildJsonObject { put("id", source.id); put("selection", selection.toString()) })
                .filterIsInstance<UIMessagePart.Text>().single().text.let { Json.parseToJsonElement(it).jsonObject.getValue("untrusted_structure").jsonObject }
            for ((logical, visible) in listOf(1 to null, 2 to 1, 3 to 2, 4 to null, 5 to 3)) {
                val listing = inspection(buildJsonObject { put("slide", logical); put("shapes", true); put("offset", 0); put("count", 30) })
                assertEquals("presentation_order_excluding_hidden_not_pdf_page", listing.getValue("visible_slide_basis").jsonPrimitive.content)
                assertEquals(3, listing.getValue("total_visible_slides").jsonPrimitive.int)
                assertEquals(5, listing.getValue("total_slides").jsonPrimitive.int)
                assertEquals(visible, listing.getValue("visible_slide").let { if (it is JsonNull) null else it.jsonPrimitive.int })
                assertEquals(visible == null, listing.getValue("hidden").jsonPrimitive.boolean)
                if (logical == 2) assertEquals("ppt/slides/slide9.xml", listing.getValue("part").jsonPrimitive.content)
            }
            fun spec(slide: Int, text: String) = buildJsonObject { put("slide", slide); put("shapeId", "+00017"); put("index", 1); put("expectedText", "DUPLICATE"); put("text", text) }
            fun args(item: MaterialFile, value: JsonObject, hash: String = item.sha256) = buildJsonObject {
                put("id", item.id); put("expected_sha256", hash); put("operation", "replace_text_run"); put("spec", value.toString()); put("title", "B03 immutable presentation version")
            }
            val visibleSpec = spec(2, "Edited grouped text")
            assertEquals(1, edit.execute(args(source, visibleSpec)).filterIsInstance<UIMessagePart.Document>().size)
            val visibleOutput = store.list(owner).single { it.output && it.state == "SUCCEEDED" }
            assertEquals(source.id, visibleOutput.sourceId)
            assertEquals(1, edit.execute(args(visibleOutput, spec(4, "Edited hidden text"))).filterIsInstance<UIMessagePart.Document>().size)
            val output = store.list(owner).single { it.sourceId == visibleOutput.id && it.state == "SUCCEEDED" }
            assertEquals(setOf(source.id, visibleOutput.id, output.id), MaterialVersions.family(store.list(owner), output.id).map { it.id }.toSet())
            assertArrayEquals(sourceBytes, store.verifiedFile(owner, source.id).readBytes())
            val actual = store.verifiedFile(owner, output.id)
            val before = OfficePackage.read(store.verifiedFile(owner, source.id)); val after = OfficePackage.read(actual)
            val changed = mapOf("ppt/slides/slide9.xml" to "Edited grouped text", "ppt/slides/slide7.xml" to "Edited hidden text")
            assertEquals(before.keys, after.keys)
            before.filterKeys { it !in changed }.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after.getValue(name)) }
            for ((part, expected) in changed) {
                val originalRoot = SafeOfficeXml.parse(before.getValue(part)); val actualRoot = SafeOfficeXml.parse(after.getValue(part))
                val old = shape(originalRoot, "17").getElementsByTagNameNS(a, "t").item(0) as Element
                val edited = shape(actualRoot, "17").getElementsByTagNameNS(a, "t").item(0) as Element
                assertEquals(expected, edited.textContent); edited.textContent = old.textContent
                val xmlNs = "http://www.w3.org/XML/1998/namespace"
                if (old.hasAttributeNS(xmlNs, "space")) edited.setAttributeNS(xmlNs, "xml:space", old.getAttributeNS(xmlNs, "space")) else edited.removeAttributeNS(xmlNs, "space")
                assertTrue("All unselected group, geometry, style and field DOM must survive", originalRoot.isEqualNode(actualRoot))
            }
            suspend fun rejected(item: MaterialFile = source, value: JsonObject = visibleSpec, hash: String = item.sha256, toolOwner: String = owner) {
                val reply = materialEditTools(store, toolOwner).single { it.name == "edit_material_file" }.execute(args(item, value, hash))
                assertFalse(reply.any { it is UIMessagePart.Document })
                assertEquals("FAILED", Json.parseToJsonElement(reply.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject.getValue("status").jsonPrimitive.content)
            }
            rejected(hash = "0".repeat(64)); rejected(toolOwner = UUID.randomUUID().toString())
            rejected(value = JsonObject(visibleSpec + ("slide" to JsonPrimitive(6))))
            rejected(value = JsonObject(visibleSpec + ("expectedText" to JsonPrimitive("not the selected text"))))
            rejected(value = JsonObject(visibleSpec + ("shapeId" to JsonPrimitive("90")) + ("expectedText" to JsonPrimitive("AUTOMATIC FIELD"))))
            val compatibility = inspection(buildJsonObject { put("slide", 3); put("offset", 0); put("count", 50) }).getValue("runs").jsonArray
            for (text in listOf("UNSUPPORTED CHOICE", "VISIBLE FALLBACK")) {
                val run = compatibility.single { it.jsonObject.getValue("text").jsonPrimitive.content == text }.jsonObject
                assertFalse(run.getValue("editable").jsonPrimitive.boolean)
                rejected(value = buildJsonObject { put("slide", 3); put("index", run.getValue("index")); put("expectedText", text); put("text", "FALSELY EDITED BRANCH") })
            }
            val ambiguousRoot = SafeOfficeXml.parse(before.getValue("ppt/slides/slide9.xml"))
            (shape(ambiguousRoot, "4").getElementsByTagNameNS(p, "cNvPr").item(0) as Element).setAttribute("id", "+00017")
            val buffer = ByteArrayOutputStream()
            javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(javax.xml.transform.dom.DOMSource(ambiguousRoot.ownerDocument), javax.xml.transform.stream.StreamResult(buffer))
            val bad = File(evidence, "ambiguous-numeric-id.pptx")
            ZipOutputStream(bad.outputStream()).use { zip -> before.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(if (name == "ppt/slides/slide9.xml") buffer.toByteArray() else bytes); zip.closeEntry() } }
            val ambiguous = store.import(owner, bad.toUri(), "Numeric alias collision.pptx"); rejected(item = ambiguous)
            assertEquals(setOf(visibleOutput.id, output.id), store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet())
            store.verifiedFile(owner, source.id).copyTo(File(evidence, "source.pptx"), overwrite = true)
            actual.copyTo(File(evidence, "edited.pptx"), overwrite = true)
            store.verifiedFile(owner, visibleOutput.id).copyTo(File(evidence, "visible-edit.pptx"), overwrite = true)
            File(evidence, "result.json").writeText(buildJsonObject {
                put("status", "PASS"); put("sourceSha256", source.sha256); put("artifactSha256", output.sha256)
                put("sourceId", source.id); put("visibleVersionId", visibleOutput.id); put("artifactId", output.id)
                put("changedParts", JsonArray(changed.keys.map(::JsonPrimitive))); put("logicalSlideCount", 5); put("visibleSlideCount", 3)
                put("unrelatedPartsBytePreserved", true); put("unrelatedSlideDomPreserved", true); put("layout", "NOT_VERIFIED")
            }.toString())
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
    @Test fun originalPageAssemblyPreservesCroppedRotatedUserUnitsRasterAndBlankPages() = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        val evidence = File(context.cacheDir, "b03-pdf-geometry-evidence").apply { mkdirs() }
        try {
            val source = store.import(owner, input("geometry.pdf").toUri(), "Geometric original.pdf")
            val selection = listOf(4, 1, 3, 2, 4)
            val args = buildJsonObject {
                put("id", source.id); put("expected_sha256", source.sha256); put("operation", "pdf_select_pages"); put("title", "Geometric selected pages")
                put("spec", buildJsonObject { put("sources", buildJsonArray { add(buildJsonObject { put("id", source.id); put("sha256", source.sha256); put("pages", JsonArray(selection.map(::JsonPrimitive))) }) }) }.toString())
            }
            val tool = materialEditTools(store, owner).single { it.name == "edit_material_file" }
            val reply = tool.execute(args); assertEquals(reply.toString(), 1, reply.filterIsInstance<UIMessagePart.Document>().size)
            val output = store.list(owner).single { it.output && it.state == "SUCCEEDED" }
            val detail = Json.parseToJsonElement(output.detail).jsonObject
            assertEquals(selection, detail.getValue("pages").jsonArray.map { it.jsonObject.getValue("source_physical_page").jsonPrimitive.int })
            assertEquals(5, MaterialReader.read(store.verifiedFile(owner, output.id), "pdf").totalPages)
            assertEquals(source.id, output.sourceId)
            assertEquals(reply.filterIsInstance<UIMessagePart.Document>().single().url, tool.execute(args).filterIsInstance<UIMessagePart.Document>().single().url)
            assertEquals(1, store.list(owner).count { it.output })
            assertEquals(source.sha256, MaterialFiles.digest(store.verifiedFile(owner, source.id)))
            for ((name, item) in listOf("source" to source, "output" to output)) {
                store.verifiedFile(owner, item.id).copyTo(File(evidence, "$name.pdf"), overwrite = true)
                File(evidence, "$name.meta.json").writeText(Json.encodeToString(MaterialFile.serializer(), item))
            }
            File(evidence, "result.json").writeText(buildJsonObject { put("status", "PASS"); put("sourceSha256", source.sha256); put("artifactSha256", output.sha256); put("sourcePages", JsonArray(selection.map(::JsonPrimitive))) }.toString())
        } finally { File(context.filesDir, "materials/$owner").deleteRecursively() }
    }
}
