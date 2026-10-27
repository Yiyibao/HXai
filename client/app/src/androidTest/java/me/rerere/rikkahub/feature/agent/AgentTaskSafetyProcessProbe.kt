package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.FileOutputStream
import java.util.UUID

/** Only invoked by the external kill/reopen runner on the synthetic QA package. */
@RunWith(AndroidJUnit4::class)
class AgentTaskSafetyProcessProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = requireNotNull(InstrumentationRegistry.getArguments().getString("fixture")).also {
        require(it.matches(Regex("[a-zA-Z0-9_-]+")))
    }
    private fun host(settings: SettingsStore): StudyHostFacade {
        val koin = GlobalContext.get()
        return StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
    }
    @Test fun prepare(): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val settings = GlobalContext.get().get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val host = host(settings)
        val epoch = host.getBootstrap(owner, 1L).contextEpoch
        val folder = context.cacheDir.resolve("safety-process-$fixture").apply { check(mkdirs()) }
        AgentTaskStore(context).use { store ->
            val task = store.accept(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "synthetic orphan revoke",
                "no-vault", "accepted-provider", "accepted-model", "HIGH", "accepted-config")
            val request = "lost-ack-$fixture"
            assertTrue(host.taskSafetyCommand(owner, task.id, request, epoch, task.revision, task.fence, "revoke").success)
            val current = store.get(owner, task.id)
            val control = store.control(owner, task.id)
            val record = listOf(owner, task.id, request, task.revision.toString(), task.fence.toString(),
                current.revision.toString(), control.grantEpoch.toString()).joinToString("\n")
            FileOutputStream(folder.resolve("ready")).use { it.write(record.toByteArray()); it.fd.sync() }
            println("TASK_SAFETY_COMMITTED_ACK_DISCARDED")
            // The external parent kills this real process. No successful instrumentation
            // return can be confused with the independent reopen verification.
            while(true) kotlinx.coroutines.delay(1000)
        }
    }
    @Test fun verify(): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val fields = context.cacheDir.resolve("safety-process-$fixture/ready").readLines()
        val settings = GlobalContext.get().get<SettingsStore>()
        settings.settingsFlow.first { !it.init }
        val host = host(settings)
        try {
            val epoch = host.getBootstrap(fields[0], 1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val before = store.snapshot(fields[0], fields[1])
                assertEquals("REVOKED", before.control.authorization)
                assertEquals(fields[5].toLong(), before.task.revision)
                assertEquals(fields[6].toLong(), before.control.grantEpoch)
                assertTrue(host.taskSafetyCommand(fields[0], fields[1], fields[2], epoch, fields[3].toLong(), fields[4].toLong(), "revoke").success)
                assertEquals(before, store.snapshot(fields[0], fields[1]))
                assertEquals("accepted-model", before.task.modelId)
                assertTrue(store.readUsage(fields[0], fields[1], null, null, null, 20).entries.isEmpty())
                println("TASK_SAFETY_REOPEN_ACK_VERIFIED")
            }
        } finally { host.close() }
    }
}
