package me.rerere.rikkahub.feature.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.sync.BackupDeletionMerge
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ManualMemoryDeletionRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { encodeDefaults = true }
    private fun db(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name).build()

    @Test fun changedTypeCannotRecreateDeletedRequest(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val store = koin.get<LearningMemoryStore>()
        val previous = store.read(owner)
        val facade = StudyHostFacade(context, settings, koin.get(), koin.get(), store, koin.get(), koin.get(), pickFiles = { emptyList() })
        val epoch = facade.getBootstrap(owner, 1).contextEpoch
        val request = "manual-deletion-${Uuid.random()}"
        try {
            val added = facade.addMemory(owner, "先解释定义", "PREFERENCE", request, epoch)
            assertTrue(added.success)
            assertTrue(facade.deleteMemory(owner, added.idResult!!, "delete").success)
            val deleted = json.encodeToString(store.read(owner))
            MemoryType.entries.forEach { type ->
                assertFalse("A deleted request must remain deleted across type changes: $type",
                    facade.addMemory(owner, "先解释定义", type.name, request, epoch).success)
                assertEquals(deleted, json.encodeToString(store.read(owner)))
            }
            assertTrue(facade.getMemories(owner, "").none { it.id == added.idResult })
            assertFalse(facade.addMemory(owner, "先解释定义", "PREFERENCE", request + "-new", epoch + 1).success)
            assertTrue(facade.addMemory(owner, "先解释定义", "PREFERENCE", request + "-new", epoch).success)
        } finally {
            store.change(owner) { previous to Unit }
            facade.close()
        }
    }

    @Test fun stagedOlderBackupCannotChangeDeletedManualClassification(): Unit = runBlocking {
        val name = "qa-manual-live-${Uuid.random()}.db"
        val live = db(name)
        val staging = File(context.cacheDir, "qa-manual-restore-${Uuid.random()}").apply { mkdirs() }
        val stagedFile = File(staging, "payload/database/${SQLiteConfiguration.DATABASE_NAME}")
        stagedFile.parentFile!!.mkdirs()
        try {
            val store = LearningMemoryStore(live, MemoryFingerprints(context))
            val id = store.addManual("owner-a", "request", MemoryType.PREFERENCE, "先解释定义")
            val saved = store.read("owner-a")
            store.delete("owner-a", id, 1)
            val deleted = live.learningMemoryDao().get("owner-a")!!.payload
            val staged = db(stagedFile.absolutePath)
            try {
                val altered = saved.copy(items = saved.items.map { it.copy(type = MemoryType.BACKGROUND) })
                staged.learningMemoryDao().put(LearningMemoryStateEntity("owner-a", json.encodeToString(altered)))
                // Identical visible content and request text in another profile remain independent.
                val other = store.addManual("owner-b", "request", MemoryType.BACKGROUND, "先解释定义")
                staged.learningMemoryDao().put(LearningMemoryStateEntity("owner-b", json.encodeToString(store.read("owner-b"))))
                assertNotEquals(id, other)
            } finally { staged.close() }
            val keyFile = File(staging, "payload/files/${PortableMemoryKey.FILE_NAME}")
            keyFile.parentFile!!.mkdirs()
            File(context.filesDir, PortableMemoryKey.FILE_NAME).copyTo(keyFile)
            BackupDeletionMerge.merge(context, json, staging, live)
            val reopened = db(stagedFile.absolutePath)
            try {
                val merged = json.decodeFromString<MemoryState>(reopened.learningMemoryDao().get("owner-a")!!.payload)
                assertTrue("Staged restore must retain manual request deletion across classifications", merged.items.isEmpty())
                assertEquals(store.read("owner-a").suppressedFacts, merged.suppressedFacts)
                assertEquals(1, json.decodeFromString<MemoryState>(reopened.learningMemoryDao().get("owner-b")!!.payload).items.size)
                assertEquals(deleted, live.learningMemoryDao().get("owner-a")!!.payload)
            } finally { reopened.close() }
        } finally {
            live.close(); context.deleteDatabase(name)
            check(staging.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            staging.deleteRecursively()
        }
    }
}
