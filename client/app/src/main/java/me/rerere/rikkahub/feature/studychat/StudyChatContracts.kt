package me.rerere.rikkahub.feature.studychat

import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import java.net.URI
import kotlin.uuid.Uuid

data class StudyModelIdentity(val providerId: Uuid, val modelId: Uuid)

fun studyChatModels(providers: List<ProviderSetting>, query: String): List<Pair<ProviderSetting, List<Model>>> =
    providers.filter { it.enabled }.mapNotNull { provider ->
        val models = provider.models.filter { model ->
            model.type == ModelType.CHAT && (query.isBlank() ||
                model.displayName.contains(query, true) || model.modelId.contains(query, true) ||
                provider.name.contains(query, true))
        }
        if (models.isEmpty()) null else provider to models
    }

fun normalizeStudyRecoveryState(rawState: String?): String = when (rawState) {
    "CANCELLED" -> "CANCELLED"
    "INTERRUPTED", "RUNNING", "WAITING_TOOL" -> "INTERRUPTED"
    else -> "FAILED"
}

fun isValidStudyProvider(provider: ProviderSetting): Boolean {
    val url = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
    }
    return provider.name.isNotBlank() && runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}

/** The caller dismisses the picker only after this returns successfully. */
suspend fun commitStudyModelSelection(model: Model, persist: suspend (Model) -> Unit): Result<Unit> =
    try {
        persist(model)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
