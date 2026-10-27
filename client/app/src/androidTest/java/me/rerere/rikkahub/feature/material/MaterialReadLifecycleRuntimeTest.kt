package me.rerere.rikkahub.feature.material

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

class MaterialReadLifecycleRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "org.hxsi.agent.nativeqa")
    }
    private suspend fun fixture(store: MaterialFiles, owner: String, body: String = "REAL LIBRARY SOURCE\nOriginal equation x²") =
        store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "教材.md", "md")) { it.writeText(body); "Synthetic library source" }
    private fun cleanup(owner: String) {
        File(context.filesDir, "materials/$owner").deleteRecursively()
        File(context.cacheDir, "material-evidence/$owner").deleteRecursively()
    }
    private fun archive(label: String, result: JsonObject) {
        File(context.cacheDir, "b04a-library-evidence").apply { mkdirs() }.resolve("$label.json").writeText(result.toString())
    }

    @Test fun ordinaryToolsIgnoreLegacyForgedBodyAndCoverageAcrossStoreReopen(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val body = "REAL LIBRARY SOURCE\nOriginal equation x²"
            val item = fixture(store, owner, body)
            val original = store.verifiedFile(owner, item.id).readBytes()
            File(context.filesDir, "materials/$owner/${item.id}.read-v6.cache").writeText(
                Json.encodeToString(MaterialContent("FORGED_DISK_BODY", "All 999 pages completely recognized", 999, 999)))
            val k = GlobalContext.get(); val settings = k.get<SettingsStore>().settingsFlow.first { !it.init }
            val model = Model(modelId = "b04a-no-provider")
            val replies = mutableListOf<JsonObject>()
            for (name in listOf("read_material_file", "read_material_text_page")) {
                val tool = materialTools(MaterialFiles(context), owner, settings, k.get<ProviderManager>(), model).single { it.name == name }
                val result = tool.execute(buildJsonObject {
                    put("id", item.id)
                    if (name == "read_material_text_page") { put("offset", "0"); put("count", "24000") }
                }).filterIsInstance<UIMessagePart.Text>().single().text.let { Json.parseToJsonElement(it).jsonObject }
                replies += result
            }
            archive("ordinary-tools", buildJsonObject {
                put("owner", owner); put("id", item.id); put("source_sha256", item.sha256)
                put("expected_body", body); put("actual_replies", JsonArray(replies))
            })
            for (reply in replies) {
                assertEquals(body, reply.getValue("untrusted_file_data").jsonPrimitive.content)
                assertFalse(reply.getValue("coverage").jsonPrimitive.content.contains("999"))
                assertFalse(reply.containsKey("source_receipt"))
            }
            assertEquals(body, MaterialFiles(context).readContent(owner, item.id).text)
            assertArrayEquals(original, store.verifiedFile(owner, item.id).readBytes())
        } finally { cleanup(owner) }
    }

    @Test fun warmReadRejectsTamperedBytesAndOtherOwnerButKeepsImmutableVersions(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = fixture(store, owner)
            val body = store.readContent(owner, item.id)
            val original = store.verifiedFile(owner, item.id).readBytes()
            assertEquals(body, store.readContent(owner, item.id))
            assertTrue(runCatching { store.readContent(other, item.id) }.isFailure)
            val newer = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "教材新版.md", "md", output = true, sourceId = item.id)) {
                it.writeText("NEW VERSION"); "Synthetic immutable version"
            }
            assertEquals("NEW VERSION", store.readContent(owner, newer.id).text)
            assertEquals(body, store.readContent(owner, item.id))
            store.file(item).appendText("CORRUPTED")
            assertTrue(runCatching { store.readContent(owner, item.id) }.isFailure)
            assertEquals("NEW VERSION", store.readContent(owner, newer.id).text)
            store.file(item).writeBytes(original)
            assertEquals(body, store.readContent(owner, item.id))
            assertArrayEquals(original, store.verifiedFile(owner, item.id).readBytes())
        } finally { cleanup(owner); cleanup(other) }
    }

    @Test fun deletedMetadataCannotBeResurrectedBySuccessFailureOrCancellation(): Unit = runBlocking {
        for (mode in listOf("success", "failure", "cancel")) {
            val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
            try {
                val item = fixture(store, owner)
                val original = store.verifiedFile(owner, item.id).readBytes()
                val result = runCatching {
                    store.readContentUsing(owner, item.id) { frozen, extension ->
                        assertArrayEquals(original, frozen.readBytes())
                        check(File(context.filesDir, "materials/$owner/${item.id}.meta.json").delete())
                        when (mode) {
                            "failure" -> error("Synthetic extractor failure after deletion")
                            "cancel" -> throw CancellationException("Synthetic extraction cancellation after deletion")
                            else -> MaterialReader.read(frozen, extension)
                        }
                    }
                }
                assertTrue("$mode must not return stale content", result.isFailure)
                if (mode == "cancel") assertTrue(result.exceptionOrNull() is CancellationException)
                assertTrue("$mode must not recreate metadata", MaterialFiles(context).list(owner).isEmpty())
                assertArrayEquals(original, store.file(item).readBytes())
                assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            } finally { cleanup(owner) }
        }
    }

    @Test fun replacedVersionKeepsItsNewStateAndCannotBorrowOldExtraction(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = fixture(store, owner)
            var replacement: MaterialFile? = null
            val result = runCatching {
                store.readContentUsing(owner, item.id) { frozen, extension ->
                    store.file(item).writeText("REPLACED SOURCE")
                    replacement = item.copy(size = store.file(item).length(), sha256 = MaterialFiles.digest(store.file(item)),
                        sourceId = UUID.randomUUID().toString(), processingState = "NOT_STARTED", detail = "Replacement not yet extracted",
                        symbolCorrection = "New source correction", symbolSourceSha256 = MaterialFiles.digest(store.file(item)))
                    store.record(requireNotNull(replacement))
                    MaterialReader.read(frozen, extension)
                }
            }
            assertTrue(result.isFailure)
            assertEquals(replacement, store.get(owner, item.id))
            assertEquals("REPLACED SOURCE", store.readContent(owner, item.id).text)
            assertEquals("New source correction", store.get(owner, item.id).symbolCorrection)
        } finally { cleanup(owner) }
    }

    @Test fun symbolCorrectionSurvivesSuccessfulAndFailedExtraction(): Unit = runBlocking {
        for (fail in listOf(false, true)) {
            val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
            try {
                val item = fixture(store, owner)
                val result = runCatching {
                    store.readContentUsing(owner, item.id) { frozen, extension ->
                        store.confirmSymbol(owner, item.id, item.sha256, "Confirmed x², not x³")
                        if (fail) error("Synthetic extractor failure")
                        MaterialReader.read(frozen, extension)
                    }
                }
                assertEquals(!fail, result.isSuccess)
                val current = store.get(owner, item.id)
                assertEquals("Confirmed x², not x³", current.symbolCorrection)
                assertEquals(item.sha256, current.symbolSourceSha256)
                assertEquals(if (fail) "FAILED" else "SUCCEEDED", current.processingState)
                assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, item.id)))
            } finally { cleanup(owner) }
        }
    }

    @Test fun cancelledJobPreservesSourceAndCorrectionWithoutPublishingSuccess(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = fixture(store, owner); val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.IO) {
                store.readContentUsing(owner, item.id) { frozen, extension ->
                    MaterialReader.read(frozen, extension)
                    store.confirmSymbol(owner, item.id, item.sha256, "Verified symbol")
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(10_000) { started.await() }
            job.cancelAndJoin()
            val current = store.get(owner, item.id)
            assertEquals("CANCELLED", current.processingState)
            assertEquals("Verified symbol", current.symbolCorrection)
            assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, item.id)))
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
            assertEquals("REAL LIBRARY SOURCE\nOriginal equation x²", MaterialFiles(context).readContent(owner, item.id).text)
        } finally { cleanup(owner) }
    }

    @Test fun warmReadUsesReplacedSourceAndValidUtf8SurvivesInvalidInput(): Unit = runBlocking {
        val owner = UUID.randomUUID().toString(); val store = MaterialFiles(context)
        try {
            val item = fixture(store, owner)
            store.readContent(owner, item.id)
            store.file(item).writeText("REAL REPLACEMENT\nCorrected equation x³")
            store.record(store.get(owner, item.id).copy(sha256 = MaterialFiles.digest(store.file(item)), size = store.file(item).length()))
            val replacement = store.readContent(owner, item.id)
            assertEquals("REAL REPLACEMENT\nCorrected equation x³", replacement.text)
            val bad = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "broken.md", "md")) {
                it.writeBytes(byteArrayOf(0xC3.toByte(), 0x28)); "Synthetic invalid UTF8"
            }
            assertTrue(runCatching { store.readContent(owner, bad.id) }.isFailure)
            assertEquals("FAILED", store.get(owner, bad.id).processingState)
            assertEquals(replacement, MaterialFiles(context).readContent(owner, item.id))
            assertTrue(File(context.cacheDir, "material-evidence/$owner").listFiles().orEmpty().isEmpty())
        } finally { cleanup(owner) }
    }
}
