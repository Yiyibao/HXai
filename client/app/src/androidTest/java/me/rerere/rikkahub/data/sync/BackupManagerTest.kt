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

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use { check(it.moveToFirst()); it.getString(0) }

}
