package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.memory.MemoryType
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.UUID

/** Only invoked by an external process-stop verifier, never counted as ordinary tests. */
class MemoryProtectionProcessProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")).also { require(UUID.fromString(it).toString() == it) }
    private val folder get() = context.cacheDir.resolve("memory-protection-process-$fixture").apply { mkdirs() }
    private fun durable(name: String, value: String) { folder.resolve(name).outputStream().use { it.write(value.toByteArray()); it.fd.sync() } }
    @Test fun prepare(): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>()
        settings.settingsFlow.first { !it.init }
        val owner = settings.createLearnerProfile("QA protected restart")
        settings.selectLearnerProfile(owner)
        val store = k.get<LearningMemoryStore>()
        store.addManual(owner, "prepare", MemoryType.PREFERENCE, "进程重开原记录哨兵")
        store.change(owner) { it.copy(recoveryBlocked = true) to Unit }
        settings.setLearnerMemoryEnabled(owner, true)
        val facade = StudyHostFacade(context, settings, k.get(), k.get(), store, k.get(), k.get(), pickFiles = { emptyList() })
        assertTrue(facade.toggleAutoMemory(owner, false, 1).success)
        assertTrue(facade.getMemoryProtection(owner, 1).recoveryBlocked)
        durable("owner", owner)
        durable("payload", k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload)
        durable("ready", "committed")
        while (true) delay(1000)
    }
    @Test fun verify(): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val owner = folder.resolve("owner").readText()
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>()
        assertEquals(owner, settings.settingsFlow.first { !it.init }.activeProfileId)
        val facade = StudyHostFacade(context, settings, k.get(), k.get(), k.get(), k.get(), k.get(), pickFiles = { emptyList() })
        try {
            val status = facade.getMemoryProtection(owner, 1)
            assertEquals(owner, status.profileId); assertEquals(1L, status.contextEpoch)
            assertFalse(status.configuredEnabled); assertTrue(status.recoveryBlocked)
            assertFalse(facade.toggleAutoMemory(owner, true, 1).success)
            assertEquals(folder.resolve("payload").readText(), k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload)
            assertEquals("", k.get<LearningMemoryStore>().context(owner, "new-chat", "哨兵", true))
            println("MEMORY_PROTECTION_PROCESS_REOPEN_PASS")
        } finally { facade.close() }
    }
}
