package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentTaskSafetyRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun accept(store: AgentTaskStore, owner: String) = store.accept(owner, UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), "preserved safety goal", "unresolvable-synthetic-vault-reference",
        "accepted-provider", "accepted-model", "HIGH", "configuration-revision")
    private fun fixture(block: (AgentTaskStore, String) -> Unit) {
        val name = "qa-safety-${UUID.randomUUID()}.db"
        try { AgentTaskStore(context, name).use { block(it, name) } }
        finally { SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    private fun apply(store: AgentTaskStore, task: AgentTask, action: String, request: String = UUID.randomUUID().toString()) =
        store.command(task.owner, task.id, request, task.revision, task.fence, action, safetyOnly = true)

    @Test fun taskCenterSafetyUsesOwnerTaskWithoutConversationOrVault(): Unit = runBlocking {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val host = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        try {
            val epoch = host.getBootstrap(owner, 1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val older = accept(store, owner); val newer = accept(store, owner)
                val untouched = store.snapshot(owner, newer.id)
                assertFalse(host.getAgentTaskDetail(owner, older.id, null, 20, epoch).conversationAvailable)
                // The existing conversation route must remain restricted.
                assertFalse(host.taskCommand(owner, older.conversationId, older.id, "old-route", epoch,
                    older.revision, older.fence, "pause", "").success)
                val request = UUID.randomUUID().toString()
                val ack = host.taskSafetyCommand(owner, older.id, request, epoch, older.revision, older.fence, "pause")
                assertTrue(ack.success); assertEquals(older.id, ack.idResult)
                val paused = store.snapshot(owner, older.id)
                assertEquals("PAUSED_BY_USER", paused.task.state); assertTrue(paused.task.manualPaused)
                assertEquals(older.configurationReference, paused.task.configurationReference)
                assertEquals(older.modelId, paused.task.modelId); assertEquals(older.reasoning, paused.task.reasoning)
                assertTrue(host.taskSafetyCommand(owner, older.id, request, epoch, older.revision, older.fence, "pause").success)
                assertEquals(paused, store.snapshot(owner, older.id))
                assertEquals(untouched, store.snapshot(owner, newer.id))
                val revoked = host.taskSafetyCommand(owner, older.id, "revoke", epoch, paused.task.revision, paused.task.fence, "revoke")
                assertTrue(revoked.success); assertEquals("REVOKED", store.control(owner, older.id).authorization)
            }
        } finally { host.close() }
    }

    @Test fun hostRejectsForeignEpochFenceRevisionAndNonSafetyCommands(): Unit = runBlocking {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val host = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        try {
            val epoch = host.getBootstrap(owner, 1L).contextEpoch
            AgentTaskStore(context).use { store ->
                val task = accept(store, owner); val before = store.snapshot(owner, task.id)
                suspend fun reject(who: String = owner, expectedEpoch: Long = epoch, revision: Long = task.revision,
                    fence: Long = task.fence, action: String = "cancel", id: String = task.id) {
                    val ack = host.taskSafetyCommand(who, id, UUID.randomUUID().toString(), expectedEpoch, revision, fence, action)
                    assertFalse(ack.success); assertEquals("TASK_PRECONDITION_REJECTED", ack.error?.code)
                    assertEquals(before, store.snapshot(owner, task.id))
                }
                reject(who = "foreign"); reject(expectedEpoch = epoch - 1); reject(revision = task.revision + 1)
                reject(fence = task.fence + 1); reject(id = UUID.randomUUID().toString())
                for (action in listOf("resume", "supplement", "provide_input", "confirm_device", "device_available", "wait_device", "")) reject(action = action)
            }
        } finally { host.close() }
    }

    @Test fun sameRequestSurvivesReopenAndCannotChangeIntent() = fixture { store, name ->
        val task = accept(store, "owner")
        val paused = apply(store, task, "pause", "lost-ack")
        val snapshot = store.snapshot(task.owner, task.id)
        store.close()
        AgentTaskStore(context, name).use { reopened ->
            assertEquals(paused, apply(reopened, task, "pause", "lost-ack"))
            assertEquals(snapshot, reopened.snapshot(task.owner, task.id))
            assertTrue(runCatching { apply(reopened, task, "cancel", "lost-ack") }.isFailure)
            assertTrue(runCatching { apply(reopened, task.copy(revision = paused.revision), "pause", "lost-ack") }.isFailure)
            assertEquals(snapshot, reopened.snapshot(task.owner, task.id))
            val canceled = apply(reopened, paused, "cancel")
            assertEquals("CANCELED", canceled.state); assertTrue(reopened.control(task.owner, task.id).cancelRequested)
        }
    }

    @Test fun terminalRaceCannotRewriteSuccessButOriginalAckStillWorks() = fixture { store, _ ->
        val task = accept(store, "owner")
        val canceled = apply(store, task, "cancel", "original")
        assertEquals(canceled, apply(store, task, "cancel", "original"))
        val before = store.snapshot(task.owner, task.id)
        for (action in listOf("pause", "cancel")) assertTrue(runCatching { apply(store, canceled, action) }.isFailure)
        assertEquals(before, store.snapshot(task.owner, task.id))
        val success = accept(store, "owner")
        // Simulate completion winning between a UI read and the command transaction.
        store.writableDatabase.execSQL("UPDATE tasks SET state='SUCCEEDED',revision=revision+1 WHERE id=?", arrayOf(success.id))
        assertTrue(runCatching { apply(store, success, "cancel") }.isFailure)
        val completed = store.get(success.owner, success.id)
        for (action in listOf("pause", "cancel")) assertTrue(runCatching { apply(store, completed, action) }.isFailure)
        assertEquals(completed, store.get(success.owner, success.id))
        apply(store, completed, "revoke")
        assertEquals("SUCCEEDED", store.get(success.owner, success.id).state)
        assertEquals("REVOKED", store.control(success.owner, success.id).authorization)
    }

    @Test fun unknownCancellationAndRevocationPreserveSlotReceiptsAndIndependentWaits() = fixture { store, _ ->
        val task = accept(store, "owner"); val lease = store.claim(task.owner, task.id)
        store.beginCall(lease, "pending-write", "create_material_file", "synthetic write intent")
        val waiting = store.get(task.owner, task.id)
        store.command(task.owner, task.id, "input", waiting.revision, waiting.fence, "wait_input")
        val device = store.get(task.owner, task.id)
        store.command(task.owner, task.id, "device", device.revision, device.fence, "require_device_confirmation")
        val stopping = apply(store, store.get(task.owner, task.id), "cancel", "cancel-pending")
        assertEquals("STOP_REQUESTED", stopping.state)
        store.finish(lease, "INTERRUPTED")
        val unknown = store.get(task.owner, task.id)
        assertEquals("UNCERTAIN_SIDE_EFFECT", unknown.state)
        val plan = store.plan(task.owner, task.id)
        val revoked = apply(store, unknown, "revoke", "revoke-pending")
        assertEquals("UNCERTAIN_SIDE_EFFECT", revoked.state)
        val control = store.control(task.owner, task.id)
        assertTrue(control.inputBlocked); assertTrue(control.deviceConfirmation); assertTrue(control.cancelRequested)
        assertEquals("REVOKED", control.authorization); assertEquals(plan, store.plan(task.owner, task.id))
        val calls = store.readTaskDetail(task.owner, task.id, null, 20).calls
        assertEquals("UNKNOWN", calls.single().state)
        val other = accept(store, "other")
        assertTrue(runCatching { store.claim(other.owner, other.id) }.isFailure)
        assertTrue(runCatching { store.check(lease) }.isFailure)
    }

    @Test fun safetyCommandCancelsActualSchedulerJobBeforeAcknowledging(): Unit = runBlocking {
        val model = me.rerere.ai.provider.Model(modelId = "synthetic-no-network")
        val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(model), apiKey = "synthetic-only", baseUrl = "http://127.0.0.1:1")
        val assistant = me.rerere.rikkahub.data.model.Assistant(chatModelId = model.id)
        val settings = me.rerere.rikkahub.data.datastore.Settings(providers = listOf(provider), assistants = listOf(assistant),
            assistantId = assistant.id, chatModelId = model.id)
        for (action in listOf("pause", "cancel", "revoke")) {
            val name = "qa-live-safety-${UUID.randomUUID()}.db"
            val scheduler = AgentScheduler(context, name)
            val conversation = me.rerere.rikkahub.data.model.Conversation.ofId(kotlin.uuid.Uuid.random(), assistant.id)
                .copy(profileId = settings.activeProfileId)
            val task = scheduler.accept(conversation, "live", "synthetic local waiting block", settings)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            var stopped = false
            val running = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                scheduler.run(task, { settings }, {}, { "COMPLETED_UNVERIFIED" }, { false }) {
                    entered.complete(Unit)
                    try { kotlinx.coroutines.awaitCancellation() } finally { stopped = true }
                }
            }
            try {
                kotlinx.coroutines.withTimeout(5000) { entered.await() }
                val before = scheduler.get(task.owner, task.id)
                val ack = scheduler.command(task.owner, task.id, "safety-stop", before.revision, before.fence, action, "", safetyOnly = true)
                assertTrue(stopped); assertTrue(running.isCompleted); assertTrue(running.isCancelled)
                assertEquals(when(action) { "pause" -> "PAUSED_BY_USER"; "cancel" -> "CANCELED"; else -> "WAITING_AUTHORIZATION" }, ack.state)
                assertEquals(task.modelId, ack.modelId); assertEquals(task.configurationReference, ack.configurationReference)
                assertEquals(ack, scheduler.command(task.owner, task.id, "safety-stop", before.revision, before.fence, action, "", safetyOnly = true))
                if(action == "revoke") assertEquals("REVOKED", scheduler.control(task.owner, task.id).authorization)
            } finally {
                running.cancel(); running.join(); scheduler.close()
                SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name))
            }
        }
    }
}
