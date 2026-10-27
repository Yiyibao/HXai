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
import java.util.UUID

class SourceRevalidationRuntimeTest {
    @Test fun realMdCitationRevalidatesBothFactsAndQuoteWithoutBreakingImmutableHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val koin = GlobalContext.get(); val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        val settings = koin.get<SettingsStore>().settingsFlow.first { !it.init }
        fun reader(profile: String = owner) = materialTools(store, profile, settings, koin.get<ProviderManager>(), Model(modelId = "b2-no-model-call", displayName = "No model request")).single { it.name == "read_material_text_page" }
        suspend fun read(args: JsonObject, profile: String = owner): JsonObject = Json.parseToJsonElement(reader(profile).execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
        try {
            val text = "REAL \uD83D\uDE00 SOURCE SAME SAME TAIL"
            val source = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Citation.md", "md")) { it.writeText(text); "Synthetic citation" }
            val initial = buildJsonObject { put("id", source.id); put("offset", "0"); put("count", "24000"); put("expected_sha256", source.sha256) }
            val original = read(initial); val receipt = original.getValue("source_receipt").jsonObject
            fun args(candidate: JsonObject = receipt, quote: String = text, id: String = source.id, hash: String = source.sha256) = JsonObject(initial + mapOf("id" to JsonPrimitive(id), "expected_sha256" to JsonPrimitive(hash), "expected_receipt" to JsonPrimitive(candidate.toString()), "expected_quote" to JsonPrimitive(quote)))
            suspend fun refused(input: JsonObject, profile: String = owner) {
                val result = read(input, profile)
                assertEquals("FAILED", result["status"]?.jsonPrimitive?.content)
                assertFalse(result.containsKey("source_receipt") || result.containsKey("receipt_revalidation"))
            }
            assertEquals(text, read(args()).getValue("untrusted_file_data").jsonPrimitive.content)
            refused(args(quote = "MODEL STITCHED INVENTED CITATION"))
            for ((key, value) in listOf(
                "owner" to JsonPrimitive(UUID.randomUUID().toString()),
                "material_id" to JsonPrimitive(UUID.randomUUID().toString()),
                "version_id" to JsonPrimitive(UUID.randomUUID().toString()),
                "parent_id" to JsonPrimitive(UUID.randomUUID().toString()),
                "source_sha256" to JsonPrimitive("0".repeat(64)),
                "quote_sha256" to JsonPrimitive("0".repeat(64)),
                "contractVersion" to JsonPrimitive(1),
                "extraction_contract" to JsonPrimitive("material_reader_text_v1"),
                "scope" to JsonPrimitive("mathematical_proof")
            )) refused(args(JsonObject(receipt + (key to value))))
            val locator = receipt.getValue("locator").jsonObject
            refused(args(JsonObject(receipt + ("locator" to JsonObject(locator + ("start" to JsonPrimitive(1)))))))
            refused(args(JsonObject(receipt - "quote_sha256")))
            refused(args(JsonObject(receipt + ("capability" to JsonPrimitive("admin")))))
            refused(args(), UUID.randomUUID().toString()); refused(args(hash = "0".repeat(64)))
            refused(JsonObject(args() - "expected_quote")); refused(JsonObject(args() - "expected_receipt")); refused(JsonObject(args() - "expected_sha256"))
            refused(JsonObject(args() + ("expected_receipt" to JsonPrimitive("{bad JSON"))))
            refused(JsonObject(args() + ("expected_receipt" to JsonPrimitive("x".repeat(32769)))))
            refused(args(quote = "")); refused(args(quote = "x".repeat(24001)))
            File(context.filesDir, "materials/$owner/${source.id}.read-v6.cache").writeText(Json.encodeToString(MaterialContent("MODEL STITCHED INVENTED CITATION", "poisoned synthetic cache")))
            assertEquals(text, read(args()).getValue("untrusted_file_data").jsonPrimitive.content)
            val bytes = store.verifiedFile(owner, source.id).readBytes()
            store.file(source).appendText("changed source"); refused(args()); store.file(source).writeBytes(bytes)
            val newer = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Citation new.md", "md", output = true, sourceId = source.id)) { it.writeText("NEW IMMUTABLE SOURCE"); "Synthetic child" }
            refused(args(id = newer.id, hash = newer.sha256))
            assertEquals(text, read(args()).getValue("untrusted_file_data").jsonPrimitive.content)
            assertArrayEquals(bytes, store.verifiedFile(owner, source.id).readBytes())
            assertEquals(source.id, store.get(owner, newer.id).sourceId)
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            val evidence = File(context.cacheDir, "b2c-source-evidence").apply { mkdirs() }
            File(evidence, "md-revalidated.json").writeText(read(args()).toString())
        } finally {
            File(context.filesDir, "materials/$owner").deleteRecursively()
            File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
        }
    }

    @Test fun actualPdfPageLocationsCannotBeAlteredToAuthenticateTheWrongPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get(); val store = MaterialFiles(context); val owner = UUID.randomUUID().toString()
        val settings = koin.get<SettingsStore>().settingsFlow.first { !it.init }
        val tool = materialTools(store, owner, settings, koin.get<ProviderManager>(), Model(modelId = "b2-no-model-call", displayName = "No model request")).single { it.name == "read_material_text_page" }
        suspend fun read(args: JsonObject) = Json.parseToJsonElement(tool.execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
        try {
            val source = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "Citation.pdf", "pdf")) { output ->
                val doc = android.graphics.pdf.PdfDocument()
                try {
                    val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(400, 500, 1).create())
                    page.canvas.drawText("REAL PDF CITATION", 25f, 60f, android.graphics.Paint().apply { textSize = 18f })
                    doc.finishPage(page); output.outputStream().use(doc::writeTo)
                } finally { doc.close() }
                "Synthetic PDF citation"
            }
            val initial = buildJsonObject { put("id", source.id); put("offset", "0"); put("count", "24000"); put("expected_sha256", source.sha256) }
            val fresh = read(initial); val quote = fresh.getValue("untrusted_file_data").jsonPrimitive.content; val receipt = fresh.getValue("source_receipt").jsonObject
            val location = receipt.getValue("source_locations").jsonArray.single().jsonObject
            val forged = JsonObject(receipt + ("source_locations" to JsonArray(listOf(JsonObject(location + ("number" to JsonPrimitive(999)))))))
            fun args(candidate: JsonObject) = JsonObject(initial + mapOf("expected_receipt" to JsonPrimitive(candidate.toString()), "expected_quote" to JsonPrimitive(quote)))
            val failure = read(args(forged))
            assertEquals("FAILED", failure["status"]?.jsonPrimitive?.content); assertFalse(failure.containsKey("source_receipt"))
            val valid = read(args(receipt)); assertEquals(quote, valid.getValue("untrusted_file_data").jsonPrimitive.content)
            assertEquals(1, valid.getValue("source_receipt").jsonObject.getValue("source_locations").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int)
            val evidence = File(context.cacheDir, "b2c-source-evidence").apply { mkdirs() }
            File(evidence, "pdf-revalidated.json").writeText(valid.toString())
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
        } finally {
            File(context.filesDir, "materials/$owner").deleteRecursively()
            File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
        }
    }
}
