package me.rerere.rikkahub.feature.agent

import android.content.Context
import me.rerere.rikkahub.data.datastore.CredentialVault
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid
import me.rerere.ai.provider.ProviderSetting

/** Encrypt the compatibility snapshot; only its reference enters queue/task storage. */
internal class AgentModelSnapshots(context: Context) {
    private val vault = CredentialVault(context)

    fun capture(settings: Settings, assistantId: Uuid): AgentModelLock {
        check(!settings.init) { "Model settings not initialized" }
        val assistant = settings.getAssistantById(assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: error("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        check(provider.enabled) { "Selected provider disabled" }
        val encoded = JsonInstant.encodeToString(settings.copy(assistantId = assistant.id, networkSetting = settings.networkSetting.copy(enableAutoRetry = false)))
        return AgentModelLock(provider.id.toString(), model.id.toString(), assistant.reasoningLevel.name, agentHash(encoded), vault.protect(encoded))
    }

    fun resolve(lock: AgentModelLock, current: Settings, profileId: String): Settings {
        require(CredentialVault.isReference(lock.secretReference)) { "Task snapshot requires a protected vault record" }
        check(current.activeProfileId == profileId && current.learnerProfiles.any { it.id == profileId }) { "Task profile unavailable" }
        // Removal/disable is revocation; an encrypted old credential cannot bypass it.
        val selected = current.findModelById(Uuid.parse(lock.modelId)) ?: error("Locked model removed; task blocked")
        val provider = selected.findProvider(current.providers) ?: error("Locked provider removed; task blocked")
        check(provider.enabled && provider.id.toString() == lock.providerId) { "Locked provider unavailable; task blocked" }
        val encoded = vault.resolve(lock.secretReference)
        check(agentHash(encoded) == lock.configurationRevision) { "Model snapshot mismatch" }
        return JsonInstant.decodeFromString<Settings>(encoded).also { frozen ->
            check(frozen.activeProfileId == profileId)
            val lockedModel = requireNotNull(frozen.findModelById(Uuid.parse(lock.modelId)))
            val lockedProvider = requireNotNull(lockedModel.findProvider(frozen.providers))
            val frozenAssistant = frozen.getCurrentAssistant()
            check((frozenAssistant.chatModelId ?: frozen.chatModelId).toString() == lock.modelId &&
                lockedProvider.id.toString() == lock.providerId && frozenAssistant.reasoningLevel.name == lock.reasoning) { "Task model lock mismatch" }
            fun credentials(value: ProviderSetting): List<String> = when (value) {
                is ProviderSetting.OpenAI -> listOf(value.apiKey)
                is ProviderSetting.Google -> listOf(value.apiKey, value.privateKey, value.serviceAccountEmail)
                is ProviderSetting.Claude -> listOf(value.apiKey)
            } + value.customHeaders.map { it.name + ":" + it.value }
            // Rotation is an AUTH-03 operation. Until that contract exists, changed/cleared credentials block.
            check(credentials(provider) == credentials(lockedProvider)) { "Locked credentials changed; task needs authorization recovery" }
        }
    }
}
