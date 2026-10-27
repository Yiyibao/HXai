package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class CompressionConcurrencyTest {
    @Test fun lateCompressionCannotOverwriteNewlyCommittedHistory(): Unit = runBlocking {
        val koin=GlobalContext.get()
        val settings=koin.get<SettingsStore>()
        val original=withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        val repo=koin.get<ConversationRepository>()
        val server=ServerSocket(0).apply { soTimeout=15000 }
        val arrived=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val failure=CompletableDeferred<Throwable>()
        val worker=thread(name="qa-compression-concurrency") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout=15000
                    val input=socket.getInputStream().buffered()
                    fun line(): String {
                        val value=StringBuilder()
                        while(true) {
                            val byte=input.read(); check(byte>=0)
                            if(byte==10) return value.toString().trimEnd('\r')
                            check(value.length<8192); value.append(byte.toChar())
                        }
                    }
                    check(line().startsWith("POST "))
                    var length=0
                    while(true) {
                        val header=line(); if(header.isEmpty()) break
                        if(header.startsWith("Content-Length:",true)) length=header.substringAfter(':').trim().toInt()
                    }
                    check(length in 1..2_000_000)
                    val bytes=ByteArray(length); var count=0
                    while(count<length) { val read=input.read(bytes,count,length-count); check(read>0); count+=read }
                    arrived.complete(Unit)
                    runBlocking { release.await() }
                    val response="""{"id":"qa-summary","model":"qa-compression","choices":[{"index":0,"message":{"role":"assistant","content":"synthetic compressed summary"},"finish_reason":"stop"}],"usage":{"prompt_tokens":17,"completion_tokens":8,"total_tokens":25}}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
                    }
                }
            } catch(error: Throwable) { failure.complete(error); arrived.completeExceptionally(error) }
        }
        val model=Model(modelId="qa-compression")
        val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:${server.localPort}/v1")
        val assistant=Assistant(name="QA compression concurrency",chatModelId=model.id,streamOutput=false)
        val id=Uuid.random()
        var service: ChatService?=null
        try {
            settings.update(original.copy(providers=listOf(provider),assistants=original.assistants+assistant,
                assistantId=assistant.id,chatModelId=model.id,compressModelId=model.id,enableSuggestion=false,
                learnerProfiles=original.learnerProfiles.map { it.copy(memoryEnabled=false) },
                networkSetting=original.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword="")))
            settings.settingsFlow.first { it.compressModelId==model.id }
            service=koin.get(); service.addConversationReference(id)
            val before=Conversation.ofId(id,assistant.id).copy(profileId=original.activeProfileId,title="QA compression",newConversation=true,
                messageNodes=listOf(UIMessage.user("old synthetic history").toMessageNode()))
            service.saveConversation(id,before)
            val compressed=async { service.compressConversation(id,before,"",1000,keepRecentMessages=0) }
            withTimeout(20_000) { arrived.await() }
            service.sendMessage(id,listOf(UIMessagePart.Text("new synthetic history")),answer=false)
            withTimeout(20_000) {
                service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.toText()=="new synthetic history" }
                while(service.getGenerationJobStateFlow(id).first()!=null) delay(10)
            }
            val latest=requireNotNull(repo.getConversationById(id))
            release.complete(Unit)
            assertTrue("Stale compression must be rejected",withTimeout(20_000) { compressed.await() }.isFailure)
            assertEquals(latest.messageNodes,requireNotNull(repo.getConversationById(id)).messageNodes)
            if(failure.isCompleted) throw failure.await()
        } finally {
            release.complete(Unit); server.close(); worker.join(5000)
            repo.getConversationById(id)?.let { service?.deleteConversation(it) }
            service?.removeConversationReference(id)
            settings.update(original)
        }
    }
}
