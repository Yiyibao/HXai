package me.rerere.rikkahub.feature.agent

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Accepted role requests share the Chat executor and encrypted immutable configuration. */
class AcceptedModelRequest internal constructor(internal val task: AgentTask, internal val role: String)

class ModelRequestRunner(context: Context, private val settingsStore: SettingsStore) {
    internal val scheduler = AgentScheduler.shared(context)

    fun accept(settings: Settings, role: String, input: String, conversationId: String = Uuid.random().toString(),
        requestId: String = Uuid.random().toString(), reasoning: ReasoningLevel? = null): AcceptedModelRequest {
        val modelId = when (role) {
            "memory" -> settings.fastModelId
            "translation" -> settings.translateModeId
            "image" -> settings.imageGenerationModelId
            "connection_test" -> settings.chatModelId
            else -> error("Unsupported independent model role")
        }
        val model = requireNotNull(settings.findModelById(modelId)) { "请先明确配置辅助角色模型" }
        val level = reasoning ?: when (role) {
            "memory" -> settings.fastModelReasoningLevel
            "translation" -> ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget)
            else -> ReasoningLevel.OFF
        }
        val assistant = settings.getCurrentAssistant().copy(chatModelId=model.id, reasoningLevel=level)
        val frozen = settings.copy(assistants=listOf(assistant), assistantId=assistant.id, chatModelId=model.id)
        val conversation = Conversation.ofId(Uuid.parse(conversationId), assistant.id).copy(profileId=settings.activeProfileId)
        return AcceptedModelRequest(scheduler.accept(conversation,"role:$role:$requestId",input,frozen),role)
    }

    suspend fun <T> run(request: AcceptedModelRequest, validate: () -> Unit = {},
        call: suspend (Model, ProviderSetting, ReasoningLevel) -> T): T =
        scheduler.run(request.task,{ settingsStore.settingsFlow.value },validate,{ "COMPLETED_UNVERIFIED" },{ false }) {
            val execution = requireNotNull(currentCoroutineContext()[AgentExecution])
            val config = execution.configuration
            val model = requireNotNull(config.settings.findModelById(config.assistant.chatModelId))
            val provider = requireNotNull(model.findProvider(config.settings.providers))
            withContext(execution.accounting(request.role,validate)) { call(model,provider,config.assistant.reasoningLevel) }
        }
}
