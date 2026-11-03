package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AgentTaskRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun task(store: AgentTaskStore, owner: String = "owner-a", request: String = UUID.randomUUID().toString()) =
        store.accept(owner,"conversation-$owner",request,"input","encrypted-reference","provider-a","model-a","HIGH","revision-a")
    private fun selection(task: AgentTask) = AgentRequestSelection(task.providerId,task.modelId,task.reasoning,task.configurationReference)
    private fun withStore(block: (AgentTaskStore,String) -> Unit) {
        val name = "qa-agent-${UUID.randomUUID()}.db"
        val store = AgentTaskStore(context,name)
        try { block(store,name) }
        finally { store.close(); SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    @Test fun crossProfileClaimsAndOldFencePublicationAreRejected() = withStore { store,_ ->
        val first = task(store); val second = task(store,"owner-b")
        val lease = store.claim(first.owner,first.id)
        assertTrue(runCatching { store.claim(second.owner,second.id) }.exceptionOrNull() is AgentTaskBlocked)
        store.finish(lease,"WAITING_INPUT")
        val next = store.claim(second.owner,second.id)
        assertTrue(next.fence > lease.fence)
        assertTrue(runCatching { store.beginCall(lease,"late","create_material_file","{}") }.isFailure)
        store.finish(next,"WAITING_INPUT")
    }
    @Test fun uncertainWriteRetainsSlotAcrossDatabaseReopenWithoutReplay() = withStore { store,name ->
        val first = task(store); val other = task(store,"owner-b")
        val lease = store.claim(first.owner,first.id)
        store.beginCall(lease,"write-once","create_material_file","{\"body\":\"once\"}")
        val file = File(context.cacheDir,"qa-agent-${UUID.randomUUID()}.txt")
        try {
            file.writeText("effect-before-receipt")
            store.close()
            AgentTaskStore(context,name).use { reopened ->
                reopened.recover()
                assertEquals("UNCERTAIN_SIDE_EFFECT",reopened.get(first.owner,first.id).state)
                assertTrue(runCatching { reopened.claim(other.owner,other.id) }.exceptionOrNull() is AgentTaskBlocked)
                assertTrue(runCatching { reopened.claim(first.owner,first.id) }.exceptionOrNull() is AgentTaskBlocked)
                assertEquals("effect-before-receipt",file.readText())
            }
        } finally { file.delete() }
    }
    @Test fun duplicateRequestsKeepOriginalModelAndDifferentIntentIsRejected() = withStore { store,_ ->
        val first = task(store,request="stable")
        val retry = store.accept(first.owner,first.conversationId,"stable","input","replacement","provider-b","model-b","LOW","revision-b")
        assertEquals(first,retry)
        assertTrue(runCatching { store.accept(first.owner,first.conversationId,"stable","changed","replacement","provider-b","model-b","LOW","revision-b") }.isFailure)
        assertEquals(first,store.get(first.owner,first.id))
    }
    @Test fun pausedMainTaskCannotBeClaimedWithoutExplicitResume() = withStore { store,_ ->
        val first = task(store)
        val lease = store.claim(first.owner,first.id)
        store.finish(lease,"PAUSED_BY_USER")
        assertTrue("claim must not silently resume a main task",
            runCatching { store.claim(first.owner,first.id) }.isFailure)
        assertEquals("PAUSED_BY_USER",store.get(first.owner,first.id).state)
    }
    @Test fun waitingInputCannotBeClaimedByOrdinaryQueueDispatch() = withStore { store,_ ->
        val first = task(store)
        val lease = store.claim(first.owner,first.id)
        store.finish(lease,"WAITING_INPUT")
        assertTrue("waiting input requires a trusted explicit resume",
            runCatching { store.claim(first.owner,first.id) }.isFailure)
        assertEquals("WAITING_INPUT",store.get(first.owner,first.id).state)
    }
    @Test fun queuedEditDoesNotRelockModelAndActiveInputCannotChange() = withStore { store,_ ->
        val first = task(store)
        store.reviseQueued(first.owner,first.id,"edited")
        assertEquals(first.configurationReference,store.get(first.owner,first.id).configurationReference)
        val lease = store.claim(first.owner,first.id)
        store.reviseQueued(first.owner,first.id,"edited")
        assertTrue(runCatching { store.reviseQueued(first.owner,first.id,"changed after dispatch") }.isFailure)
        store.finish(lease,"WAITING_INPUT")
    }
    @Test fun unknownUsageRemainsNullAndUnverifiedResultCannotCompleteGoal() = withStore { store,_ ->
        val first = task(store); val lease = store.claim(first.owner,first.id)
        val request = store.beginRequest(lease,"main","synthetic prompt",selection(first))
        store.finishRequest(lease,request,null)
        store.readableDatabase.rawQuery("SELECT provenance,prompt_tokens,completion_tokens FROM usage WHERE request_id=?",arrayOf(request)).use {
            assertTrue(it.moveToFirst()); assertEquals("unknown",it.getString(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
        }
        store.beginCall(lease,"result","math_verify","unsupported input")
        store.finishCall(lease,"result","unsupported-hash",false)
        store.finish(lease,"SUCCEEDED")
        assertEquals("COMPLETED_UNVERIFIED",store.get(first.owner,first.id).state)
    }
    @Test fun unresolvedOcrRequestPreventsFurtherProviderDispatch() = withStore { store,_ ->
        val first = task(store); val lease = store.claim(first.owner,first.id)
        store.beginRequest(lease,"ocr","synthetic image digest",selection(first))
        assertTrue(runCatching { store.beginRequest(lease,"main","redacted OCR failure",selection(first)) }.exceptionOrNull() is AgentTaskBlocked)
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM usage WHERE task_id=?",arrayOf(first.id)).use {
            assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0))
        }
        store.finish(lease,"FAILED_BLOCKED")
        assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(first.owner,first.id).state)
    }
    @Test fun usageTimestampMigrationPreservesUnknownEffectsAndHistoricalUnknownTime() = withStore { store,name ->
        val first = task(store); val lease = store.claim(first.owner,first.id)
        val request = store.beginRequest(lease,"main","synthetic prompt",selection(first))
        store.writableDatabase.execSQL("CREATE TABLE usage_v1 (request_id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id), role TEXT NOT NULL, provenance TEXT NOT NULL, prompt_tokens INTEGER, completion_tokens INTEGER, cached_tokens INTEGER)")
        store.writableDatabase.execSQL("INSERT INTO usage_v1 SELECT request_id,task_id,role,provenance,prompt_tokens,completion_tokens,cached_tokens FROM usage")
        store.writableDatabase.execSQL("DROP TABLE usage")
        store.writableDatabase.execSQL("ALTER TABLE usage_v1 RENAME TO usage")
        store.writableDatabase.execSQL("ALTER TABLE tasks DROP COLUMN manual_pause")
        store.writableDatabase.version = 1
        store.close()
        AgentTaskStore(context,name).use { migrated ->
            migrated.recover()
            assertEquals(8,migrated.readableDatabase.version)
            assertEquals("UNCERTAIN_SIDE_EFFECT",migrated.get(first.owner,first.id).state)
            migrated.readableDatabase.rawQuery("SELECT provenance,created_at FROM usage WHERE request_id=?",arrayOf(request)).use {
                assertTrue(it.moveToFirst()); assertEquals("unknown",it.getString(0)); assertTrue(it.isNull(1))
            }
            assertTrue(runCatching { migrated.claim(first.owner,first.id) }.exceptionOrNull() is AgentTaskBlocked)
        }
    }
    @Test fun explicitResumePreservesModelAndAdvancesFenceWithoutUnblockingUnknown() = withStore { store,_ ->
        val first=task(store); val lease=store.claim(first.owner,first.id)
        store.finish(lease,"PAUSED_BY_USER")
        assertTrue(store.get(first.owner,first.id).manualPaused)
        assertTrue(runCatching { store.resume("other-owner",first.id) }.isFailure)
        assertTrue(runCatching { store.resume(first.owner,first.id,allowManualPause=false) }.isFailure)
        assertTrue(store.get(first.owner,first.id).manualPaused)
        store.resume(first.owner,first.id)
        assertFalse(store.get(first.owner,first.id).manualPaused)
        val resumed=store.claim(first.owner,first.id)
        assertTrue(resumed.fence>lease.fence)
        assertEquals(first.configurationReference,resumed.task.configurationReference)
        store.beginCall(resumed,"unconfirmed","create_material_file","{}")
        store.finish(resumed,"PAUSED_BY_USER")
        assertTrue(store.get(first.owner,first.id).manualPaused)
        assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(first.owner,first.id).state)
        assertTrue(runCatching { store.resume(first.owner,first.id) }.isFailure)
        assertTrue(runCatching { store.claim(first.owner,first.id) }.isFailure)
    }
    @Test fun schema4MigrationKeepsReportedUsageAndStickyPause() = withStore { store,name ->
        val first=task(store); val lease=store.claim(first.owner,first.id)
        val request=store.beginRequest(lease,"main","synthetic",selection(first))
        store.finishRequest(lease,request,me.rerere.ai.core.TokenUsage(17,8,null,25))
        store.finish(lease,"PAUSED_BY_USER")
        store.writableDatabase.execSQL("ALTER TABLE tasks DROP COLUMN manual_pause")
        store.writableDatabase.version=4
        store.close()
        AgentTaskStore(context,name).use { migrated ->
            assertEquals(8,migrated.readableDatabase.version)
            assertTrue(migrated.get(first.owner,first.id).manualPaused)
            assertEquals(lease.fence,migrated.get(first.owner,first.id).fence)
            assertTrue(runCatching { migrated.claim(first.owner,first.id) }.isFailure)
            migrated.readableDatabase.rawQuery("SELECT provenance,prompt_tokens,cached_tokens FROM usage WHERE request_id=?",arrayOf(request)).use {
                assertTrue(it.moveToFirst()); assertEquals("reported",it.getString(0)); assertEquals(17,it.getInt(1)); assertTrue(it.isNull(2))
            }
            migrated.resume(first.owner,first.id)
            migrated.finish(migrated.claim(first.owner,first.id),"COMPLETED_UNVERIFIED")
        }
    }
    @Test fun stoppingRunningTaskPreventsNewDispatchButRetainsUnknownSlot() = withStore { store,_ ->
        val first=task(store); val lease=store.claim(first.owner,first.id)
        store.beginRequest(lease,"main","synthetic",selection(first))
        store.pausePending(first.owner,first.conversationId)
        assertTrue(runCatching { store.check(lease) }.isFailure)
        assertTrue(runCatching { store.beginCall(lease,"late","create_material_file","{}") }.isFailure)
        store.finish(lease,"PAUSED_BY_USER")
        assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(first.owner,first.id).state)
        assertTrue(store.get(first.owner,first.id).manualPaused)
    }
    @Test fun secondSchedulerCannotRecoverTheLiveExecutionDomain() {
        val name="qa-agent-${UUID.randomUUID()}.db"
        val scheduler=AgentScheduler(context,name)
        try {
            assertTrue(runCatching { AgentScheduler(context,name) }.isFailure)
        } finally { scheduler.close(); SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
        AgentScheduler(context,name).close()
        SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name))
    }
    @Test fun queuedSettingsMutationCannotChangeEncryptedExecutionSnapshot(): Unit = runBlocking {
        val name = "qa-agent-${UUID.randomUUID()}.db"
        val model = Model(modelId="synthetic-model-a")
        val provider = ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:1")
        val assistant = Assistant(chatModelId=model.id,reasoningLevel=ReasoningLevel.HIGH)
        val settings = Settings(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
        val scheduler = AgentScheduler(context,name)
        val conversation = Conversation.ofId(Uuid.random(),assistant.id)
        val accepted = scheduler.accept(conversation,"input","question",settings)
        provider.apiKey = "synthetic-changed"
        provider.baseUrl = "http://127.0.0.1:2"
        try {
            scheduler.run(accepted,{ settings },{}, { "WAITING_INPUT" }, { false }) {
                val frozen = requireNotNull(currentCoroutineContext()[AgentExecution]).configuration
                assertEquals(ReasoningLevel.HIGH,frozen.assistant.reasoningLevel)
                val locked = frozen.settings.providers.single() as ProviderSetting.OpenAI
                assertEquals("synthetic-only",locked.apiKey)
                assertEquals("http://127.0.0.1:1",locked.baseUrl)
                assertEquals(model.id,frozen.assistant.chatModelId)
            }
        } finally { scheduler.close(); SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
        Unit
    }
    @Test fun stoppingQueuedRoleCannotDispatchAfterOtherConversationFinishes(): Unit = runBlocking {
        val name="qa-agent-${UUID.randomUUID()}.db"
        val model=Model(modelId="synthetic-role")
        val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:1")
        val assistant=Assistant(chatModelId=model.id)
        val settings=Settings(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
        val firstConversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=settings.activeProfileId)
        val roleConversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=settings.activeProfileId)
        val scheduler=AgentScheduler(context,name)
        val release=CompletableDeferred<Unit>()
        val firstTask=scheduler.accept(firstConversation,"first","synthetic request",settings)
        val first=launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            scheduler.run(firstTask,{ settings },{}, { "WAITING_INPUT" }, { false }) { release.await() }
        }
        val dispatches=java.util.concurrent.atomic.AtomicInteger()
        val role=launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            scheduler.requestRole(roleConversation,"title",settings,model,ReasoningLevel.LOW,listOf(me.rerere.ai.ui.UIMessage.user("synthetic title")),
                liveSettings={ settings },validate={}) { _,_ ->
                dispatches.incrementAndGet()
                error("Paused role must not dispatch")
            }
        }
        try {
            scheduler.stop(roleConversation)
            AgentTaskStore(context,name).use { store ->
                val id=store.readableDatabase.rawQuery("SELECT id FROM tasks WHERE request_id LIKE 'role:title:%'",null).use { check(it.moveToFirst()); it.getString(0) }
                assertTrue(role.isCancelled)
                assertEquals("PAUSED_BY_USER",store.get(settings.activeProfileId,id).state)
                assertEquals("RUNNING",store.get(settings.activeProfileId,firstTask.id).state)
                release.complete(Unit); first.join()
                assertTrue(runCatching { store.claim(settings.activeProfileId,id) }.exceptionOrNull() is IllegalStateException)
                assertEquals(0,dispatches.get())
                assertEquals("WAITING_INPUT",store.get(settings.activeProfileId,firstTask.id).state)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                release.complete(Unit); first.join(); role.cancel(); role.join()
                scheduler.close(); SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name))
            }
        }
    }
}
internal object SQLiteCleanup {
    fun delete(file: File) { listOf(file,File(file.path+"-journal"),File(file.path+"-wal"),File(file.path+"-shm")).forEach { it.delete() } }
}
