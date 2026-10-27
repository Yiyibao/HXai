package me.rerere.rikkahub.feature.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AllRoleAdmissionTest {
    @Test fun translationAndChatCannotDispatchTogether(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = GlobalContext.get().get<SettingsStore>()
        val original = settingsStore.settingsFlow.first { !it.init }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val server = ServerSocket(0)
        val workers = mutableListOf<Thread>()
        val acceptor = thread {
            try { while (!server.isClosed) {
                val socket = server.accept()
                synchronized(workers) { workers += thread {
                    socket.use {
                        val input = socket.getInputStream().bufferedReader()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: return@thread
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) { val n = input.read(body, offset, length-offset); check(n > 0); offset += n }
                        count.incrementAndGet(); entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                        val response = """{"id":"synthetic","model":"qwen-mt-qa","choices":[{"index":0,"message":{"role":"assistant","content":"translated"},"finish_reason":"stop"}]}""".toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(response); flush()
                        }
                    }
                } }
            } } catch (_: java.net.SocketException) { }
        }
        val model = Model(modelId="qwen-mt-qa")
        val provider = ProviderSetting.OpenAI(models=listOf(model), apiKey="synthetic-only", baseUrl="http://127.0.0.1:${server.localPort}/v1")
        val assistant = Assistant(chatModelId=model.id)
        val settings = original.copy(providers=listOf(provider), assistants=listOf(assistant), assistantId=assistant.id,
            chatModelId=model.id, translateModeId=model.id, networkSetting=original.networkSetting.copy(proxyUrl="",proxyUsername="",proxyPassword=""))
        settingsStore.update(settings)
        settingsStore.settingsFlow.first { it.translateModeId == model.id }
        val scheduler = AgentScheduler.shared(context)
        var translation: Deferred<String>? = null
        var chat: Deferred<TextGenerationResult>? = null
        try {
            translation = async(Dispatchers.IO) { GlobalContext.get().get<TranslationHandler>().translateText(settings,"synthetic",Locale.ENGLISH).first() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val conversation = Conversation.ofId(Uuid.random(), assistant.id).copy(profileId=settings.activeProfileId)
            val task = scheduler.accept(conversation,"qa-role-chat-${Uuid.random()}","synthetic",settings)
            chat = async(Dispatchers.IO) {
                scheduler.run(task,{ settings },{}, { "COMPLETED_UNVERIFIED" },{ false }) {
                    withContext(requireNotNull(currentCoroutineContext()[AgentExecution]).accounting("main")) {
                        GlobalContext.get().get<ProviderManager>().getProviderByType(provider).generateText(provider,listOf(UIMessage.user("synthetic")),TextGenerationParams(model,reasoningLevel=assistant.reasoningLevel))
                    }
                }
            }
            delay(700)
            val whileBlocked = count.get()
            release.countDown()
            assertEquals("translated", withTimeout(15_000) { translation.await() })
            withTimeout(15_000) { chat.await() }
            assertEquals("One physical request while the first role is blocked",1,whileBlocked)
            assertEquals(2,count.get())
        } finally {
            release.countDown(); translation?.cancelAndJoin(); chat?.cancelAndJoin()
            server.close(); acceptor.join(3000)
            synchronized(workers) { workers.toList() }.forEach { it.join(3000) }
            settingsStore.update(original)
        }
    }
}
