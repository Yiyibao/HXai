package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class SourceReceiptRuntimeTest {
    @Test fun actualVersionBoundRangeIgnoresPoisonedCacheAndRejectsWrongOwnerHashOrUnicodeBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }; val owner = settings.createLearnerProfile("B2a synthetic source QA")
        val store = koin.get<MaterialFiles>(); val evidence = File(context.cacheDir, "b2a-source-evidence").apply { mkdirs() }
        try {
            settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
            val text = "REAL SOURCE \uD83D\uDE00 [物理页 999：正文标记不是定位证据]\n" + "Textbook evidence. ".repeat(2000) + "TAIL_QUOTE"
            val source = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Source.md", "md")) { it.writeText(text); "Synthetic source" }
            val originalBytes = store.verifiedFile(owner, source.id).readBytes()
            val cache = File(context.filesDir, "materials/$owner/${source.id}.read-v6.cache")
            cache.writeText(Json.encodeToString(MaterialContent("FORGED_CACHE", "synthetic poisoned cache")))
            val model = Model(modelId = "b2-no-model-call", displayName = "No model request")
            fun reader(profile: String = owner) = materialTools(store, profile, settings.settingsFlow.value, koin.get<ProviderManager>(), model).single { it.name == "read_material_text_page" }
            fun args(item: MaterialFile = source, offset: Int = 0, count: Int = 24000, hash: String = item.sha256) = buildJsonObject {
                put("id", item.id); put("offset", offset.toString()); put("count", count.toString()); put("expected_sha256", hash)
            }
            suspend fun read(input: JsonObject, profile: String = owner) = reader(profile).execute(input).filterIsInstance<UIMessagePart.Text>().single().text.let { Json.parseToJsonElement(it).jsonObject }
            val page = read(args(count = 100))
            assertEquals(text.substring(0, 100), page.getValue("untrusted_file_data").jsonPrimitive.content)
            val receipt = page.getValue("source_receipt").jsonObject
            assertEquals("VERIFIED", receipt.getValue("status").jsonPrimitive.content)
            assertEquals("extracted_text_range", receipt.getValue("scope").jsonPrimitive.content)
            assertEquals(owner, receipt.getValue("owner").jsonPrimitive.content)
            assertEquals(source.id, receipt.getValue("material_id").jsonPrimitive.content)
            assertEquals(source.id, receipt.getValue("version_id").jsonPrimitive.content)
            assertEquals(source.sha256, receipt.getValue("source_sha256").jsonPrimitive.content)
            val locator = receipt.getValue("locator").jsonObject
            assertEquals("extracted_text", locator.getValue("kind").jsonPrimitive.content)
            assertEquals("utf16", locator.getValue("unit").jsonPrimitive.content)
            assertEquals(0, locator.getValue("start").jsonPrimitive.int); assertEquals(100, locator.getValue("end").jsonPrimitive.int)
            assertFalse(locator.containsKey("physical_page") || locator.containsKey("printed_page") || locator.containsKey("slide"))
            val quote = text.substring(0, 100)
            val quoteHash = MessageDigest.getInstance("SHA-256").digest(quote.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(quoteHash, receipt.getValue("quote_sha256").jsonPrimitive.content)
            val tail = read(args(offset = text.length - 10, count = 10))
            assertEquals("TAIL_QUOTE", tail.getValue("untrusted_file_data").jsonPrimitive.content)
            assertFalse(tail.containsKey("next_offset"))
            suspend fun refused(input: JsonObject, profile: String = owner) {
                val reply = read(input, profile)
                assertEquals("FAILED", reply.getValue("status").jsonPrimitive.content)
                assertFalse(reply.containsKey("source_receipt"))
            }
            refused(args(hash = "0".repeat(64))); refused(args(), UUID.randomUUID().toString())
            val emoji = text.indexOf("\uD83D\uDE00")
            refused(args(offset = emoji + 1, count = 10)); refused(args(offset = emoji, count = 1))
            val originalFile = store.file(source); originalFile.appendText("source changed")
            refused(args()); originalFile.writeBytes(originalBytes)
            val newerText = text.replace("REAL SOURCE", "NEW VERSION")
            val newer = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Source version.md", "md", output = true, sourceId = source.id)) { it.writeText(newerText); "New immutable source version" }
            val newerPage = read(args(item = newer, count = 100))
            assertEquals(newer.id, newerPage.getValue("source_receipt").jsonObject.getValue("version_id").jsonPrimitive.content)
            assertEquals(source.id, newerPage.getValue("source_receipt").jsonObject.getValue("parent_id").jsonPrimitive.content)
            assertEquals(newerText.substring(0, 100), newerPage.getValue("untrusted_file_data").jsonPrimitive.content)
            assertEquals(text.substring(0, 100), read(args(count = 100)).getValue("untrusted_file_data").jsonPrimitive.content)
            assertArrayEquals(originalBytes, store.verifiedFile(owner, source.id).readBytes())
            assertEquals(setOf(source.id, newer.id), store.list(owner).map { it.id }.toSet())
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            File(evidence, "receipt.json").writeText(receipt.toString())
            File(evidence, "result.json").writeText(buildJsonObject { put("status", "PASS"); put("owner", owner); put("sourceId", source.id); put("sourceSha256", source.sha256); put("newVersionId", newer.id); put("cacheIgnored", true); put("originalPreserved", true); put("physicalPageClaimed", false) }.toString())
        } finally {
            me.rerere.rikkahub.service.restoreMemoryProfileFixture(original, settings)
            File(context.filesDir, "materials/$owner").deleteRecursively()
            File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
        }
    }

    @Test fun frozenDocxAndRealPdfRangesRemainUsableWhileInvalidUtf8FailsWithoutReceiptOrTempLeak() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get(); val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        val settings = koin.get<SettingsStore>().settingsFlow.first { !it.init }
        val model = Model(modelId = "b2-no-model-call", displayName = "No model request")
        val tool = materialTools(store, owner, settings, koin.get<ProviderManager>(), model).single { it.name == "read_material_text_page" }
        suspend fun read(item: MaterialFile, bound: Boolean = true): JsonObject {
            val reply = tool.execute(buildJsonObject {
                put("id", item.id); put("offset", "0"); put("count", "24000")
                if (bound) put("expected_sha256", item.sha256)
            }).filterIsInstance<UIMessagePart.Text>().single().text
            return Json.parseToJsonElement(reply).jsonObject
        }
        try {
            val word = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Quote.docx", "docx")) { output ->
                MaterialWriter.write(output, "docx", "EXACT DOCX SOURCE", emptyList(), emptyList(), buildJsonObject {
                    put("equations", buildJsonArray { add(buildJsonObject { put("type", "fraction"); put("numerator", "a"); put("denominator", "b") }) })
                })
            }
            val wordReply = read(word)
            assertTrue(wordReply.getValue("untrusted_file_data").jsonPrimitive.content.contains("EXACT DOCX SOURCE"))
            assertTrue(wordReply.getValue("untrusted_file_data").jsonPrimitive.content.contains("[公式 (a)/(b)]"))
            assertEquals(word.sha256, wordReply.getValue("source_receipt").jsonObject.getValue("source_sha256").jsonPrimitive.content)
            val pdf = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Quote.pdf", "pdf")) { output ->
                val document = android.graphics.pdf.PdfDocument()
                try {
                    for (number in 1..2) {
                        val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(400, 500, number).create())
                        page.canvas.drawText("REAL PDF PAGE $number EVIDENCE", 25f, 60f, android.graphics.Paint().apply { textSize = 18f })
                        document.finishPage(page)
                    }
                    output.outputStream().use(document::writeTo)
                } finally { document.close() }
                "Synthetic real PDF"
            }
            val pdfReply = read(pdf)
            assertEquals(2, pdfReply.getValue("total_pages").jsonPrimitive.int)
            val pdfText = pdfReply.getValue("untrusted_file_data").jsonPrimitive.content
            assertTrue(pdfText.contains("REAL PDF PAGE 1 EVIDENCE") && pdfText.contains("REAL PDF PAGE 2 EVIDENCE"))
            assertEquals("extracted_text", pdfReply.getValue("source_receipt").jsonObject.getValue("locator").jsonObject.getValue("kind").jsonPrimitive.content)
            val legacy = read(word, bound = false)
            assertTrue(legacy.getValue("untrusted_file_data").jsonPrimitive.content.contains("EXACT DOCX SOURCE"))
            assertFalse("Old three-parameter read must not invent a verified receipt", legacy.containsKey("source_receipt"))
            val malformed = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Invalid.md", "md")) { it.writeBytes(byteArrayOf(0xC3.toByte(), 0x28)); "Synthetic invalid UTF8" }
            val failure = read(malformed)
            assertEquals("FAILED", failure.getValue("status").jsonPrimitive.content)
            assertFalse(failure.containsKey("source_receipt"))
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
        } finally {
            File(context.filesDir, "materials/$owner").deleteRecursively()
            File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
        }
    }
}
