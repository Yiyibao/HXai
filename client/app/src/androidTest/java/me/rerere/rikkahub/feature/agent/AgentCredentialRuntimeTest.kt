package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.CountDownLatch
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AgentCredentialRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val settingsStore get()=GlobalContext.get().get<SettingsStore>()
    private val providers get()=GlobalContext.get().get<ProviderManager>()
    private suspend fun request(scheduler: AgentScheduler,task: AgentTask,resume: Boolean=false): TextGenerationResult =
        scheduler.run(task,{ settingsStore.settingsFlow.value },{},{ "COMPLETED_UNVERIFIED" },{ false },
            resumeRequested=resume,resumeManualPause=resume) {
            val execution=requireNotNull(currentCoroutineContext()[AgentExecution])
            val config=execution.configuration
            val model=requireNotNull(config.settings.findModelById(config.assistant.chatModelId ?: config.settings.chatModelId))
            val provider=requireNotNull(model.findProvider(config.settings.providers))
            withContext(execution.accounting("main")) {
                providers.getProviderByType(provider).generateText(provider,listOf(UIMessage.user("synthetic input")),
                    TextGenerationParams(model,reasoningLevel=config.assistant.reasoningLevel))
            }
        }
    private suspend fun command(scheduler: AgentScheduler,task: AgentTask,action: String,payload: String="") {
        val current=scheduler.get(task.owner,task.id)
        scheduler.command(task.owner,task.id,Uuid.random().toString(),current.revision,current.fence,action,payload)
    }
    private suspend fun fixture(server: LocalModelServer,block: suspend (String,AgentScheduler,AgentTask,Settings)->Unit) {
        val original=settingsStore.settingsFlow.first { !it.init }
        val model=Model(modelId="qa-locked-model")
        val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-old-key",baseUrl=server.baseUrl)
        val assistant=Assistant(chatModelId=model.id)
        val config=original.copy(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id,
            networkSetting=original.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword=""))
        val name="qa-credentials-${Uuid.random()}.db"
        settingsStore.update(config)
        settingsStore.settingsFlow.first { it.chatModelId==model.id }
        val scheduler=AgentScheduler(context,name)
        try {
            val conversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=config.activeProfileId)
            val task=scheduler.accept(conversation,Uuid.random().toString(),"synthetic input",config)
            assertTrue(runCatching { request(scheduler,task) }.isFailure)
            assertEquals("EXPIRED",scheduler.control(task.owner,task.id).authorization)
            assertEquals("WAITING_AUTHORIZATION",scheduler.get(task.owner,task.id).state)
            block(name,scheduler,task,config)
        } finally {
            runCatching { scheduler.close() }.getOrThrow()
            settingsStore.update(original)
            for(suffix in listOf("","-wal","-shm")) context.noBackupFilesDir.resolve(name+suffix).delete()
        }
    }
    private fun server(disconnect: Boolean=false)=LocalModelServer(
        status={ headers,_ -> if(headers["authorization"]=="Bearer synthetic-new-key") 200 else 401 },
        disconnect={ headers,_ -> disconnect && headers["authorization"]=="Bearer synthetic-new-key" },
        allowClientDisconnect=true) { LocalModelServer.completion("OK") }

    @Test fun real401AndKeyValidationPreserveFrozenModelPauseInputAndDeviceWaits(): Unit=runBlocking {
        server().use { server -> fixture(server) { name,scheduler,task,config ->
            command(scheduler,task,"wait_input");command(scheduler,task,"wait_device")
            command(scheduler,task,"require_device_confirmation");command(scheduler,task,"pause")
            val accepted=scheduler.get(task.owner,task.id)
            val provider=config.providers.single() as ProviderSetting.OpenAI
            settingsStore.update(config.copy(providers=listOf(provider.copy(models=provider.models.map { it.copy(modelId="qa-new-live-model") }))))
            val requestId=Uuid.random().toString()
            val updated=scheduler.updateCredential(task.owner,task.id,requestId,accepted.revision,accepted.fence,"synthetic-new-key",
                providers,{ settingsStore.settingsFlow.value },{})
            assertEquals("ACTIVE",scheduler.control(task.owner,task.id).authorization)
            assertTrue(updated.manualPaused)
            val control=scheduler.control(task.owner,task.id)
            assertTrue(control.inputBlocked);assertTrue(control.deviceBlocked);assertTrue(control.deviceConfirmation)
            assertEquals("PAUSED_BY_USER",updated.state)
            assertTrue(runCatching { request(scheduler,task,resume=true) }.isFailure)
            assertTrue(runCatching { scheduler.updateCredential("other-owner",task.id,requestId,accepted.revision,accepted.fence,
                "synthetic-new-key",providers,{ settingsStore.settingsFlow.value },{}) }.isFailure)
            scheduler.updateCredential(task.owner,task.id,requestId,accepted.revision,accepted.fence,"synthetic-new-key",
                providers,{ settingsStore.settingsFlow.value },{})
            assertEquals("One rejected request and one validation; lost command ACK must not re-bill",2,server.bodies.size)
            command(scheduler,task,"provide_input","synthetic required input");command(scheduler,task,"device_available")
            command(scheduler,task,"confirm_device")
            assertEquals("OK",request(scheduler,task,resume=true).message.toText())
            assertEquals(listOf("qa-locked-model","qa-locked-model","qa-locked-model"),server.bodies.map { Json.parseToJsonElement(it).jsonObject.getValue("model").jsonPrimitive.content })
            assertEquals("Bearer synthetic-new-key",server.headers.last()["authorization"])
            assertEquals("qa-locked-model",scheduler.modelIdentity(task.owner,task.id).modelName)
            assertEquals(1,server.maxActive.get())
            AgentTaskStore(context,name).use { store ->
                store.readableDatabase.rawQuery("SELECT provenance,prompt_tokens,completion_tokens,cached_tokens,total_tokens FROM usage",null).use { cursor ->
                    assertEquals("Each of the three physical requests has one durable usage row",3,cursor.count)
                    while(cursor.moveToNext()) { assertEquals("unknown",cursor.getString(0));for(column in 1..4) assertTrue(cursor.isNull(column)) }
                }
            }
        } }
    }
    @Test fun knownCredentialCommandAckSurvivesReopenWithoutAnotherPhysicalRequest(): Unit=runBlocking {
        server().use { server -> fixture(server) { name,scheduler,task,_ ->
            val accepted=scheduler.get(task.owner,task.id)
            val requestId=Uuid.random().toString()
            scheduler.updateCredential(task.owner,task.id,requestId,accepted.revision,accepted.fence,"synthetic-new-key",
                providers,{ settingsStore.settingsFlow.value },{})
            scheduler.close()
            AgentScheduler(context,name).use { reopened ->
                reopened.continueCredential(task.owner,task.id,requestId,providers,{ settingsStore.settingsFlow.value },{})
                assertEquals("ACTIVE",reopened.control(task.owner,task.id).authorization)
                assertEquals("APPLIED",reopened.snapshot(task.owner,task.id).credential?.state)
                assertEquals(2,server.bodies.size)
                assertEquals("OK",request(reopened,task,resume=true).message.toText())
                assertEquals("Bearer synthetic-new-key",server.headers.last()["authorization"])
            }
        } }
    }
    @Test fun revocationDuringActualVerificationNeverRestoresOriginalGrant(): Unit=runBlocking {
        server().use { server -> fixture(server) { name,scheduler,task,_ ->
            val accepted=scheduler.get(task.owner,task.id)
            val requestId=Uuid.random().toString()
            val barrier=CountDownLatch(1);server.barrier=barrier
            val verification=async(Dispatchers.IO) { runCatching {
                scheduler.updateCredential(task.owner,task.id,requestId,accepted.revision,accepted.fence,"synthetic-new-key",
                    providers,{ settingsStore.settingsFlow.value },{})
            } }
            try {
                withTimeout(10_000) { while(server.bodies.size<2) delay(10) }
                command(scheduler,task,"revoke")
                barrier.countDown()
                assertTrue(withTimeout(10_000) { verification.await() }.isFailure)
                assertEquals("REVOKED",scheduler.control(task.owner,task.id).authorization)
                scheduler.close()
                AgentScheduler(context,name).use { reopened ->
                    assertTrue(runCatching { reopened.continueCredential(task.owner,task.id,requestId,providers,{ settingsStore.settingsFlow.value },{}) }.isFailure)
                    assertEquals("REVOKED",reopened.control(task.owner,task.id).authorization)
                    assertEquals(2,server.bodies.size)
                }
            } finally { barrier.countDown();verification.cancelAndJoin() }
        } }
    }
    @Test fun lostPhysicalAckRetainsGlobalSlotAndCannotBeBlindlyReplayedAfterRestart(): Unit=runBlocking {
        server(disconnect=true).use { server -> fixture(server) { name,scheduler,task,config ->
            val accepted=scheduler.get(task.owner,task.id)
            val requestId=Uuid.random().toString()
            assertTrue(runCatching { scheduler.updateCredential(task.owner,task.id,requestId,accepted.revision,accepted.fence,
                "synthetic-new-key",providers,{ settingsStore.settingsFlow.value },{}) }.isFailure)
            assertEquals("UNCERTAIN_SIDE_EFFECT",scheduler.snapshot(task.owner,task.id).credential?.state)
            scheduler.close()
            AgentScheduler(context,name).use { reopened ->
                assertTrue(runCatching { reopened.continueCredential(task.owner,task.id,requestId,providers,{ settingsStore.settingsFlow.value },{}) }.isFailure)
                val other=reopened.accept(Conversation.ofId(Uuid.random(),config.assistantId).copy(profileId=task.owner),Uuid.random().toString(),"other synthetic chat",config)
                assertTrue(runCatching { request(reopened,other) }.exceptionOrNull() is AgentTaskBlocked)
                assertEquals(2,server.bodies.size)
                assertEquals("EXPIRED",reopened.control(task.owner,task.id).authorization)
            }
        } }
    }
}
