package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class ToolProjectionRuntimeTest {
    @Test fun generationWithoutAcceptedTaskCannotDispatchPhysicalRequest(): Unit = runBlocking {
        val settingsStore=GlobalContext.get().get<SettingsStore>()
        val original=settingsStore.settingsFlow.first { !it.init }
        LocalModelServer { LocalModelServer.completion("unleased response") }.use { server ->
            val model=Model(modelId="qa-unleased")
            val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic",baseUrl=server.baseUrl)
            val assistant=Assistant(chatModelId=model.id,streamOutput=false,localTools=emptyList())
            val settings=original.copy(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,
                chatModelId=model.id,learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },
                networkSetting=original.networkSetting.copy(proxyUrl="",enableAutoRetry=false))
            val failure=runCatching {
                GlobalContext.get().get<GenerationLoop>().generateText(settings,model,listOf(UIMessage.user("synthetic")),
                    assistant=assistant,maxSteps=1).collect {}
            }.exceptionOrNull()
            assertEquals("Unleased generation must not dispatch a billable request",0,server.bodies.size)
            assertNotNull(failure)
        }
    }
    @Test fun providerProjectionIsBoundedWithoutChangingDurableInteractiveJson(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = GlobalContext.get().get<SettingsStore>()
        val original = settingsStore.settingsFlow.first { !it.init }
        val cache=me.rerere.rikkahub.feature.studychat.ChatContextCache(context)
        val originalBudget=cache.budget(original.activeProfileId)
        val interactive = buildJsonObject { put("title","synthetic"); put("data","x".repeat(70_000)) }.toString()
        val surface = UIMessagePart.InteractiveSurfaceRef("qa-surface","synthetic",interactive)
        val raw = UIMessagePart.Tool("../../not-a-path","render_interactive_ui","{}",
            output=listOf(UIMessagePart.Text(interactive),surface),executionState=ToolExecutionState.COMPLETED)
        val history = listOf(UIMessage.user("synthetic"),UIMessage(role=MessageRole.ASSISTANT,parts=listOf(raw)),UIMessage.user("continue"))
        val service=GlobalContext.get().get<me.rerere.rikkahub.service.ChatService>()
        val repo=GlobalContext.get().get<me.rerere.rikkahub.data.repository.ConversationRepository>()
        val id=kotlin.uuid.Uuid.random()
        service.addConversationReference(id)
        LocalModelServer { LocalModelServer.completion("done") }.use { server ->
            val model=Model(modelId="qa-model")
            val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl=server.baseUrl)
            val assistant=Assistant(chatModelId=model.id,streamOutput=false)
            val settings=original.copy(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id,
                networkSetting=original.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword=""))
            settingsStore.update(settings)
            try {
                val conversation=me.rerere.rikkahub.data.model.Conversation.ofId(id,assistant.id).copy(
                    profileId=settings.activeProfileId,newConversation=true,
                    messageNodes=history.map { me.rerere.rikkahub.data.model.MessageNode(messages=listOf(it)) })
                service.saveConversation(id,conversation)
                val reopened=requireNotNull(repo.getConversationById(id))
                val scheduler=AgentScheduler.shared(context)
                val request=history.last()
                val accepted=scheduler.accept(reopened,request.id.toString(),kotlinx.serialization.json.Json.encodeToString(request.parts),settings)
                val loop=GlobalContext.get().get<GenerationLoop>()
                cache.setBudget(settings.activeProfileId,64000)
                scheduler.run(accepted,{settingsStore.settingsFlow.value},{},{ "COMPLETED_UNVERIFIED" },{false}) {
                    loop.generateText(settings,model,reopened.currentMessages,assistant=assistant,maxSteps=1,conversationId=id).collect { chunk ->
                        if (chunk is GenerationChunk.DurableCheckpoint) chunk.committed.complete(Unit)
                    }
                }
                assertEquals(1,server.bodies.size)
                val body=Json.parseToJsonElement(server.bodies.single()).jsonObject
                val toolMessages=body["messages"]!!.jsonArray.filter { it.jsonObject["role"]?.jsonPrimitive?.content=="tool" }
                assertTrue("Provider receives bounded tool text",toolMessages.single().jsonObject["content"].toString().length <= 32*1024)
                val restored=requireNotNull(repo.getConversationById(id)).currentMessages[1].getTools().single()
                assertEquals(raw,restored)
                assertEquals(surface,restored.output[1])
                assertEquals(70_000,Json.parseToJsonElement((restored.output[0] as UIMessagePart.Text).text).jsonObject["data"]!!.jsonPrimitive.content.length)
            } finally {
                cache.setBudget(original.activeProfileId,originalBudget)
                service.removeConversationReference(id)
                repo.getConversationById(id)?.let { repo.deleteConversation(it) }
                settingsStore.update(original)
            }
        }
    }
}
