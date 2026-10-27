package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.ProviderRequestGuard
import me.rerere.ai.core.ProviderRequestAccounting
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.feature.agent.AgentExecution
import me.rerere.rikkahub.feature.agent.LocalModelServer
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Real input-transform/physical HTTP/lease/Room/cache chain; synthetic pixels and localhost only. */
@RunWith(AndroidJUnit4::class)
class OcrSourceSuppressionTest {
    @Test fun healthyImageReachesMainModel() = scenario("healthy")
    @Test fun healthyCacheAvoidsSecondOcrRequest() = scenario("cache")
    @Test fun newMessageWithSameImageHasItsOwnVerifiedCache() = scenario("cache-new-message")
    @Test fun oldCacheWithoutVerifiedSourceIsPreservedButNotReused() = scenario("old-cache")
    @Test fun missingFileCannotBecomeNormalOcrErrorText() = scenario("pre-missing")
    @Test fun deletionAtPhysicalAdmissionPreventsOcrAndUsage() = scenario("physical-deleted")
    @Test fun legacyProtectionAtPhysicalAdmissionPreventsOcrAndUsage() = scenario("physical-legacy")
    @Test fun changedFileAtPhysicalAdmissionPreventsOcrAndUsage() = scenario("physical-file")
    @Test fun deletedFileAtPhysicalAdmissionPreventsOcrAndUsage() = scenario("physical-missing")
    @Test fun deletionDuringOcrDoesNotPublishCache() = scenario("late-deleted")
    @Test fun legacyProtectionDuringOcrDoesNotPublishCache() = scenario("late-legacy")
    @Test fun changedFileDuringOcrDoesNotPublishCacheOrReachMain() = scenario("late-file")
    @Test fun deletedFileDuringOcrDoesNotPublishCacheOrReachMain() = scenario("late-missing")
    @Test fun anotherOwnersTombstoneDoesNotRejectHealthyInput() = scenario("other-owner")
    @Test fun healthyNewSourceWithSameTextRemainsAllowed() = scenario("fresh")
    @Test fun deletedHistoricalImageIsNotRecognized() = scenario("old-deleted")
    @Test fun providerFailureIsSanitizedAndUnknownDoesNotContinue() = scenario("provider-failure")

    private fun scenario(mode: String): Unit = runBlocking {
        val k = GlobalContext.get()
        val context = k.get<Context>()
        val settingsStore = k.get<SettingsStore>()
        val original = withTimeout(20_000) { settingsStore.settingsFlow.first { !it.init } }
        val owner = original.activeProfileId
        val memory = k.get<LearningMemoryStore>()
        val previous = memory.read(owner)
        val unique = Uuid.random().toString()
        val text = "OCR_VERIFIED_$unique"
        val file = File(context.cacheDir, "ocr-source-$unique.png")
        fun pixels(color: Int) {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        pixels(android.graphics.Color.RED)
        val image = UIMessagePart.Image(file.toUri().toString())
        val message = UIMessage.user("Read this synthetic image").copy(parts = listOf(UIMessagePart.Text("Read this synthetic image"), image))
        val marker = memory.fingerprint(owner, "message:${message.id}")
        suspend fun mutate(kind: String) {
            when (kind) {
                "deleted" -> memory.change(owner) { it.copy(suppressedSources = it.suppressedSources + marker) to Unit }
                "legacy" -> memory.change(owner) { it.copy(schemaVersion = 2, recoveryBlocked = true,
                    legacySuppressedSources = setOf("SYNTHETIC_UNMAPPED_$unique")) to Unit }
                "file" -> pixels(android.graphics.Color.BLUE)
                "missing" -> check(file.delete())
            }
        }
        val server = LocalModelServer(status = { _, body ->
            if (mode == "provider-failure" && "qa-ocr-$unique" in body) 400 else 200
        }) { body ->
            val isOcr = Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content == "qa-ocr-$unique"
            if (isOcr && mode.startsWith("late-")) runBlocking { mutate(mode.removePrefix("late-")) }
            if (mode == "provider-failure" && isOcr) """{"error":{"message":"PRIVATE_OCR_SECRET"}}"""
            else LocalModelServer.completion(if (isOcr) text else "safe synthetic answer")
        }
        val main = Model(modelId = "qa-main-$unique", inputModalities = listOf(me.rerere.ai.provider.Modality.TEXT))
        val ocr = Model(modelId = "qa-ocr-$unique", inputModalities = listOf(me.rerere.ai.provider.Modality.TEXT, me.rerere.ai.provider.Modality.IMAGE))
        val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(main, ocr))
        val assistant = Assistant(chatModelId = main.id, reasoningLevel = ReasoningLevel.OFF, streamOutput = false,
            enableWebSearch = false, localTools = emptyList())
        val config = original.copy(providers = listOf(provider), assistants = original.assistants + assistant,
            assistantId = assistant.id, chatModelId = main.id, ocrModelId = ocr.id,
            networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
        val cache = File(context.cacheDir, "ocr_cache.json")
        var active: AgentExecution? = null
        var changed = false
        try {
            settingsStore.update(config)
            settingsStore.settingsFlow.first { it.ocrModelId == ocr.id }
            memory.change(owner) { me.rerere.rikkahub.feature.memory.MemoryState(fingerprintVersion = 1) to Unit }
            if (mode == "pre-missing") mutate("missing")
            if (mode == "old-cache") {
                val key = me.rerere.rikkahub.feature.agent.AgentTaskStore.hash(owner + "\u0000" +
                    me.rerere.rikkahub.feature.material.MaterialFiles.digest(file) + "\u0000" +
                    Json.encodeToString(ocr) + "\u0000" + Json.encodeToString<ProviderSetting>(provider) + "\u0000" + config.ocrPrompt)
                cache.writeText(buildJsonObject { put(key, "OLD_UNVERIFIED_OCR_$unique") }.toString())
            }
            if (mode in setOf("fresh", "old-deleted")) memory.change(owner) { it.copy(suppressedSources = setOf(
                if (mode == "old-deleted") marker else memory.fingerprint(owner, "message:${Uuid.random()}"))) to Unit }
            if (mode == "other-owner") memory.change("qa-other-$unique") { it.copy(suppressedSources = setOf(marker)) to Unit }
            val messages = if (mode == "old-deleted") listOf(message, UIMessage.assistant("old image answer"), UIMessage.user("fresh question")) else listOf(message)
            LeasedGenerationFixture(config, assistant).use { fixture ->
                suspend fun generate() = fixture.run {
                    active = requireNotNull(currentCoroutineContext()[AgentExecution])
                    withContext(ProviderRequestGuard {
                        if (mode.startsWith("physical-") && !changed && currentCoroutineContext()[ProviderRequestAccounting] != null) {
                            changed = true
                            mutate(mode.removePrefix("physical-"))
                        }
                    }) {
                        k.get<GenerationLoop>().generateText(settings = config, model = main, messages = messages,
                            assistant = assistant, profileId = owner, inputTransformers = listOf(OcrTransformer)).collect()
                    }
                }
                val result = runCatching { withTimeout(20_000) { generate() } }
                val rejected = mode.startsWith("physical-") || mode.startsWith("late-") ||
                    mode in setOf("pre-missing", "provider-failure")
                if (!rejected) result.getOrThrow()
                assertEquals("Source invalidation must abort rather than become OCR error text: $mode", !rejected, result.isSuccess)
                val expectedOcr = if (mode.startsWith("physical-") || mode in setOf("old-deleted", "pre-missing")) 0 else 1
                val ocrBodies = server.bodies.filter { "qa-ocr-$unique" in it }
                assertEquals("Physical OCR dispatches: $mode", expectedOcr, ocrBodies.size)
                assertEquals("Main model dispatches: $mode", if (rejected) 0 else 1, server.bodies.count { "qa-main-$unique" in it })
                if (mode == "provider-failure") {
                    assertFalse(result.exceptionOrNull()!!.stackTraceToString().contains("PRIVATE_OCR_SECRET"))
                    active!!.store.readableDatabase.rawQuery(
                        "SELECT state FROM calls WHERE task_id = ? AND tool = 'provider:ocr'", arrayOf(active!!.lease.task.id)).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("UNKNOWN must not be cleared to satisfy this fixture", "UNKNOWN", it.getString(0))
                    }
                }
                if (mode.startsWith("physical-")) active!!.store.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM usage WHERE task_id = ?", arrayOf(active!!.lease.task.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals("Guard precedes accounting", 0, it.getInt(0))
                }
                val cacheText = if (cache.exists()) cache.readText() else ""
                assertEquals("Cache publication requires unchanged source: $mode", !rejected && expectedOcr > 0 && mode != "provider-failure", text in cacheText)
                if (!rejected) {
                    val body = server.bodies.last()
                    assertFalse(body.contains("PRIVATE_OCR_SECRET"))
                    assertEquals(mode !in setOf("old-deleted", "provider-failure"), text in body)
                    if (mode == "old-cache") {
                        assertTrue("Old cache bytes are preserved", "OLD_UNVERIFIED_OCR_$unique" in cacheText)
                        assertFalse("Old unverified cache must not reach main", "OLD_UNVERIFIED_OCR_$unique" in body)
                    }
                }
            }
            if (mode in setOf("cache", "cache-new-message")) {
                LeasedGenerationFixture(config, assistant).use { fixture -> fixture.run {
                    val next = if (mode == "cache-new-message") message.copy(id = Uuid.random()) else message
                    k.get<GenerationLoop>().generateText(settings = config, model = main, messages = listOf(next),
                        assistant = assistant, profileId = owner, inputTransformers = listOf(OcrTransformer)).collect()
                } }
                assertEquals(if (mode == "cache-new-message") 2 else 1, server.bodies.count { "qa-ocr-$unique" in it })
                assertEquals(2, server.bodies.count { "qa-main-$unique" in it })
            }
            assertEquals(listOf(image), message.parts.filterIsInstance<UIMessagePart.Image>())
            if (mode.endsWith("deleted")) assertTrue(marker in memory.read(owner).suppressedSources)
        } finally {
            memory.change(owner) { previous to Unit }
            if (mode == "other-owner") k.get<AppDatabase>().openHelper.writableDatabase.execSQL(
                "DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf("qa-other-$unique"))
            settingsStore.update(original); settingsStore.settingsFlow.first { it.assistantId == original.assistantId }
            file.delete(); server.close()
        }
    }
}
