package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.encodeToString
import androidx.core.net.toUri
import androidx.core.net.toFile
import me.rerere.rikkahub.feature.agent.AgentExecution
import me.rerere.rikkahub.feature.agent.AgentTaskStore
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderRequestGuard
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.days


object OcrTransformer : InputMessageTransformer, KoinComponent {
    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "ocr_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.model.inputModalities.contains(Modality.IMAGE)) {
            return messages
        }

        val hasImages = messages.any { message ->
            message.parts.any { it is UIMessagePart.Image && it.url.startsWith("file:") }
        }
        if (!hasImages) return messages

        return withContext(Dispatchers.IO) {
            try {
                ctx.processingStatus.value = "正在识别图片..."
                messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            when {
                                part is UIMessagePart.Image && part.url.startsWith("file:") -> {
                                    UIMessagePart.Text(performOcr(part, message))
                                }

                                else -> part
                            }
                        }
                    )
                }
            } finally {
                ctx.processingStatus.value = null
            }
        }
    }

    private suspend fun performOcr(part: UIMessagePart.Image, source: UIMessage): String = ocrTextOrFailure {
        val execution = requireNotNull(currentCoroutineContext()[AgentExecution]) { "OCR requires an accepted task lease" }
        val settings = execution.configuration.settings
        val model = settings.findModelById(settings.ocrModelId) ?: return@ocrTextOrFailure "[Image]"
        val providerSetting = model.findProvider(settings.providers) ?: return@ocrTextOrFailure "[Image]"
        val file = part.url.toUri().toFile()
        val sourceHash = requireOcrSource { me.rerere.rikkahub.feature.material.MaterialFiles.digest(file) }
        // Previous entries have no verified source lifecycle. Preserve them, but do not
        // reuse their results under this contract or across different message identities.
        val cacheKey = AgentTaskStore.hash("ocr-source-v2\u0000" + settings.activeProfileId + "\u0000" +
            source.id + "\u0000" + sourceHash + "\u0000" +
            Json.encodeToString(model) + "\u0000" + Json.encodeToString(providerSetting) + "\u0000" + settings.ocrPrompt)
        require(providerSetting.enabled) { "OCR provider disabled" }
        val validateRole = {
            val live = get<SettingsStore>().settingsFlow.value
            require(live.activeProfileId == settings.activeProfileId &&
                live.findModelById(model.id)?.findProvider(live.providers)?.enabled == true) { "OCR authorization unavailable" }
        }
        val upstreamGuard = currentCoroutineContext()[ProviderRequestGuard]
        suspend fun validateSource() = requireOcrSource {
            upstreamGuard?.validate?.invoke()
            execution.check()
            validateRole()
            check(me.rerere.rikkahub.feature.material.MaterialFiles.digest(file) == sourceHash)
        }
        val memories = get<me.rerere.rikkahub.feature.memory.LearningMemoryStore>()
        suspend fun <T> withSource(publish: () -> T): T = requireOcrSource {
            memories.commitWithHistory(settings.activeProfileId, listOf(source), listOf(source)) {
                validateSource()
                publish()
            }
        }
        withSource { cache.get(cacheKey) }?.let { return@ocrTextOrFailure it }
        val provider = get<ProviderManager>().getProviderByType(providerSetting)
        val result = try {
            withContext(execution.accounting("ocr",validateRole) + ProviderRequestGuard { validateSource() }) {
                provider.generateText(
                    providerSetting = providerSetting,
                    messages = listOf(
                        UIMessage.system(settings.ocrPrompt),
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Image(part.url))
                        )
                    ),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }
        } finally {
            // Also check failed responses: source invalidation must not become a
            // generic OCR placeholder that allows the main request to continue.
            validateSource()
        }
        val content = result.message.toText().ifBlank { "[ERROR, OCR failed]" }
        val ocrResult = """
            <image_file_ocr>
               $content
            </image_file_ocr>
            * The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.
        """.trimIndent()

        // Serialize the last source check and local write with deletion tombstones.
        // This short Room transaction never includes the provider request.
        withSource { cache.put(cacheKey, ocrResult) }
        ocrResult
    }
}

internal inline fun ocrTextOrFailure(recognize: () -> String): String = try {
    recognize()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (changed: OcrSourceUnavailableException) {
    throw changed
} catch (_: Exception) {
    // Provider failures may contain private recognized content, URLs or keys.
    "[ERROR, OCR failed]"
}

internal class OcrSourceUnavailableException : IllegalStateException(
    "图片来源或授权已变化，旧识别结果未使用；原聊天保留，请重新发起。"
)

internal inline fun <T> requireOcrSource(validate: () -> T): T = try {
    validate()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (changed: OcrSourceUnavailableException) {
    throw changed
} catch (_: Exception) {
    // No provider error, local path, recognized content or credential in diagnostics.
    throw OcrSourceUnavailableException()
}
