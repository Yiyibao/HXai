package me.rerere.rikkahub.feature.material

import android.graphics.Bitmap
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

class DocxEquationRuntimeTest {
    private val mathNs = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private suspend fun fixture(file: File) {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream()
        try { bitmap.eraseColor(android.graphics.Color.BLUE); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) }
        finally { bitmap.recycle() }
        MaterialWriter.write(file, "docx", "Complex DOCX preserved body", listOf(listOf("Header", "Value"), listOf("Data", "7")), emptyList(),
            buildJsonObject { put("equations", buildJsonArray {
                add(buildJsonObject { put("type", "fraction"); put("numerator", "a"); put("denominator", "b") })
                add(buildJsonObject { put("type", "superscript"); put("base", "x"); put("exponent", "2") })
            }) }, listOf(png.toByteArray()))
        val parts = OfficePackage.read(file)
        var document = parts.getValue("word/document.xml").toString(Charsets.UTF_8)
        document = document.replace("<w:body>", "<w:body><w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> TOC </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>Preserved contents field</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>")
            .replace("<w:p><m:oMath><m:sSup>", "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>Before equation </w:t></w:r><m:oMath><m:sSup>")
            .replace("</m:sSup></m:oMath></w:p>", "</m:sSup></m:oMath><w:r><w:rPr><w:i/></w:rPr><w:t> after equation</w:t></w:r></w:p>")
            .replace("<w:t xml:space=\"preserve\">Data</w:t></w:r></w:p>", "<w:t xml:space=\"preserve\">Data</w:t></w:r><m:oMath><m:r><m:t>table z</m:t></m:r></m:oMath></w:p>")
            .replace("<w:sectPr>", "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"hdr\"/><w:footerReference w:type=\"default\" r:id=\"ftr\"/>")
        parts["word/document.xml"] = document.toByteArray()
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        parts["word/header1.xml"] = "<w:hdr xmlns:w=\"$w\"><w:p><w:r><w:t>Header preserved</w:t></w:r></w:p></w:hdr>".toByteArray()
        parts["word/footer1.xml"] = "<w:ftr xmlns:w=\"$w\"><w:p><w:r><w:t>Footer preserved </w:t></w:r><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>".toByteArray()
        parts["word/styles.xml"] = "<w:styles xmlns:w=\"$w\"><w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"Heading 2\"/><w:rPr><w:color w:val=\"204080\"/><w:sz w:val=\"28\"/></w:rPr></w:style></w:styles>".toByteArray()
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").toString(Charsets.UTF_8).replace("</Relationships>",
            "<Relationship Id=\"hdr\" Type=\"$rel/header\" Target=\"header1.xml\"/><Relationship Id=\"ftr\" Type=\"$rel/footer\" Target=\"footer1.xml\"/><Relationship Id=\"styles\" Type=\"$rel/styles\" Target=\"styles.xml\"/></Relationships>").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").toString(Charsets.UTF_8).replace("</Types>",
            listOf("header1.xml" to "header", "footer1.xml" to "footer", "styles.xml" to "styles").joinToString("") { (path, type) -> "<Override PartName=\"/word/$path\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.$type+xml\"/>" } + "</Types>").toByteArray()
        ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }

    @Test fun realToolReplacesOnlyInspectedOmmlAndPreservesComplexDocumentAndSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val settings = GlobalContext.get().get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val store = GlobalContext.get().get<MaterialFiles>()
        val owner = settings.createLearnerProfile("B1c DOCX synthetic QA")
        val evidence = File(context.cacheDir, "b1c-omml-evidence").apply { mkdirs() }
        try {
            settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
            val input = File(evidence, "source.docx"); fixture(input)
            val source = store.import(owner, input.toUri(), "Complex original.docx")
            val originalBytes = store.verifiedFile(owner, source.id).readBytes()
            val tools = materialEditTools(store, owner)
            val inspected = tools.single { it.name == "inspect_material_edit" }.execute(buildJsonObject {
                put("id", source.id); put("selection", "{\"equations\":true,\"offset\":0,\"count\":10}")
            }).filterIsInstance<UIMessagePart.Text>().single().text.let { Json.parseToJsonElement(it).jsonObject }
            val equations = inspected.getValue("untrusted_structure").jsonObject.getValue("equations").jsonArray
            assertEquals(3, equations.size)
            val expected = equations[1].jsonObject.getValue("text").jsonPrimitive.content
            assertEquals("[公式 (x)^(2)]", expected)
            val spec = buildJsonObject { put("index", 2); put("expectedText", expected); put("equation", buildJsonObject {
                put("type", "fraction"); put("numerator", "x+1"); put("denominator", "y-1")
            }) }
            fun args(value: JsonObject = spec, hash: String = source.sha256) = buildJsonObject {
                put("id", source.id); put("expected_sha256", hash); put("operation", "replace_equation"); put("spec", value.toString()); put("title", "Editable equation version")
            }
            val edit = tools.single { it.name == "edit_material_file" }
            val result = edit.execute(args())
            assertTrue("Actual new file must be delivered, not a success string", result.any { it is UIMessagePart.Document })
            val artifact = store.list(owner).single { it.output && it.state == "SUCCEEDED" }
            assertEquals(source.id, artifact.sourceId)
            assertEquals(setOf(source.id, artifact.id), MaterialVersions.family(store.list(owner), artifact.id).map { it.id }.toSet())
            assertArrayEquals(originalBytes, store.verifiedFile(owner, source.id).readBytes())
            val actual = store.verifiedFile(owner, artifact.id)
            val before = OfficePackage.read(input); val after = OfficePackage.read(actual)
            assertEquals(before.keys, after.keys)
            before.filterKeys { it != "word/document.xml" }.forEach { (name, bytes) -> assertArrayEquals("Preserved package part $name", bytes, after.getValue(name)) }
            val beforeRoot = SafeOfficeXml.parse(before.getValue("word/document.xml"))
            val afterRoot = SafeOfficeXml.parse(after.getValue("word/document.xml"))
            val beforeMath = beforeRoot.getElementsByTagNameNS(mathNs, "oMath").item(1) as Element
            val afterMath = afterRoot.getElementsByTagNameNS(mathNs, "oMath").item(1) as Element
            assertEquals("[公式 (x+1)/(y-1)]", MaterialWordContent.text(afterMath))
            while (beforeMath.hasChildNodes()) beforeMath.removeChild(beforeMath.firstChild)
            while (afterMath.hasChildNodes()) afterMath.removeChild(afterMath.firstChild)
            assertTrue("Only selected OMML content may change", beforeRoot.isEqualNode(afterRoot))
            assertEquals(artifact.id, store.list(owner).single { it.output && it.state == "SUCCEEDED" }.id)
            assertTrue(edit.execute(args()).any { it is UIMessagePart.Document })
            suspend fun rejected(toolOwner: String = owner, value: JsonObject = spec, hash: String = source.sha256) {
                val reply = materialEditTools(store, toolOwner).single { it.name == "edit_material_file" }.execute(args(value, hash))
                assertFalse(reply.any { it is UIMessagePart.Document })
                assertEquals("FAILED", Json.parseToJsonElement(reply.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject.getValue("status").jsonPrimitive.content)
            }
            rejected(toolOwner = UUID.randomUUID().toString())
            rejected(hash = "0".repeat(64))
            rejected(value = JsonObject(spec + ("expectedText" to JsonPrimitive("wrong equation"))))
            rejected(value = JsonObject(spec + ("index" to JsonPrimitive(999))))
            rejected(value = JsonObject(spec + ("equation" to buildJsonObject { put("type", "fraction"); put("numerator", "x") })))
            assertEquals(setOf(artifact.id), store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet())
            actual.copyTo(File(evidence, "edited.docx"), overwrite = true)
            File(evidence, "result.json").writeText(buildJsonObject {
                put("status", "PASS"); put("owner", owner); put("sourceId", source.id); put("artifactId", artifact.id)
                put("sourceSha256", source.sha256); put("artifactSha256", artifact.sha256)
                put("formula", "[公式 (x+1)/(y-1)]"); put("otherPartsBytePreserved", true); put("unrelatedMainXmlPreserved", true)
                put("layout", "NOT_VERIFIED"); put("crossSoftware", "NOT_RUN")
            }.toString())
        } finally {
            me.rerere.rikkahub.service.restoreMemoryProfileFixture(original, settings)
            File(context.filesDir, "materials/$owner").deleteRecursively()
        }
    }

    @Test fun validUtf16AlternateMathPrefixWorksButFieldEquationIsNeverOverwritten() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "b1c-utf16-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(folder, "source.docx"); fixture(source)
            val parts = OfficePackage.read(source)
            val xml = parts.getValue("word/document.xml").toString(Charsets.UTF_8)
                .replace("xmlns:m=", "xmlns:math=").replace("m:", "math:")
            parts["word/document.xml"] = xml.replaceFirst("encoding=\"UTF-8\"", "encoding=\"UTF-16\"").toByteArray(Charsets.UTF_16)
            fun save(file: File) = ZipOutputStream(file.outputStream()).use { zip ->
                parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
            save(source)
            val sourceBytes = source.readBytes()
            val spec = buildJsonObject {
                put("index", 2); put("expectedText", "[公式 (x)^(2)]")
                put("equation", buildJsonObject { put("type", "text"); put("text", "x<&y") })
            }
            val output = File(folder, "edited.docx")
            MaterialEditor.replaceEquation(source, output, spec)
            val equation = MaterialEditor.wordEquations(output, 1, 1).getValue("equations").jsonArray.single().jsonObject
            assertEquals("[公式 x<&y]", equation.getValue("text").jsonPrimitive.content)
            assertArrayEquals(sourceBytes, source.readBytes())
            val root = SafeOfficeXml.parse(parts.getValue("word/document.xml"))
            val target = root.getElementsByTagNameNS(mathNs, "oMath").item(1)
            val field = root.ownerDocument.createElementNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "w:fldSimple")
            field.setAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "w:instr", "EQ")
            target.parentNode.replaceChild(field, target); field.appendChild(target)
            val serialized = ByteArrayOutputStream()
            javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(javax.xml.transform.dom.DOMSource(root.ownerDocument), javax.xml.transform.stream.StreamResult(serialized))
            parts["word/document.xml"] = serialized.toByteArray()
            val protected = File(folder, "field.docx"); save(protected)
            val inspected = MaterialEditor.wordEquations(protected, 1, 1).getValue("equations").jsonArray.single().jsonObject
            assertFalse(inspected.getValue("editable").jsonPrimitive.boolean)
            val rejected = File(folder, "rejected.docx")
            assertTrue(runCatching { MaterialEditor.replaceEquation(protected, rejected, spec) }.isFailure)
            assertFalse("A field rejection must not publish a replacement file", rejected.exists())
        } finally { folder.deleteRecursively() }
    }
}
