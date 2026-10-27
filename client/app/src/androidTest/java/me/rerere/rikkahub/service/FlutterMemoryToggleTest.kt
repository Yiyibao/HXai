package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.data.db.AppDatabase
import kotlinx.serialization.json.Json

class FlutterMemoryToggleTest {
    @Test fun protectionWaitsForRealSettingsInsteadOfReportingStartupDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>()
        val originalOwner = settings.settingsFlow.first { !it.init }.activeProfileId
        val owner = settings.createLearnerProfile("QA initialized protection")
        settings.selectLearnerProfile(owner); settings.setLearnerMemoryEnabled(owner, false)
        val actual = settings.settingsFlow.value
        val store = k.get<LearningMemoryStore>(); store.read(owner)
        val facade = StudyHostFacade(context, settings, k.get(), k.get(), store, k.get(), k.get(), pickFiles = { emptyList() })
        settings.settingsFlow.value = actual.copy(init = true, learnerProfiles = actual.learnerProfiles.map {
            if (it.id == owner) it.copy(memoryEnabled = true) else it
        })
        val release = launch { delay(500); settings.settingsFlow.value = actual }
        try {
            val status = facade.getMemoryProtection(owner, 1)
            assertFalse("Startup defaults are not durable memory settings", status.configuredEnabled)
        } finally {
            release.join(); settings.settingsFlow.value = actual
            settings.selectLearnerProfile(originalOwner); facade.close()
        }
    }

    @Test fun protectedProfileCannotBeEnabledAndPayloadIsPreserved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val enabled = settings.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled
        val db = koin.get<me.rerere.rikkahub.data.db.AppDatabase>()
        val original = db.learningMemoryDao().get(owner)
        val payload = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
            me.rerere.rikkahub.feature.memory.MemoryState(fingerprintVersion = 1, recoveryBlocked = true))
        val facade = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        try {
            db.learningMemoryDao().put(me.rerere.rikkahub.feature.memory.LearningMemoryStateEntity(owner, payload))
            settings.setLearnerMemoryEnabled(owner, true)
            val status = facade.getMemoryProtection(owner, 1)
            assertEquals(owner, status.profileId)
            assertEquals(1L, status.contextEpoch)
            assertTrue(status.configuredEnabled)
            assertTrue(status.recoveryBlocked)
            assertTrue(facade.toggleAutoMemory(owner, false, 1).success)
            assertTrue(facade.getMemoryProtection(owner, 1).recoveryBlocked)
            settings.setLearnerMemoryEnabled(owner, false)
            assertFalse("Protection cannot be bypassed by enabling memory", facade.toggleAutoMemory(owner, true, 1).success)
            assertFalse(settings.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertEquals(payload, db.learningMemoryDao().get(owner)!!.payload)
            val added = facade.addMemory(owner, "保护中仍可管理哨兵", "PREFERENCE", "protected-manual", 1)
            assertTrue(added.success)
            val item = facade.getMemories(owner, "").single { it.id == added.idResult }
            assertTrue(facade.updateMemory(owner, item.id, "保护中修改哨兵", item.revision, "edit").success)
            assertTrue(facade.deleteMemory(owner, item.id, "delete").success)
            assertTrue(facade.getMemoryProtection(owner, 1).recoveryBlocked)
        } finally {
            if (original != null) db.learningMemoryDao().put(original)
            else db.openHelper.writableDatabase.execSQL("DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf(owner))
            settings.setLearnerMemoryEnabled(owner, enabled)
            facade.close()
        }
    }

    @Test fun bridgeTogglePersistsAndRejectsOtherProfile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val owner = original.activeProfileId
        val enabled = original.learnerProfiles.single { it.id == owner }.memoryEnabled
        val facade = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        try {
            assertTrue(facade.toggleAutoMemory(owner, !enabled, 1).success)
            assertEquals(!enabled, settings.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertEquals(!enabled, facade.getBootstrap(owner, 1L).profiles.single { it.id == owner }.memoryEnabled)
            assertEquals(!enabled, settings.settingsFlowRaw.first { !it.init }.learnerProfiles.single { it.id == owner }.memoryEnabled)
            val readback = facade.getMemoryProtection(owner, 1)
            assertEquals(!enabled, readback.configuredEnabled)
            assertFalse(readback.recoveryBlocked)
            assertTrue(facade.toggleAutoMemory(owner, enabled, 1).success)
            assertEquals(enabled, settings.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertTrue(runCatching { facade.toggleAutoMemory("other-profile", !enabled, 1) }.isFailure)
            assertTrue(runCatching { facade.getMemoryProtection("other-profile", 1) }.isFailure)
            assertTrue(runCatching { facade.getMemoryProtection(owner, 2) }.isFailure)
            assertTrue(runCatching { facade.toggleAutoMemory(owner, !enabled, 2) }.isFailure)
            assertEquals(enabled, settings.settingsFlowRaw.first { !it.init }.learnerProfiles.single { it.id == owner }.memoryEnabled)
        } finally {
            settings.setLearnerMemoryEnabled(owner, enabled)
            facade.close()
        }
    }

    @Test fun unreadableMemoryCannotMasqueradeAsHealthyAndCanStillBeDisabled() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val k = GlobalContext.get()
        val settings = k.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val owner = original.activeProfileId
        val enabled = original.learnerProfiles.single { it.id == owner }.memoryEnabled
        val name = "qa-memory-status-${java.util.UUID.randomUUID()}.db"
        val keyRoot = java.io.File(context.cacheDir, "qa-memory-status-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = java.io.File(keyRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = java.io.File(keyRoot, "no-backup").apply { mkdirs() }
        }
        val db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val store = LearningMemoryStore(db, MemoryFingerprints(isolated))
        val facade = StudyHostFacade(context, settings, k.get(), k.get(), store, k.get(), k.get(), pickFiles = { emptyList() })
        try {
            val json = Json { encodeDefaults = true }
            for (state in listOf(MemoryState(schemaVersion = 999),
                    MemoryState(fingerprintVersion = 1, suppressedFacts = setOf("opaque-missing-key")))) {
                val payload = json.encodeToString(state)
                db.learningMemoryDao().put(LearningMemoryStateEntity(owner, payload))
                settings.setLearnerMemoryEnabled(owner, true)
                assertTrue(runCatching { facade.getMemoryProtection(owner, 1) }.isFailure)
                assertFalse(facade.toggleAutoMemory(owner, true, 1).success)
                assertTrue(facade.toggleAutoMemory(owner, false, 1).success)
                assertFalse(settings.settingsFlowRaw.first { !it.init }.learnerProfiles.single { it.id == owner }.memoryEnabled)
                assertEquals(payload, db.learningMemoryDao().get(owner)!!.payload)
                assertFalse(java.io.File(isolated.filesDir, PortableMemoryKey.FILE_NAME).exists())
            }
        } finally {
            facade.close(); db.close(); context.deleteDatabase(name)
            check(keyRoot.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            keyRoot.deleteRecursively()
            settings.setLearnerMemoryEnabled(owner, enabled)
        }
    }

    @Test fun epochSurvivesProfileRoundTripAndWriteGuardRejectsBeforeCommit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val k = GlobalContext.get()
        val settings = k.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val owner = original.activeProfileId
        val enabled = original.learnerProfiles.single { it.id == owner }.memoryEnabled
        val other = settings.createLearnerProfile("QA memory epoch")
        settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == other } }
        val facade = StudyHostFacade(context, settings, k.get(), k.get(), k.get(), k.get(), k.get(), pickFiles = { emptyList() })
        try {
            var checked = false
            assertTrue(runCatching {
                settings.setLearnerMemoryEnabled(owner, !enabled) { persisted ->
                    assertEquals(owner, persisted); checked = true; error("revoked at write boundary")
                }
            }.isFailure)
            assertTrue(checked)
            assertEquals(enabled, settings.settingsFlowRaw.first { !it.init }.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertTrue(facade.switchProfile(other, 1).success)
            assertEquals(other, facade.getMemoryProtection(other, 2).profileId)
            assertTrue(runCatching { facade.toggleAutoMemory(owner, !enabled, 1) }.isFailure)
            assertTrue(facade.switchProfile(owner, 2).success)
            assertTrue(runCatching { facade.getMemoryProtection(owner, 1) }.isFailure)
            assertTrue(runCatching { facade.toggleAutoMemory(owner, !enabled, 1) }.isFailure)
            assertEquals(3L, facade.getMemoryProtection(owner, 3).contextEpoch)
            assertEquals(enabled, settings.settingsFlowRaw.first { !it.init }.learnerProfiles.single { it.id == owner }.memoryEnabled)
        } finally {
            settings.selectLearnerProfile(owner)
            settings.setLearnerMemoryEnabled(owner, enabled)
            facade.close()
        }
    }
}
