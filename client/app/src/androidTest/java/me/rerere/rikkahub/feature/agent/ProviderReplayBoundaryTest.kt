package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ProviderReplayBoundaryTest {
    @Test fun retryAfterCannotRepeatJournaledProviderRequest(): Unit =
        assertNoFollowUp("503 Service Unavailable","Retry-After: 0\r\n")
    @Test fun redirectCannotLeaveLockedEndpoint(): Unit =
        assertNoFollowUp("302 Found","Location: /v1/redirected\r\n")

    private fun assertNoFollowUp(firstStatus: String, firstHeader: String): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = GlobalContext.get().get<SettingsStore>()
        val original = withTimeout(20_000) { settingsStore.settingsFlow.first { !it.init } }
        val name = "qa-provider-replay-${UUID.randomUUID()}.db"
        val scheduler = AgentScheduler(context,name)
        val server = ServerSocket(0).apply { soTimeout=20_000 }
        val requests = AtomicInteger()
        val worker = thread(name="qa-provider-replay-http") {
            try {
                repeat(2) { index ->
                    server.soTimeout=if(index==0) 20_000 else 2000
                    server.accept().use { socket ->
                    socket.soTimeout=10_000
                    val input=socket.getInputStream().buffered()
                    fun line(): String {
                        val text=StringBuilder()
                        while(true) {
                            val byte=input.read(); check(byte>=0)
                            if(byte==10) return text.toString().trimEnd('\r')
                            check(text.length<8192); text.append(byte.toChar())
                        }
                    }
                    var length=0
                    while(true) {
                        val header=line(); if(header.isEmpty()) break
                        if(header.startsWith("Content-Length:",ignoreCase=true)) length=header.substringAfter(':').trim().toInt()
                    }
                    require(length in 0..2_000_000)
                    val body=ByteArray(length); var read=0
                    while(read<length) { val count=input.read(body,read,length-read); check(count>0); read+=count }
                    requests.incrementAndGet()
                    val response=if(index==0) """{"error":{"message":"synthetic rejection","type":"server_error","code":"qa"}}"""
                        else """{"id":"unexpected-replay","model":"qa-replay","choices":[{"index":0,"message":{"role":"assistant","content":"replayed"},"finish_reason":"stop"}]}"""
                    val bytes=response.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 ${if(index==0) firstStatus else "200 OK"}\r\n${if(index==0) firstHeader else ""}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                } }
            } catch(_: SocketTimeoutException) { /* No second dispatch is the expected boundary. */ }
        }
        try {
            settingsStore.update(original.copy(networkSetting=original.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword="")))
            settingsStore.settingsFlow.first { it.networkSetting.proxyUrl.isEmpty() }
            val model=Model(modelId="qa-replay")
            val provider=ProviderSetting.OpenAI(models=listOf(model),apiKey="synthetic-only",baseUrl="http://127.0.0.1:${server.localPort}/v1")
            val assistant=Assistant(chatModelId=model.id)
            val settings=original.copy(providers=listOf(provider),assistants=listOf(assistant),assistantId=assistant.id,chatModelId=model.id)
            val conversation=Conversation.ofId(Uuid.random(),assistant.id).copy(profileId=original.activeProfileId)
            val task=scheduler.accept(conversation,"provider-replay-boundary","synthetic request",settings)
            val result=runCatching {
                scheduler.run(task,{ settings },{}, { "COMPLETED_UNVERIFIED" }, { false }) {
                    val execution=requireNotNull(currentCoroutineContext()[AgentExecution])
                    kotlinx.coroutines.withContext(execution.accounting("main")) {
                        GlobalContext.get().get<ProviderManager>().getProviderByType(provider).generateText(
                            providerSetting=provider,messages=listOf(UIMessage.user("synthetic request")),
                            params=TextGenerationParams(model=model,reasoningLevel=assistant.reasoningLevel))
                    }
                }
            }
            worker.join(5000)
            assertEquals("The transport must not replay a paid or outcome-unknown request",1,requests.get())
            assertTrue(result.isFailure)
            AgentTaskStore(context,name).use { store -> assertEquals("UNCERTAIN_SIDE_EFFECT",store.get(task.owner,task.id).state) }
        } finally {
            server.close(); worker.join(5000)
            scheduler.close()
            listOf("","-journal","-wal","-shm").forEach { context.noBackupFilesDir.resolve(name+it).delete() }
            settingsStore.update(original)
        }
    }
}
