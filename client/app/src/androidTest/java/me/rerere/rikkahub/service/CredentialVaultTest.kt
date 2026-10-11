package me.rerere.rikkahub.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderSetting
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import java.util.zip.ZipFile
import me.rerere.rikkahub.data.datastore.CredentialVault
import me.rerere.rikkahub.data.datastore.CredentialPreferencesMigration
import me.rerere.rikkahub.data.datastore.resetFailedCredentialPreferences
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var vault: CredentialVault

    @Before fun setup() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "credential-test-").toFile()
        context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        vault = CredentialVault(context)
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun encryptedWriteSurvivesReopenAndDoesNotStoreCanary() {
        val value = "SYNTHETIC_CREDENTIAL_CANARY"
        val ref = vault.protect(value)
        assertTrue(CredentialVault.isReference(ref))
        assertEquals(value, CredentialVault(context).resolve(ref))
        assertFalse(directory.walkTopDown().filter { it.isFile }.any { it.readBytes().toString(Charsets.ISO_8859_1).contains(value) })
        assertEquals(ref, vault.protect(value, ref))
        val newRef = vault.protect("changed", ref)
        assertNotEquals(ref, newRef)
        assertEquals(value, vault.resolve(ref)) // old snapshots stay valid until commit
    }

    @Test fun tamperedCiphertextIsRejected() {
        val ref = vault.protect("synthetic")
        val record = directory.walkTopDown().first { it.extension == "bin" }
        val bytes = record.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        record.writeBytes(bytes)
        assertTrue(runCatching { vault.resolve(ref) }.isFailure)
    }

    @Test fun missingRecordAndPathTraversalAreRejected() {
        val ref = vault.protect("synthetic")
        directory.walkTopDown().first { it.extension == "bin" }.delete()
        assertTrue(runCatching { vault.resolve(ref) }.isFailure)
        assertTrue(runCatching { vault.resolve("credential-v1:../../settings") }.isFailure)
    }

    @Test fun recoveryResetsOnlyFailedPartitionsAndPreservesProfileAndReadableCredentials() {
        val missing = vault.protect("synthetic lost credential")
        directory.walkTopDown().first { it.extension == "bin" }.delete()
        val readable = vault.protect("synthetic preserved assistant")
        val prefs = mutablePreferencesOf(SettingsStore.PROVIDERS to missing, SettingsStore.ASSISTANTS to readable,
            SettingsStore.ACTIVE_PROFILE to "learner-b", SettingsStore.WEB_SERVER_ENABLED to true)
        var archived = ""
        resetFailedCredentialPreferences(prefs, vault) { archived = it }
        assertNull(prefs[SettingsStore.PROVIDERS])
        assertEquals(readable, prefs[SettingsStore.ASSISTANTS])
        assertEquals("learner-b", prefs[SettingsStore.ACTIVE_PROFILE])
        assertEquals(false, prefs[SettingsStore.WEB_SERVER_ENABLED])
        assertTrue(archived.contains(missing))
        assertFalse(archived.contains("synthetic"))
    }

    @Test fun failedRecoveryArchiveDoesNotMutatePreferences() {
        val missing = vault.protect("synthetic")
        directory.walkTopDown().first { it.extension == "bin" }.delete()
        val prefs = mutablePreferencesOf(SettingsStore.PROVIDERS to missing, SettingsStore.WEB_SERVER_ENABLED to true)
        assertTrue(runCatching { resetFailedCredentialPreferences(prefs, vault) { error("archive unavailable") } }.isFailure)
        assertEquals(missing, prefs[SettingsStore.PROVIDERS])
        assertEquals(true, prefs[SettingsStore.WEB_SERVER_ENABLED])
    }

    @Test fun missingDeviceKeyCanBeExplicitlyRecoveredWithoutUsingOldCiphertext() {
        val alias = "mathstudy.qa.${java.util.UUID.randomUUID()}"
        val isolated = CredentialVault(context, alias)
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val ref = isolated.protect("synthetic lost key")
            store.deleteEntry(alias)
            assertTrue(runCatching { isolated.resolve(ref) }.isFailure)
            val prefs = mutablePreferencesOf(SettingsStore.PROVIDERS to ref)
            resetFailedCredentialPreferences(prefs, isolated) { }
            assertNull(prefs[SettingsStore.PROVIDERS])
            val replacement = isolated.protect("synthetic new key")
            assertEquals("synthetic new key", isolated.resolve(replacement))
            assertTrue(runCatching { isolated.resolve(ref) }.isFailure)
        } finally { store.deleteEntry(alias) }
    }

    @Test fun legacyMigrationPreservesValuesAndIsIdempotent() = runBlocking {
        val source = mutablePreferencesOf(SettingsStore.PROVIDERS to "[{\"apiKey\":\"SYNTHETIC\"}]")
        val migration = CredentialPreferencesMigration(vault)
        assertTrue(migration.shouldMigrate(source))
        val migrated = migration.migrate(source)
        assertEquals(source[SettingsStore.PROVIDERS], vault.resolve(migrated[SettingsStore.PROVIDERS]!!))
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals("[{\"apiKey\":\"SYNTHETIC\"}]", source[SettingsStore.PROVIDERS])
    }

    @Test fun failedMigrationPreservesOriginalPreferences() = runBlocking {
        File(directory, "credentials-v1").writeText("block directory creation")
        val source = mutablePreferencesOf(SettingsStore.PROVIDERS to "SYNTHETIC")
        assertTrue(runCatching { CredentialPreferencesMigration(vault).migrate(source) }.isFailure)
        assertEquals("SYNTHETIC", source[SettingsStore.PROVIDERS])
    }

    @Test fun applicationSettingsLoadWithoutPlaintextCredentialsOnDisk() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
        val disk = File(app.filesDir, "datastore/settings.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("Expected migrated credential references", disk.contains("credential-v1:"))
        settings.providers.forEach { provider ->
            val secrets = when (provider) {
                is ProviderSetting.OpenAI -> listOf(provider.apiKey)
                is ProviderSetting.Claude -> listOf(provider.apiKey)
                is ProviderSetting.Google -> listOf(provider.apiKey, provider.privateKey)
            } + provider.customHeaders.map { it.value } + provider.models.flatMap { it.customHeaders.map { header -> header.value } }
            secrets.filter { it.length >= 8 }.forEach {
                assertFalse("Runtime credential must not appear in preferences", disk.contains(it))
            }
        }
    }

    @Test fun realBackupArchiveExcludesCredentialsAndReferences() = runBlocking {
        val backupContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "backup-files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "backup-cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "backup-private").apply { mkdirs() }
            override fun getDatabasePath(name: String) = if (File(name).isAbsolute) File(name) else File(directory, "database/$name").apply { parentFile!!.mkdirs() }
        }
        val database = me.rerere.rikkahub.data.db.AppDatabaseFactory.create(backupContext)
        val manager = BackupManager(backupContext, database, GlobalContext.get().get<SettingsStore>(), JsonInstant)
        val archive = manager.createEncryptedBackup("credential fixture password!".toCharArray())
        val plain = File(directory, "verified-archive.zip")
        try {
            me.rerere.rikkahub.data.sync.EncryptedBackup.decrypt(archive, plain, "credential fixture password!".toCharArray())
            ZipFile(plain).use { zip ->
                val raw = zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use { it.readText() }
                assertFalse(raw.contains("credential-v1:"))
                val exported = JsonInstant.decodeFromString<Settings>(raw)
                assertFalse(exported.webServerEnabled)
                assertTrue(exported.webServerAccessPassword.isEmpty())
                assertTrue(exported.networkSetting.proxyPassword.isEmpty())
                assertTrue(exported.mcpServers.isEmpty())
                exported.providers.forEach { provider ->
                    assertTrue(provider.customHeaders.isEmpty())
                    assertTrue(provider.models.all { it.customHeaders.isEmpty() })
                    assertTrue(when (provider) {
                        is ProviderSetting.OpenAI -> provider.apiKey.isEmpty() && provider.baseUrl.isEmpty()
                        is ProviderSetting.Claude -> provider.apiKey.isEmpty() && provider.baseUrl.isEmpty()
                        is ProviderSetting.Google -> provider.apiKey.isEmpty() && provider.privateKey.isEmpty() && provider.baseUrl.isEmpty()
                    })
                }
            }
        } finally { archive.delete(); plain.delete(); database.close() }
    }
}
