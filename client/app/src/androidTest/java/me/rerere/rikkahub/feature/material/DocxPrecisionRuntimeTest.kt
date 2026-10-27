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

/** Native domain tools and real immutable files; no model/remote service is substituted for editing. */
class DocxPrecisionRuntimeTest {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val m = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also { check(it.packageName == "org.hxsi.agent.nativeqa") }
    private fun save(file: File, parts: Map<String, ByteArray>) = ZipOutputStream(file.outputStream()).use { zip ->
        parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
    }
    private fun serialize(root: Element) = ByteArrayOutputStream().also {
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(javax.xml.transform.dom.DOMSource(root.ownerDocument), javax.xml.transform.stream.StreamResult(it))
    }.toByteArray()
    private suspend fun fixture(file: File, label: String) {
        val image = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream()
        try { image.eraseColor(android.graphics.Color.BLUE); check(image.compress(Bitmap.CompressFormat.PNG, 100, png)) } finally { image.recycle() }
        MaterialWriter.write(file, "docx", "Lecture $label first-page preserved body", listOf(listOf("Header", "Value"), listOf("Data $label", "7")), emptyList(),
            buildJsonObject { put("equations", buildJsonArray { add(buildJsonObject { put("type", "fraction"); put("numerator", "a"); put("denominator", "b") }) }) }, listOf(png.toByteArray()))
        val parts = OfficePackage.read(file); val root = SafeOfficeXml.parse(parts.getValue("word/document.xml")); val doc = root.ownerDocument
        val body = root.getElementsByTagNameNS(w, "body").item(0) as Element
        fun element(name: String) = doc.createElementNS(w, "w:$name")
        fun paragraph(text: String = ""): Element = element("p").apply {
            if (text.isNotEmpty()) appendChild(element("r").apply { appendChild(element("t").apply { textContent = text }) })
        }
        fun run(text: String, style: String? = null) = element("r").apply {
            style?.let { appendChild(element("rPr").apply { appendChild(element(it)) }) }
            appendChild(element("t").apply { setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve"); textContent = text })
        }
        fun field(kind: String) = element("r").apply { appendChild(element("fldChar").apply { setAttributeNS(w, "w:fldCharType", kind) }) }
        val beginning = paragraph().apply {
            appendChild(field("begin")); appendChild(element("r").apply { appendChild(element("instrText").apply { textContent = " TOC \\o \"1-3\" " }) }); appendChild(field("separate"))
        }
        val cached = paragraph("Cached TOC chapter 1")
        val cachedMath = paragraph().apply { appendChild(SafeOfficeXml.parse("<m:oMath xmlns:m=\"$m\"><m:r><m:t>cached x</m:t></m:r></m:oMath>".toByteArray()).let { doc.importNode(it, true) }) }
        val end = paragraph().apply { appendChild(field("end")) }
        for (node in listOf(end, cachedMath, cached, beginning)) body.insertBefore(node, body.firstChild)
        val section = body.getElementsByTagNameNS(w, "sectPr").item(0) as Element
        fun append(node: Element) { body.insertBefore(node, section) }
        fun pageBreak() = paragraph().apply { appendChild(element("r").apply { appendChild(element("br").apply { setAttributeNS(w, "w:type", "page") }) }) }
        append(pageBreak()); append(paragraph("Lecture $label edited page"))
        append(paragraph().apply {
            appendChild(element("pPr").apply { appendChild(element("pStyle").apply { setAttributeNS(w, "w:val", "B02Body") }) })
            appendChild(element("bookmarkStart").apply { setAttributeNS(w, "w:id", "10"); setAttributeNS(w, "w:name", "precision_$label") })
            appendChild(run("TARGET ", "b")); appendChild(run("old ", "i")); appendChild(run("phrase", "b")); appendChild(run(" END"))
            appendChild(element("bookmarkEnd").apply { setAttributeNS(w, "w:id", "10") })
            appendChild(doc.importNode(SafeOfficeXml.parse("<m:oMath xmlns:m=\"$m\"><m:r><m:t>k</m:t></m:r></m:oMath>".toByteArray()), true))
            appendChild(run(" TAIL"))
        })
        append(paragraph().apply { appendChild(doc.importNode(SafeOfficeXml.parse("<m:oMath xmlns:m=\"$m\"><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:oMath>".toByteArray()), true)) })
        append(pageBreak()); append(paragraph("Lecture $label last-page unchanged theorem")); append(paragraph("Representative source $label: hypotheses remain unchanged."))
        section.insertBefore(element("headerReference").apply { setAttributeNS(w, "w:type", "default"); setAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "r:id", "b02Header") }, section.firstChild)
        section.insertBefore(element("footerReference").apply { setAttributeNS(w, "w:type", "default"); setAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "r:id", "b02Footer") }, section.firstChild)
        parts["word/document.xml"] = serialize(root)
        parts["word/header1.xml"] = "<w:hdr xmlns:w=\"$w\"><w:p><w:r><w:t>Lecture $label header preserved</w:t></w:r></w:p></w:hdr>".toByteArray()
        parts["word/footer1.xml"] = "<w:ftr xmlns:w=\"$w\"><w:p><w:r><w:t>Footer $label preserved </w:t></w:r><w:fldSimple w:instr=\"PAGE\"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>".toByteArray()
        parts["word/styles.xml"] = "<w:styles xmlns:w=\"$w\"><w:style w:type=\"paragraph\" w:styleId=\"B02Body\"><w:name w:val=\"B02 body\"/><w:pPr><w:spacing w:after=\"180\"/></w:pPr></w:style></w:styles>".toByteArray()
        val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        parts["word/_rels/document.xml.rels"] = parts.getValue("word/_rels/document.xml.rels").toString(Charsets.UTF_8).replace("</Relationships>",
            "<Relationship Id=\"b02Header\" Type=\"$r/header\" Target=\"header1.xml\"/><Relationship Id=\"b02Footer\" Type=\"$r/footer\" Target=\"footer1.xml\"/><Relationship Id=\"b02Styles\" Type=\"$r/styles\" Target=\"styles.xml\"/></Relationships>").toByteArray()
        parts["[Content_Types].xml"] = parts.getValue("[Content_Types].xml").toString(Charsets.UTF_8).replace("</Types>",
            listOf("header1.xml" to "header", "footer1.xml" to "footer", "styles.xml" to "styles").joinToString("") { (name, type) -> "<Override PartName=\"/word/$name\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.$type+xml\"/>" } + "</Types>").toByteArray()
        save(file, parts)
    }
    private suspend fun fieldCase(kind: String) {
        val folder = File(context.cacheDir, "b02-field-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(folder, "source.docx"); fixture(source, "field")
            val target = File(folder, "must-not-exist.docx")
            when (kind) {
                "run" -> {
                    val runs = MaterialEditor.textRuns(source, "docx", 0, 0, 50).getValue("runs").jsonArray
                    val run = runs.single { it.jsonObject.getValue("text").jsonPrimitive.content == "Cached TOC chapter 1" }.jsonObject
                    assertFalse("A TOC field beginning in an earlier paragraph must protect its cached run", run.getValue("editable").jsonPrimitive.boolean)
                    assertTrue(runCatching { MaterialEditor.replaceTextRun(source, target, "docx", buildJsonObject { put("index", run.getValue("index")); put("expectedText", "Cached TOC chapter 1"); put("text", "corrupt TOC") }) }.isFailure)
                }
                "paragraph" -> {
                    val paragraphs = MaterialEditor.wordParagraphs(source, 0, 50).getValue("paragraphs").jsonArray
                    val paragraph = paragraphs.single { it.jsonObject.getValue("text").jsonPrimitive.content == "Cached TOC chapter 1" }.jsonObject
                    assertFalse(paragraph.getValue("editable").jsonPrimitive.boolean)
                    assertTrue(runCatching { MaterialEditor.replaceParagraph(source, target, buildJsonObject { put("index", paragraph.getValue("index")); put("expectedText", "Cached TOC chapter 1"); put("text", "corrupt TOC") }) }.isFailure)
                }
                else -> {
                    val equation = MaterialEditor.wordEquations(source, 0, 1).getValue("equations").jsonArray.single().jsonObject
                    assertFalse(equation.getValue("editable").jsonPrimitive.boolean)
                    assertTrue(runCatching { MaterialEditor.replaceEquation(source, target, buildJsonObject { put("index", 1); put("expectedText", "[公式 cached x]"); put("equation", buildJsonObject { put("type", "text"); put("text", "corrupt TOC") }) }) }.isFailure)
                }
            }
            assertFalse(target.exists())
        } finally { folder.deleteRecursively() }
    }
    @Test fun crossParagraphTocProtectsTextRun(): Unit = runBlocking { fieldCase("run") }
    @Test fun crossParagraphTocProtectsParagraph(): Unit = runBlocking { fieldCase("paragraph") }
    @Test fun crossParagraphTocProtectsEquation(): Unit = runBlocking { fieldCase("equation") }

    @Test fun twoRichLecturesProduceSourceBoundEditableWordVersionsWithoutChangingOtherStructures(): Unit = runBlocking {
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val store = k.get<MaterialFiles>(); val owner = settings.createLearnerProfile("B02 DOCX synthetic")
        val evidence = File(context.cacheDir, "b02-docx-evidence").apply { mkdirs() }
        try {
            settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
            val readTools = materialTools(store, owner, settings.settingsFlow.first { it.activeProfileId == owner },
                k.get<me.rerere.ai.provider.ProviderManager>(), me.rerere.ai.provider.Model())
            val reader = readTools.single { it.name == "read_material_text_page" }
            val sourceQuotes = ArrayList<String>()
            val sourceRows = ArrayList<List<String>>()
            val results = buildJsonArray {
                for (label in listOf("A", "B")) {
                    val sourcePath = File(evidence, "source-$label.docx"); fixture(sourcePath, label)
                    val source = store.import(owner, sourcePath.toUri(), "Lecture $label original.docx")
                    val before = store.verifiedFile(owner, source.id).readBytes()
                    val tools = materialEditTools(store, owner); val edit = tools.single { it.name == "edit_material_file" }
                    suspend fun inspect(item: MaterialFile, equations: Boolean = false): JsonObject {
                        val reply = tools.single { it.name == "inspect_material_edit" }.execute(buildJsonObject {
                            put("id", item.id); put("selection", buildJsonObject { put("offset", 0); put("count", 50); if (equations) put("equations", true) }.toString())
                        })
                        val inspected = Json.parseToJsonElement(reply.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
                        assertEquals(item.id, inspected.getValue("source_id").jsonPrimitive.content)
                        assertEquals(item.sha256, inspected.getValue("source_sha256").jsonPrimitive.content)
                        return inspected
                    }
                    val paragraphInspection = inspect(source)
                    val paragraph = paragraphInspection.getValue("untrusted_structure").jsonObject.getValue("paragraphs").jsonArray.single { it.jsonObject.getValue("text").jsonPrimitive.content == "TARGET old phrase END TAIL" }.jsonObject
                    assertFalse(paragraph.getValue("editable").jsonPrimitive.boolean)
                    assertTrue(paragraph.getValue("has_editable_text_ranges").jsonPrimitive.boolean)
                    fun readArgs(offset: Int, count: Int) = buildJsonObject {
                        put("id", source.id); put("expected_sha256", source.sha256); put("offset", offset.toString()); put("count", count.toString())
                    }
                    val fullSource = Json.parseToJsonElement(reader.execute(readArgs(0, 24000)).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
                    val quote = "Lecture $label last-page unchanged theorem"
                    val quoteOffset = fullSource.getValue("untrusted_file_data").jsonPrimitive.content.indexOf(quote)
                    assertTrue(quoteOffset >= 0)
                    val quoteArgs = readArgs(quoteOffset, quote.length)
                    val sourceQuote = Json.parseToJsonElement(reader.execute(quoteArgs).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
                    assertEquals(quote, sourceQuote.getValue("untrusted_file_data").jsonPrimitive.content)
                    val receipt = sourceQuote.getValue("source_receipt").jsonObject
                    assertEquals(source.id, receipt.getValue("material_id").jsonPrimitive.content)
                    val revalidation = reader.execute(JsonObject(quoteArgs + mapOf("expected_receipt" to JsonPrimitive(receipt.toString()), "expected_quote" to JsonPrimitive(quote))))
                    assertEquals("VERIFIED", Json.parseToJsonElement(revalidation.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject.getValue("receipt_revalidation").jsonObject.getValue("status").jsonPrimitive.content)
                    sourceQuotes.add(quote); sourceRows.add(listOf("Source $label", source.id, source.sha256))
                    val spec = buildJsonObject { put("index", paragraph.getValue("index")); put("expectedText", "TARGET old phrase END TAIL"); put("start", 7); put("end", 17); put("text", "new precise phrase") }
                    fun args(item: MaterialFile, operation: String, value: JsonObject) = buildJsonObject { put("id", item.id); put("expected_sha256", item.sha256); put("operation", operation); put("spec", value.toString()); put("title", "Lecture $label $operation") }
                    val reply = edit.execute(args(source, "replace_paragraph_text", spec))
                    assertTrue("Rich local paragraph replacement must deliver real Word, not reject a correct range", reply.any { it is UIMessagePart.Document })
                    val paragraphVersion = store.list(owner).single { it.sourceId == source.id && it.output && it.state == "SUCCEEDED" }
                    val equationInspection = inspect(paragraphVersion, equations = true)
                    val equations = equationInspection.getValue("untrusted_structure").jsonObject.getValue("equations").jsonArray
                    val target = equations.single { it.jsonObject.getValue("text").jsonPrimitive.content == "[公式 (x)^(2)]" }.jsonObject
                    val nested = Json.parseToJsonElement("""{"type":"fraction","numerator":{"type":"subsuperscript","base":"x","subscript":"n","exponent":"2"},"denominator":{"type":"radical","radicand":{"type":"row","items":["a","+","b"]}}}""").jsonObject
                    val equationSpec = buildJsonObject { put("index", target.getValue("index")); put("expectedText", target.getValue("text")); put("equation", nested) }
                    val finalReply = edit.execute(args(paragraphVersion, "replace_equation", equationSpec))
                    assertTrue(finalReply.any { it is UIMessagePart.Document })
                    val artifact = store.list(owner).single { it.sourceId == paragraphVersion.id && it.output && it.state == "SUCCEEDED" }
                    assertEquals(setOf(source.id, paragraphVersion.id, artifact.id), MaterialVersions.family(store.list(owner), artifact.id).map { it.id }.toSet())
                    assertArrayEquals(before, store.verifiedFile(owner, source.id).readBytes())
                    val finalFile = store.verifiedFile(owner, artifact.id); val originalParts = OfficePackage.read(sourcePath); val finalParts = OfficePackage.read(finalFile)
                    assertEquals(originalParts.keys, finalParts.keys)
                    originalParts.filterKeys { it != "word/document.xml" }.forEach { (name, bytes) -> assertArrayEquals(name, bytes, finalParts.getValue(name)) }
                    val originalRoot = SafeOfficeXml.parse(originalParts.getValue("word/document.xml")); val finalRoot = SafeOfficeXml.parse(finalParts.getValue("word/document.xml"))
                    val originalTextNodes = originalRoot.getElementsByTagNameNS(w, "t"); val finalTextNodes = finalRoot.getElementsByTagNameNS(w, "t")
                    assertEquals(originalTextNodes.length, finalTextNodes.length)
                    for (n in 0 until originalTextNodes.length) {
                        if (originalTextNodes.item(n).textContent in setOf("old ", "phrase")) { originalTextNodes.item(n).textContent = "masked"; finalTextNodes.item(n).textContent = "masked" }
                    }
                    val oldMath = originalRoot.getElementsByTagNameNS(m, "oMath").item(target.getValue("index").jsonPrimitive.int - 1) as Element
                    val newMath = finalRoot.getElementsByTagNameNS(m, "oMath").item(target.getValue("index").jsonPrimitive.int - 1) as Element
                    assertEquals("[公式 ((x)_(n)^(2))/(root[2](a+b))]", MaterialWordContent.text(newMath))
                    while (oldMath.hasChildNodes()) oldMath.removeChild(oldMath.firstChild)
                    while (newMath.hasChildNodes()) newMath.removeChild(newMath.firstChild)
                    assertTrue("Every unrelated main-document node, style, field and relation must survive", originalRoot.isEqualNode(finalRoot))
                    val publishedIds = store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet()
                    assertTrue(edit.execute(args(paragraphVersion, "replace_equation", equationSpec)).any { it is UIMessagePart.Document })
                    assertEquals(publishedIds, store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet())
                    val invalid = listOf(JsonObject(spec + ("expectedText" to JsonPrimitive("wrong"))), JsonObject(spec + ("end" to JsonPrimitive(9999))),
                        JsonObject(spec + mapOf("start" to JsonPrimitive(0), "end" to JsonPrimitive(26))))
                    for (bad in invalid) assertFalse(edit.execute(args(source, "replace_paragraph_text", bad)).any { it is UIMessagePart.Document })
                    val wrongHash = JsonObject(args(source, "replace_paragraph_text", spec) + ("expected_sha256" to JsonPrimitive("0".repeat(64))))
                    assertFalse(edit.execute(wrongHash).any { it is UIMessagePart.Document })
                    assertFalse(materialEditTools(store, UUID.randomUUID().toString()).single { it.name == "edit_material_file" }.execute(args(source, "replace_paragraph_text", spec)).any { it is UIMessagePart.Document })
                    assertEquals(publishedIds, store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet())
                    store.verifiedFile(owner, paragraphVersion.id).copyTo(File(evidence, "paragraph-$label.docx"), overwrite = true)
                    finalFile.copyTo(File(evidence, "edited-$label.docx"), overwrite = true)
                    add(buildJsonObject { put("label", label); put("sourceId", source.id); put("sourceSha256", source.sha256); put("parentId", paragraphVersion.id); put("parentSha256", paragraphVersion.sha256); put("artifactId", artifact.id); put("artifactSha256", artifact.sha256); put("paragraphSpec", spec); put("equationSpec", equationSpec); put("paragraphInspection", paragraphInspection); put("equationInspection", equationInspection); put("sourceQuote", sourceQuote) })
                }
            }
            val create = readTools.single { it.name == "create_material_file" }
            val content = buildJsonObject {
                put("body", "B02 synthetic domain notes; not a Chat/model synthesis or mathematical proof.\n" + sourceQuotes.joinToString("\n"))
                put("rows", JsonArray((listOf(listOf("Label", "Source version", "SHA256")) + sourceRows).map { JsonArray(it.map(::JsonPrimitive)) }))
                put("equations", buildJsonArray { add(Json.parseToJsonElement("""{"type":"radical","radicand":{"type":"fraction","numerator":{"type":"subscript","base":"a","subscript":"n"},"denominator":"b"}}""")) })
            }
            val createArgs = buildJsonObject { put("format", "docx"); put("title", "B02 two-lecture domain notes"); put("content", content.toString()) }
            assertTrue(create.execute(createArgs).any { it is UIMessagePart.Document })
            val created = store.list(owner).single { it.name == "B02 two-lecture domain notes.docx" && it.state == "SUCCEEDED" }
            val createdFile = store.verifiedFile(owner, created.id)
            assertEquals("[公式 root[2](((a)_(n))/(b))]", MaterialEditor.wordEquations(createdFile, 0, 1).getValue("equations").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            val publishedIds = store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet()
            assertTrue(create.execute(createArgs).any { it is UIMessagePart.Document })
            assertEquals(publishedIds, store.list(owner).filter { it.output && it.state == "SUCCEEDED" }.map { it.id }.toSet())
            createdFile.copyTo(File(evidence, "created-notes.docx"), overwrite = true)
            File(evidence, "created-result.json").writeText(buildJsonObject { put("id", created.id); put("sha256", created.sha256); put("sourceDocuments", results); put("chatStory", "NOT_RUN") }.toString())
            File(evidence, "result.json").writeText(buildJsonObject { put("status", "PASS"); put("owner", owner); put("documents", results); put("otherPartsBytePreserved", true); put("unrelatedMainXmlPreserved", true); put("layout", "NOT_VERIFIED"); put("crossSoftware", "NOT_RUN") }.toString())
        } finally {
            me.rerere.rikkahub.service.restoreMemoryProfileFixture(original, settings)
            File(context.filesDir, "materials/$owner").deleteRecursively()
        }
    }
}
