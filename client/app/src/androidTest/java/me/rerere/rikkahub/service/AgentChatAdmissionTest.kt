package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** Real ChatService admission -> queue -> scheduler -> provider DTO -> Room, localhost only. */
@RunWith(AndroidJUnit4::class)
class AgentChatAdmissionTest {
    @Test fun twoConversationsKeepAdmissionModelAfterSettingsChange() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // This suite may change fixture settings only in the explicitly isolated QA package.
        assertEquals("org.hxsi.agent.phase2qa.debug", context.packageName)
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = withTimeout(20_000) { settings.settingsFlow.first { !it.init } }
        settings.setLearnerMemoryEnabled(original.activeProfileId, false)
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bodies = CopyOnWriteArrayList<String>()
        val serverFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val server = thread(isDaemon = true) {
          try {
            repeat(2) { index ->
                listener.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    fun line(): String = buildString {
                        while (true) { val b = input.read(); if (b < 0 || b == 10) break; if (b != 13) append(b.toChar()) }
                    }
                    line()
                    var size = 0
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) size = header.substringAfter(':').trim().toInt() }
                    val bytes = ByteArray(size)
                    var at = 0
                    while (at < size) { val count = input.read(bytes, at, size - at); check(count > 0); at += count }
                    bodies += bytes.toString(Charsets.UTF_8)
                    if (index == 0) { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
                    val result = """{"id":"synthetic-$index","model":"synthetic-a","choices":[{"index":0,"message":{"role":"assistant","content":"SYNTHETIC_RESPONSE"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}}"""
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${result.toByteArray().size}\r\nConnection: close\r\n\r\n$result".toByteArray()); flush()
                    }
                }
            }
          } catch (error: Throwable) {
              if (!listener.isClosed) { serverFailure.set(error); entered.countDown() }
          }
        }
        val modelA = Model(modelId = "synthetic-a", abilities = listOf(ModelAbility.REASONING))
        val modelB = Model(modelId = "synthetic-b", abilities = listOf(ModelAbility.REASONING))
        val provider = ProviderSetting.OpenAI(name = "synthetic-localhost", models = listOf(modelA, modelB), apiKey = "synthetic-key",
            baseUrl = "http://127.0.0.1:${listener.localPort}/v1")
        val assistant = Assistant(name = "synthetic-agent", chatModelId = modelA.id, reasoningLevel = ReasoningLevel.HIGH,
            streamOutput = false, localTools = emptyList())
        val config = original.copy(providers = listOf(provider), assistants = listOf(assistant), assistantId = assistant.id,
            chatModelId = modelA.id, enableSuggestion = false,
            networkSetting = original.networkSetting.copy(proxyUrl = "", proxyUsername = "", proxyPassword = ""))
        val chat = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val ids = List(2) { Uuid.random() }
        try {
            settings.update(config)
            settings.settingsFlow.first { it.assistantId == assistant.id }
            ids.forEach { id ->
                chat.addConversationReference(id)
                chat.saveConversation(id, Conversation.ofId(id, assistant.id, newConversation = true).copy(profileId = original.activeProfileId, title = "Synthetic agent admission"))
            }
            chat.sendMessageCommitted(ids[0], listOf(UIMessagePart.Text("synthetic first")), Uuid.random())
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            serverFailure.get()?.let { throw AssertionError("Synthetic server failed", it) }
            chat.sendMessageCommitted(ids[1], listOf(UIMessagePart.Text("synthetic second")), Uuid.random())
            settings.update(config.copy(chatModelId = modelB.id, assistants = listOf(assistant.copy(chatModelId = modelB.id, reasoningLevel = ReasoningLevel.LOW))))
            settings.settingsFlow.first { it.assistants.single().reasoningLevel == ReasoningLevel.LOW }
            delay(300)
            assertEquals(1, bodies.size)
            release.countDown()
            withTimeout(30_000) { while (ids.any { repository.getConversationById(it)?.currentMessages?.lastOrNull()?.generationState != "COMPLETED" }) delay(50) }
            assertEquals(2, bodies.size)
            bodies.forEach { body ->
                assertTrue(body.contains("synthetic-a"))
                assertFalse(body.contains("synthetic-b"))
                assertTrue(body.contains("high"))
                assertFalse(body.contains("\"reasoning_effort\":\"low\""))
            }
            ids.forEach { assertEquals(modelA.id, repository.getConversationById(it)!!.currentMessages.last().modelId) }
        } finally {
            release.countDown(); listener.close(); server.join(1000)
            ids.forEach { chat.stopGeneration(it); repository.getConversationById(it)?.let { conv -> repository.deleteConversation(conv) }; chat.removeConversationReference(it) }
            settings.update(original)
            settings.setLearnerMemoryEnabled(original.activeProfileId, original.learnerProfiles.single { it.id == original.activeProfileId }.memoryEnabled)
        }
    }
}
