package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey

internal val credentialPreferenceKeys = listOf(
    "providers", "assistants", "network_setting", "search_services", "mcp_servers",
    "webdav_config", "s3_config", "tts_providers", "asr_providers", "web_server_access_password",
).map(::stringPreferencesKey)

internal fun protectCredentialPreferences(preferences: MutablePreferences, previous: Preferences?, vault: CredentialVault) {
    credentialPreferenceKeys.forEach { key ->
        preferences[key]?.let { preferences[key] = vault.protect(it, previous?.get(key)) }
    }
}

internal fun resetFailedCredentialPreferences(
    preferences: MutablePreferences,
    vault: CredentialVault,
    archive: (String) -> Unit,
) {
    val failed = credentialPreferenceKeys.filter { key ->
        preferences[key]?.let { value -> runCatching { vault.resolve(value) }.isFailure } == true
    }
    check(failed.isNotEmpty()) { "No unavailable credentials" }
    archive(failed.joinToString("\n") { key -> "${key.name}=${preferences[key]}" })
    vault.renewInvalidatedKey()
    failed.forEach(preferences::remove)
    preferences[SettingsStore.WEB_SERVER_ENABLED] = false
}

internal class CredentialPreferencesMigration(private val vault: CredentialVault) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = credentialPreferenceKeys.any {
        currentData[it]?.let { value -> !CredentialVault.isReference(value) } == true
    }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        credentialPreferenceKeys.forEach { key ->
            this[key]?.let { value ->
                // Keep existing references untouched. Runtime reads handle unavailable records
                // so explicit recovery remains possible even alongside a legacy partition.
                if (!CredentialVault.isReference(value)) this[key] = vault.protect(value)
            }
        }
    }.toPreferences()

    override suspend fun cleanUp() = Unit
}
