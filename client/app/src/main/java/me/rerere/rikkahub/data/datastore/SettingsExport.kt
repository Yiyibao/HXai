package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ai.provider.ProviderSetting

fun ProviderSetting.withoutCredentials(): ProviderSetting = JsonInstant.decodeFromString(
    stripProviderCredentials(JsonInstant.parseToJsonElement(JsonInstant.encodeToString(this))).toString()
)

/** Portable/Web snapshots never carry runtime credentials or device-local vault references. */
fun Settings.withoutCredentials(): Settings {
    val source = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(this)) as JsonObject
    val omittedSections = setOf(
        "networkSetting", "searchServices", "mcpServers", "webDavConfig", "s3Config",
        "ttsProviders", "asrProviders", "webServerAccessPassword", "webServerEnabled",
    )
    val portable = JsonObject(source.filterKeys { it !in omittedSections }.mapValues { (name, value) ->
        if (name == "providers" || name == "assistants") stripProviderCredentials(value) else value
    })
    return JsonInstant.decodeFromString(portable.toString())
}

private fun stripProviderCredentials(value: JsonElement): JsonElement = when (value) {
    is JsonArray -> JsonArray(value.map(::stripProviderCredentials))
    is JsonObject -> JsonObject(value.mapValues { (name, child) ->
        when (name) {
            "apiKey", "privateKey", "serviceAccountEmail", "baseUrl", "chatCompletionsPath", "responsesPath" -> JsonPrimitive("")
            "customHeaders", "customBodies" -> JsonArray(emptyList())
            else -> stripProviderCredentials(child)
        }
    })
    else -> value
}
