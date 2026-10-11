package me.rerere.rikkahub.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext
import java.io.File

/** Swap only the startup singleton; existing Koin consumers retain their live DataStore. */
internal suspend fun <T> withIsolatedRestoreSettings(
    file: File,
    block: suspend (DataStore<Preferences>) -> T,
): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    val field = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")
        .getDeclaredField("settingsDataStore").apply { isAccessible = true }
    val original = field.get(null)
    field.set(null, store)
    return try { block(store) } finally {
        field.set(null, original)
        scope.coroutineContext.job.cancelAndJoin()
    }
}

internal fun restoreAppDictionary() {
    // libsimple's dictionary path is process-wide, including isolated Room fixtures.
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val path = SimpleDictManager.extractDict(app).absolutePath
    GlobalContext.get().get<AppDatabase>().openHelper.writableDatabase
        .query("SELECT jieba_dict(?)", arrayOf(path)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(path, cursor.getString(0)?.trimEnd('/'))
        }
}
