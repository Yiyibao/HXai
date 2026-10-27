package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ExecutorHandoffSchedulerTest {
    @Test fun realSchedulerJobStopsBeforeSyntheticTargetCanActivate(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-scheduler-handoff-${UUID.randomUUID()}.db"
        val model=Model(modelId="synthetic-model")
        val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:1")
        val assistant=Assistant(chatModelId=model.id,reasoningLevel=ReasoningLevel.HIGH)
        val settings=Settings(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
        val conversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=settings.activeProfileId)
        val scheduler=AgentScheduler(context,name)
        val task=scheduler.accept(conversation,"input","synthetic task",settings)
        val entered=CompletableDeferred<AgentLease>();val exited=CompletableDeferred<Unit>()
        val job=launch(start=CoroutineStart.UNDISPATCHED) {
            scheduler.run(task,{settings},{},{"SUCCEEDED"},{false}) {
                val active=requireNotNull(currentCoroutineContext()[AgentExecution])
                entered.complete(active.lease)
                try { awaitCancellation() } finally { exited.complete(Unit) }
            }
        }
        try {
            val old=entered.await()
            val receipt=scheduler.prepareHandoff(task.owner,task.id,"handoff","synthetic-cloud")
            assertTrue(job.isCompleted);assertTrue(exited.isCompleted);assertEquals("STOPPED",receipt.state)
            AgentTaskStore(context,name).use { authority ->
                val cloud=authority.activateHandoff(task.owner,task.id,"handoff","synthetic-cloud")
                authority.check(cloud);assertEquals(old.fence+1,cloud.fence)
                assertEquals(task.configurationRevision,cloud.task.configurationRevision)
                assertTrue(runCatching { authority.finish(old,"SUCCEEDED") }.isFailure)
                assertTrue(runCatching { scheduler.prepareHandoff(task.owner,task.id,"again","local") }.isFailure)
                val queued=scheduler.accept(conversation,"next","another synthetic task",settings)
                assertTrue(runCatching { scheduler.run(queued,{settings},{},{"SUCCEEDED"},{false}) { fail("Second independent slot") } }.exceptionOrNull() is AgentTaskBlocked)
                assertEquals("QUEUED",scheduler.get(queued.owner,queued.id).state)
            }
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin();scheduler.close();SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
        }
    }
    @Test fun executorWithoutBoundRunningJobCannotMintStopReceipt(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-unbound-handoff-${UUID.randomUUID()}.db"
        val scheduler=AgentScheduler(context,name)
        try {
            assertTrue(runCatching { scheduler.prepareHandoff("owner","absent","transfer","cloud") }.isFailure)
        } finally { scheduler.close();SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) }
    }
    @Test fun executorCannotRequestItsOwnHandoffAndLeaveAnUnprovableTransfer(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-self-handoff-${UUID.randomUUID()}.db"
        val model=Model(modelId="synthetic-model")
        val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:1")
        val assistant=Assistant(chatModelId=model.id)
        val settings=Settings(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
        val conversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=settings.activeProfileId)
        val scheduler=AgentScheduler(context,name)
        val t=scheduler.accept(conversation,"self","synthetic",settings)
        var rejectedBeforeWriting=false
        val job=launch {
            scheduler.run(t,{settings},{},{"INTERRUPTED"},{false}) {
                val error=runCatching { scheduler.prepareHandoff(t.owner,t.id,"self","cloud") }.exceptionOrNull()
                rejectedBeforeWriting=error is IllegalStateException
            }
        }
        try {
            job.join()
            AgentTaskStore(context,name).use { store ->
                assertNull("The executor cannot issue its own stop/join request",store.readHandoff(t.owner,t.id,"self"))
                assertTrue(rejectedBeforeWriting)
                assertEquals("INTERRUPTED",store.get(t.owner,t.id).state)
            }
        } finally { withContext(NonCancellable) { job.cancelAndJoin();scheduler.close();SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) } }
    }
    @Test fun cancellationBetweenValidationAndExecutorBindingCannotStrandClaimedSlot(): Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="qa-binding-race-${UUID.randomUUID()}.db"
        val model=Model(modelId="synthetic-model")
        val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:1")
        val assistant=Assistant(chatModelId=model.id)
        val settings=Settings(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
        val conversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=settings.activeProfileId)
        val scheduler=AgentScheduler(context,name)
        val t=scheduler.accept(conversation,"cancel-before-bind","synthetic",settings)
        lateinit var worker: Job
        worker=launch(start=CoroutineStart.LAZY) {
            runCatching {
                scheduler.run(t,{settings},{worker.cancel()},{"SUCCEEDED"},{false}) { fail("Canceled job must not execute") }
            }
        }
        try {
            worker.start();worker.join()
            AgentTaskStore(context,name).use { store ->
                val other=store.accept("other-owner","other-conversation","other","synthetic","ref","p","m","HIGH","config")
                val next=store.claim(other.owner,other.id)
                assertEquals(other.id,next.task.id)
                assertTrue(store.get(t.owner,t.id).state !in setOf("RUNNING","STOP_REQUESTED","UNCERTAIN_SIDE_EFFECT"))
            }
        } finally { withContext(NonCancellable) { worker.cancelAndJoin();scheduler.close();SQLiteCleanup.delete(context.noBackupFilesDir.resolve(name)) } }
    }
}
