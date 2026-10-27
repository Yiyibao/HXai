package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ProviderRequestGuard
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

class TranslationHandler(
    private val providerManager: ProviderManager,
    private val requests: me.rerere.rikkahub.feature.agent.ModelRequestRunner,
) {
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        conversationId: String? = null,
        validateRequest: () -> Unit = {},
        validateSource: suspend () -> Unit = {},
        onStreamUpdate: ((String) -> Unit)? = null,
    ): Flow<String> {
        // Admission happens now, not when a cold Flow eventually collects.
        validateRequest()
        val request = requests.accept(settings,"translation","${targetLanguage.toLanguageTag()}:$sourceText",
            conversationId = conversationId ?: kotlin.uuid.Uuid.random().toString())
        val prompt = settings.translatePrompt.applyPlaceholders(
            "source_text" to sourceText, "target_lang" to targetLanguage.toString())
        return channelFlow {
            withContext(ProviderRequestGuard { validateRequest(); validateSource() }) {
                requests.run(request, validateRequest) { model,provider,reasoning ->
                    validateSource()
                    val providerHandler = providerManager.getProviderByType(provider)
                    if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
                        var messages = listOf(UIMessage.user(prompt))
                        val streamChunkHandler = StreamChunkHandler(model)
                        providerHandler.streamText(provider,messages,TextGenerationParams(
                            model=model,reasoningLevel=reasoning,customHeaders=model.customHeaders,customBody=model.customBodies
                        )).collect { chunk ->
                            messages = streamChunkHandler.handle(messages,chunk)
                            val translatedText = messages.lastOrNull()?.toText().orEmpty()
                            if (translatedText.isNotBlank()) {
                                validateRequest(); validateSource()
                                onStreamUpdate?.invoke(translatedText)
                                send(translatedText)
                            }
                        }
                    } else {
                        val result = providerHandler.generateText(provider,listOf(UIMessage.user(sourceText)),
                            TextGenerationParams(model=model,reasoningLevel=reasoning,temperature=0.3f,topP=0.95f,
                                customHeaders=model.customHeaders,customBody=model.customBodies + CustomBody(
                                    "translation_options",buildJsonObject {
                                        put("source_lang","auto")
                                        put("target_lang",targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                    })))
                        val translatedText = result.message.toText()
                        if (translatedText.isNotBlank()) {
                            validateRequest(); validateSource()
                            onStreamUpdate?.invoke(translatedText)
                            send(translatedText)
                        }
                    }
                    validateRequest(); validateSource()
                }
            }
        }.flowOn(Dispatchers.IO)
    }
}
