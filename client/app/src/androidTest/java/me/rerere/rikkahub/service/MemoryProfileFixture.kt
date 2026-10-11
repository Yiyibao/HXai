package me.rerere.rikkahub.service

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.utils.JsonInstant

/** Test-only cleanup. Production profile updates remain atomic and purpose-specific. */
internal suspend fun restoreMemoryProfileFixture(original: Settings, store: SettingsStore) {
    @Suppress("UNCHECKED_CAST")
    val dataStore = SettingsStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }.get(store) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    dataStore.edit {
        it[SettingsStore.LEARNER_PROFILES] = JsonInstant.encodeToString(original.learnerProfiles)
        it[SettingsStore.ACTIVE_PROFILE] = original.activeProfileId
    }
    withTimeout(20_000) { store.settingsFlow.first { it.activeProfileId == original.activeProfileId && it.learnerProfiles == original.learnerProfiles } }
    store.update(original)
}
