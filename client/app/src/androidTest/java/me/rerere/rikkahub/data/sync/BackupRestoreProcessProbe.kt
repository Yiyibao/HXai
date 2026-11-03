package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.db.*
import me.rerere.rikkahub.data.db.entity.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.ConversationQueue
import me.rerere.rikkahub.data.model.PendingInput
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.service.withIsolatedRestoreSettings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.uuid.Uuid

/** External driver intentionally ends the real target process after installation, before commit. */
class BackupRestoreProcessProbe {
    @Test fun interruptedRestoreRetainsDeletionAndPausedQueue(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        check(app.packageName == "org.hxsi.agent.nativeqa")
        val phase = InstrumentationRegistry.getArguments().getString("restorePhase")!!
        val fixture = InstrumentationRegistry.getArguments().getString("restoreFixture")!!
        require(phase in setOf("seed", "crash", "verify") && Regex("[a-f0-9-]{36}").matches(fixture))
        val root = File(app.filesDir, "qa-a02-restore-$fixture").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name) else
                File(root, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val settings = GlobalContext.get().get<SettingsStore>()
        val owner = settings.settingsFlowRaw.first { !it.init }.activeProfileId
        val deleted = UUID.nameUUIDFromBytes("deleted:$fixture".toByteArray()).toString()
        val survivor = UUID.nameUUIDFromBytes("survivor:$fixture".toByteArray()).toString()
        val pending = PendingRestore(File(context.noBackupFilesDir, "backup-restore"),
            context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME), context.filesDir)
        suspend fun <T> withDatabase(block: suspend (AppDatabase) -> T): T {
            val database = AppDatabaseFactory.create(context)
            try { return block(database) } finally { database.close() }
        }
        if (phase == "seed") {
            withDatabase { db ->
                fun entity(id: String, queue: String = "{}") = ConversationEntity(id, UUID.randomUUID().toString(),
                    "synthetic restore history", "[]", 1, 1, "[]", false, profileId = owner, queueState = queue)
                db.conversationDao().insert(entity(deleted))
                val queue = ConversationQueue(messages = listOf(PendingInput(Uuid.random(), listOf(UIMessagePart.Text("queued synthetic goal")))))
                db.conversationDao().insert(entity(survivor, JsonInstant.encodeToString(queue)))
                val memories = LearningMemoryStore(db, MemoryFingerprints(context))
                memories.addManual(owner, "deleted-memory", MemoryType.PREFERENCE, "synthetic preference")
                val manager = BackupManager(context, db, settings, JsonInstant)
                val archive = manager.createEncryptedBackup("synthetic process backup!".toCharArray())
                val item = memories.read(owner).items.single()
                memories.delete(owner, item.id, item.revision)
                db.conversationDao().recordDeletion(ConversationDeletionEntity(deleted, owner, 4, true))
                db.conversationDao().deleteById(deleted)
                manager.confirmRestore(manager.prepareEncryptedRestore(archive, "synthetic process backup!".toCharArray()))
            }
        } else {
            withIsolatedRestoreSettings(File(root, "restore.preferences_pb")) {
                assertTrue(pending.apply(beforeInstall = { staging ->
                    withDatabase { live -> BackupManager.mergeStagedRestore(context, JsonInstant, staging, live) }
                }) {
                    if (phase == "crash") {
                        FileOutputStream(File(root, "INSTALLED_BEFORE_CRASH")).use { out -> out.write("installed".toByteArray()); out.fd.sync() }
                        kotlin.system.exitProcess(77)
                    }
                })
            }
            assertTrue(File(root, "INSTALLED_BEFORE_CRASH").isFile)
            withDatabase { db ->
                assertNull(db.conversationDao().getConversationById(deleted))
                assertTrue(db.conversationDao().deletion(deleted)!!.deleted)
                val chat = requireNotNull(db.conversationDao().getConversationById(survivor))
                assertTrue(chat.lifecycleRevision > 0)
                val queue = JsonInstant.decodeFromString<ConversationQueue>(chat.queueState)
                assertTrue(queue.paused); assertEquals(1, queue.messages.size)
                assertTrue(LearningMemoryStore(db, MemoryFingerprints(context)).read(owner).items.isEmpty())
            }
            assertFalse(pending.apply {})
        }
        println("BACKUP_RESTORE_PROCESS_PASS $phase $fixture")
    }
}
