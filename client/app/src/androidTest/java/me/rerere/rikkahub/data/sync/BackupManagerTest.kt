package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.service.restoreAppDictionary
import me.rerere.rikkahub.service.withIsolatedRestoreSettings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    @Test fun oldArchiveCannotReviveConversationDeletedBeforeConfirmation() = conversationDeletion(false)
    @Test fun deletionAfterConfirmationIsMergedAgainAtStartup() = conversationDeletion(true)
    @Test fun missingPreparedDatabaseIsRejectedBeforePublish() = damagedStaging("database")
    @Test fun missingPreparedSettingsIsRejectedBeforePublish() = damagedStaging("settings")

    private fun damagedStaging(kind: String): Unit = runBlocking {
        val archive = manager.createEncryptedBackup("staging integrity fixture!".toCharArray())
        val preview = manager.prepareEncryptedRestore(archive, "staging integrity fixture!".toCharArray())
        val target = if (kind == "database") File(preview.staging, "payload/database/${me.rerere.rikkahub.data.db.SQLiteConfiguration.DATABASE_NAME}")
            else File(preview.staging, "settings.json")
        assertTrue(target.delete())
        assertTrue("Missing prepared input must not silently create an empty restore", runCatching { manager.confirmRestore(preview) }.isFailure)
        assertEquals("live", probe(liveDatabase))
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertTrue(preview.staging.isDirectory)
        manager.discardRestore(preview)
    }

    @Test fun conflictingConversationOwnerRejectsRestoreWithoutTouchingLiveData(): Unit = runBlocking {
        val owner = GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first { !it.init }.activeProfileId
        val id = java.util.UUID.randomUUID().toString()
        val original = me.rerere.rikkahub.data.db.entity.ConversationEntity(id, java.util.UUID.randomUUID().toString(),
            "archive owner", "[]", 1, 1, "[]", false, profileId = owner)
        liveDatabase.conversationDao().insert(original)
        val archive = manager.createEncryptedBackup("owner boundary fixture!".toCharArray())
        val preview = manager.prepareEncryptedRestore(archive, "owner boundary fixture!".toCharArray())
        liveDatabase.conversationDao().update(original.copy(profileId = "another-owner"))
        assertTrue(runCatching { manager.confirmRestore(preview) }.isFailure)
        assertEquals("another-owner", liveDatabase.conversationDao().getConversationById(id)!!.profileId)
        assertEquals("live", probe(liveDatabase))
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        manager.discardRestore(preview)
    }

    private fun conversationDeletion(afterConfirmation: Boolean): Unit = runBlocking {
        val owner = GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first { !it.init }.activeProfileId
        val id = java.util.UUID.randomUUID().toString()
        liveDatabase.conversationDao().insert(me.rerere.rikkahub.data.db.entity.ConversationEntity(id,
            java.util.UUID.randomUUID().toString(), "old derived title", "[]", 1, 1, "[]", false, profileId = owner))
        val archive = manager.createEncryptedBackup("conversation deletion fixture!".toCharArray())
        val preview = manager.prepareEncryptedRestore(archive, "conversation deletion fixture!".toCharArray())
        if (afterConfirmation) manager.confirmRestore(preview)
        liveDatabase.conversationDao().recordDeletion(me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity(id, owner, 0, true))
        liveDatabase.conversationDao().deleteById(id)
        if (!afterConfirmation) manager.confirmRestore(preview)
        liveDatabase.close()
        withIsolatedRestoreSettings(File(directory, "conversation-delete.preferences_pb")) {
            assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        }
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("An old archive is not explicit permission to resurrect a deleted conversation", null,
            liveDatabase.conversationDao().getConversationById(id))
        assertTrue(liveDatabase.conversationDao().deletion(id)!!.deleted)
        assertEquals("live", probe(liveDatabase))
    }
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var manager: BackupManager

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "backup-manager-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        liveDatabase = AppDatabaseFactory.create(context)
        liveDatabase.openHelper.writableDatabase.execSQL("CREATE TABLE backup_probe (text TEXT)")
        liveDatabase.openHelper.writableDatabase.execSQL("INSERT INTO backup_probe VALUES ('live')")
        manager = BackupManager(context, liveDatabase, GlobalContext.get().get<SettingsStore>(), JsonInstant)
    }

    @After fun tearDown() {
        liveDatabase.close()
        restoreAppDictionary()
        directory.deleteRecursively()
    }

    @Test fun encryptedRestoreLeavesLiveDatabaseUntouchedUntilConfirmedStartup() = runBlocking {
        val expected = GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first { !it.init }
        val archive = manager.createEncryptedBackup("encrypted backup fixture!".toCharArray())
        liveDatabase.openHelper.writableDatabase.execSQL("UPDATE backup_probe SET text = 'changed after snapshot'")
        val preview = manager.prepareEncryptedRestore(archive, "encrypted backup fixture!".toCharArray())
        assertEquals("changed after snapshot", probe(liveDatabase))
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        manager.confirmRestore(preview)
        assertEquals("changed after snapshot", probe(liveDatabase))
        liveDatabase.close()
        withIsolatedRestoreSettings(File(directory, "restored.preferences_pb")) { store ->
            assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
            val preferences = store.data.first()
            assertEquals(expected.activeProfileId, preferences[SettingsStore.ACTIVE_PROFILE])
            assertEquals(expected.learnerProfiles,
                JsonInstant.decodeFromString<List<me.rerere.rikkahub.feature.studychat.LearnerProfile>>(
                    preferences[SettingsStore.LEARNER_PROFILES]!!))
        }
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("live", probe(liveDatabase))
        assertFalse(BackupManager.applyPendingRestore(context, JsonInstant))
    }

    @Test fun encryptedSnapshotContainsCommittedDatabaseWithoutWalOrShm() = runBlocking {
        val archive = manager.createEncryptedBackup("encrypted backup fixture!".toCharArray())
        val plain = File(directory, "authenticated.zip")
        EncryptedBackup.decrypt(archive, plain, "encrypted backup fixture!".toCharArray())
        BackupIntegrity.verify(plain)
        ZipFile(plain).use { zip ->
            assertTrue(zip.getEntry("settings.json") != null)
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
        }
        val preview = manager.prepareEncryptedRestore(archive, "encrypted backup fixture!".toCharArray())
        assertEquals("live", probe(liveDatabase))
        manager.discardRestore(preview)
        assertFalse(preview.staging.exists())
    }

    @Test fun authenticatedFutureSchemaIsRejectedBeforePublishing() = runBlocking {
        val database = liveDatabase.openHelper.writableDatabase
        val version = database.version
        val archive = try {
            database.version = 9999
            manager.createEncryptedBackup("encrypted backup fixture!".toCharArray())
        } finally { database.version = version }
        val failure = runCatching {
            manager.prepareEncryptedRestore(archive, "encrypted backup fixture!".toCharArray())
        }.exceptionOrNull()
        assertTrue("Future schema must fail migration, not an obsolete entry point",
            failure?.message?.contains("9999") == true)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun encryptedBackupRetainsOpaqueLegacySourceProtectionAfterStartupRestore() = runBlocking {
        val owner = GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first { !it.init }.activeProfileId
        val fingerprints = me.rerere.rikkahub.feature.memory.MemoryFingerprints(context)
        val store = me.rerere.rikkahub.feature.memory.LearningMemoryStore(liveDatabase, fingerprints)
        val id = store.addManual(owner, "keep", me.rerere.rikkahub.feature.memory.MemoryType.PREFERENCE, "保留备份管理记录")
        val legacyMarker = fingerprints.legacyOf(owner, "message:missing-source")
        val state = store.read(owner).copy(fingerprintVersion = 0, suppressedSources = setOf(legacyMarker))
        liveDatabase.learningMemoryDao().put(me.rerere.rikkahub.feature.memory.LearningMemoryStateEntity(owner, JsonInstant.encodeToString(state)))
        val archive = manager.createEncryptedBackup("encrypted backup fixture!".toCharArray())
        val protected = store.read(owner)
        assertEquals(2, protected.schemaVersion)
        assertEquals(setOf(legacyMarker), protected.legacySuppressedSources)
        val preview = manager.prepareEncryptedRestore(archive, "encrypted backup fixture!".toCharArray())
        manager.confirmRestore(preview)
        liveDatabase.close()
        withIsolatedRestoreSettings(File(directory, "orphan-restored.preferences_pb")) {
            assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        }
        liveDatabase = AppDatabaseFactory.create(context)
        val restored = me.rerere.rikkahub.feature.memory.LearningMemoryStore(liveDatabase, fingerprints)
        assertEquals(2, restored.read(owner).schemaVersion)
        assertEquals(setOf(legacyMarker), restored.read(owner).legacySuppressedSources)
        assertTrue(restored.read(owner).recoveryBlocked)
        assertEquals(id, restored.read(owner).items.single().id)
        assertTrue(runCatching { restored.projectHistory(owner, listOf(me.rerere.ai.ui.UIMessage.user("old-source"))) }.isFailure)
        assertFalse(BackupManager.applyPendingRestore(context, JsonInstant))
    }

    @Test fun olderEncryptedSnapshotCannotEraseNewerOpaqueSourceDeletion() = runBlocking {
        val owner = GlobalContext.get().get<SettingsStore>().settingsFlowRaw.first { !it.init }.activeProfileId
        val fingerprints = me.rerere.rikkahub.feature.memory.MemoryFingerprints(context)
        val store = me.rerere.rikkahub.feature.memory.LearningMemoryStore(liveDatabase, fingerprints)
        store.addManual(owner, "old-item", me.rerere.rikkahub.feature.memory.MemoryType.PREFERENCE, "旧快照不得复活此记录")
        val archive = manager.createEncryptedBackup("encrypted backup fixture!".toCharArray())
        val marker = fingerprints.legacyOf(owner, "message:deleted-orphan")
        val deleted = store.read(owner).copy(fingerprintVersion = 0, items = emptyList(), suppressedSources = setOf(marker))
        liveDatabase.learningMemoryDao().put(me.rerere.rikkahub.feature.memory.LearningMemoryStateEntity(owner, JsonInstant.encodeToString(deleted)))
        assertTrue(store.read(owner).recoveryBlocked)
        val preview = manager.prepareEncryptedRestore(archive, "encrypted backup fixture!".toCharArray())
        manager.confirmRestore(preview)
        liveDatabase.close()
        withIsolatedRestoreSettings(File(directory, "old-orphan-restored.preferences_pb")) {
            assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        }
        liveDatabase = AppDatabaseFactory.create(context)
        val restored = me.rerere.rikkahub.feature.memory.LearningMemoryStore(liveDatabase, fingerprints)
        assertTrue(restored.read(owner).items.isEmpty())
        assertEquals(2, restored.read(owner).schemaVersion)
        assertEquals(setOf(marker), restored.read(owner).legacySuppressedSources)
        assertTrue(restored.read(owner).recoveryBlocked)
    }

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use { check(it.moveToFirst()); it.getString(0) }

}
