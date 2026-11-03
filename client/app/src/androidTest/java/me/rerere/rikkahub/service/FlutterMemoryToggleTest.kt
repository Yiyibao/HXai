package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class FlutterMemoryToggleTest {
    @Test fun bridgeTogglePersistsAndRejectsOtherProfile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val owner = original.activeProfileId
        val enabled = original.learnerProfiles.single { it.id == owner }.memoryEnabled
        val facade = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        try {
            assertTrue(facade.toggleAutoMemory(owner, !enabled).success)
            assertEquals(!enabled, settings.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertEquals(!enabled, facade.getBootstrap(owner, 1L).profiles.single { it.id == owner }.memoryEnabled)
            assertEquals(!enabled, settings.settingsFlowRaw.first { !it.init }.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertTrue(facade.toggleAutoMemory(owner, enabled).success)
            assertEquals(enabled, settings.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled)
            assertTrue(runCatching { facade.toggleAutoMemory("other-profile", !enabled) }.isFailure)
        } finally {
            settings.setLearnerMemoryEnabled(owner, enabled)
            facade.close()
        }
    }
}
