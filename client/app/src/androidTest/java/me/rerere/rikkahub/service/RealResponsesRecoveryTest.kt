package me.rerere.rikkahub.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Opt-in remote-provider acceptance. Emits only synthetic fixture IDs, hashes and states. */
@RunWith(AndroidJUnit4::class)
class RealResponsesRecoveryTest {
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Test fun acceptance() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Remote acceptance is explicitly opt-in", args.getString("allowRemote") == "true")
        val phase = requireNotNull(args.getString("phase"))
        val id = Uuid.parse(requireNotNull(args.getString("fixtureId")))
        val koin = GlobalContext.get()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = koin.get<SettingsStore>()
        val settings = withTimeout(20_000) { store.settingsFlow.first { !it.init } }
        val repo = koin.get<ConversationRepository>()
        val service = koin.get<ChatService>()
        val oldAssistant = settings.getCurrentAssistant()
        val model = requireNotNull(settings.findModelById(oldAssistant.chatModelId ?: settings.chatModelId))
        val provider = settings.providers.filterIsInstance<ProviderSetting.OpenAI>().single { p -> p.models.any { it.id == model.id } }
        require(provider.useResponseApi && provider.apiKey.isNotBlank())
        fun report(vararg pairs: Pair<String, String>) {
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("qa_phase", phase)
                putString("qa_pid", android.os.Process.myPid().toString())
                pairs.forEach { (key, value) -> putString("qa_$key", value) }
            })
        }
        suspend fun terminal(status: String): Conversation = withTimeout(150_000) {
            service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState == status }
            while (service.getGenerationJobStateFlow(id).first() != null) delay(30)
            repo.getConversationById(id)!!
        }
        suspend fun partial(): Conversation = withTimeout(150_000) {
            service.getConversationFlow(id).first {
                it.currentMessages.lastOrNull()?.let { m -> m.role == MessageRole.ASSISTANT && m.toText().length > 180 && m.generationState == "RUNNING" } == true
            }
        }
        if (phase == "cleanup") {
            repo.getConversationById(id)?.let {
                require(it.title.startsWith("[QA] real "))
                repo.deleteConversation(it)
            }
            store.update(settings.copy(assistants = settings.assistants.filterNot { it.id == id && it.name == "QA Responses" }))
        } else if (phase == "select") {
            val previous = File(context.filesDir, "qa-real-previous-assistant")
            if (!previous.exists()) previous.writeText(settings.assistantId.toString())
            require(settings.assistants.any { it.id == id && it.name == "QA Responses" })
            store.update(settings.copy(assistantId = id))
            store.settingsFlow.first { it.assistantId == id }
        } else if (phase == "restore") {
            val previous = File(context.filesDir, "qa-real-previous-assistant")
            store.update(settings.copy(assistantId = Uuid.parse(previous.readText())))
            previous.delete()
        } else if (phase == "network" || phase == "kill") {
            service.addConversationReference(id)
            service.saveConversation(id, Conversation.ofId(id, oldAssistant.id).copy(profileId = settings.activeProfileId, title = "[QA] real $phase"))
            service.sendMessage(id, listOf(UIMessagePart.Text("[QA 合成测试] 请直接输出从1到180的编号，每行写一句30字左右的数学学习说明，不要省略。这是流式恢复测试。")))
            partial()
            service.sendMessage(id, listOf(UIMessagePart.Text("[QA 合成测试] 排队问题：只回复 12。")))
            withTimeout(10_000) { service.getConversationFlow(id).first { it.queue.messages.size == 1 } }
            val before = repo.getConversationById(id)!!
            report("id" to id.toString(), "message_id" to before.currentMessages.last().id.toString(), "queued_id" to before.queue.messages.single().id.toString())
            if (phase == "kill") {
                report("kill_ready" to "true")
                awaitCancellation() // Host kills the application while a real response is streaming.
            }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val route = args.getString("route") ?: "airplane"
            val wifi = shell("settings get global wifi_on") != "0"
            try {
                if (route == "airplane") shell("cmd connectivity airplane-mode enable") else {
                    assertTrue(manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
                    shell("svc wifi disable")
                }
                val interrupted = terminal("INTERRUPTED")
                assertEquals(before.currentMessages.last().id, interrupted.currentMessages.last().id)
                assertTrue(interrupted.currentMessages.last().toText().startsWith(before.currentMessages.last().toText()))
                assertTrue(interrupted.queue.paused)
                if (route != "airplane") withTimeout(30_000) {
                    while (manager.activeNetwork?.let(manager::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) delay(100)
                }
                shell("cmd connectivity airplane-mode disable")
                withTimeout(30_000) { while (manager.activeNetwork == null) delay(100) }
                delay(3_000)
                assertNull(service.getGenerationJobStateFlow(id).first())
                assertEquals(interrupted.currentMessages, repo.getConversationById(id)!!.currentMessages)
                service.resumeMessageQueue(id)
                val completed = terminal("COMPLETED")
                assertEquals(2, completed.currentMessages.count { it.role == MessageRole.USER })
                assertEquals(before.queue.messages.single().id, completed.currentMessages.filter { it.role == MessageRole.USER }.last().id)
                assertTrue(completed.queue.messages.isEmpty())
                report("result" to "PASS", "route" to route, "partial_chars" to interrupted.currentMessages.last().toText().length.toString())
            } finally {
                shell("cmd connectivity airplane-mode disable")
                shell(if (wifi) "svc wifi enable" else "svc wifi disable")
                service.stopGeneration(id)
            }
        } else if (phase == "verify") {
            val before = repo.getConversationById(id)!!
            service.addConversationReference(id)
            service.initializeConversation(id)
            withTimeout(20_000) { service.getConversationFlow(id).first { it.currentMessages.lastOrNull()?.generationState == "INTERRUPTED" } }
            val recovered = repo.getConversationById(id)!!
            assertEquals(before.currentMessages.map { it.id }, recovered.currentMessages.map { it.id })
            assertEquals(before.currentMessages.last().toText(), recovered.currentMessages.last().toText())
            assertTrue(recovered.queue.paused)
            assertEquals(before.queue.messages.single().id, recovered.queue.messages.single().id)
            delay(3_000)
            assertNull(service.getGenerationJobStateFlow(id).first())
            report("result" to "PASS", "partial_chars" to recovered.currentMessages.last().toText().length.toString(), "message_id" to recovered.currentMessages.last().id.toString(),
                "sha256" to MessageDigest.getInstance("SHA-256").digest(recovered.currentMessages.last().toText().toByteArray()).joinToString("") { "%02x".format(it) })
        } else if (phase == "approval" || phase == "tools") {
            val assistant = Assistant(id = id, name = "QA Responses", chatModelId = model.id,
                reasoningLevel = oldAssistant.reasoningLevel, localTools = listOf(LocalToolOption.JavascriptEngine, LocalToolOption.AskUser))
            store.update(settings.copy(assistants = settings.assistants.filter { it.id != id } + assistant))
            store.settingsFlow.first { it.assistants.any { a -> a.id == id } }
            service.addConversationReference(id)
            val prompt = if (phase == "approval") "[QA 合成测试] 必须先调用 ask_user，问题为：是否继续本次测试？选项为继续。收到答案后只回复测试完成。" else
                "[QA 合成测试] 在同一轮并行调用两次 eval_javascript，代码分别为 1+1 和 2+2。取得工具结果后只简短总结；不要再次调用工具。"
            val conversation = Conversation.ofId(id, id).copy(profileId = settings.activeProfileId, title = "[QA] real $phase",
                messageNodes = listOf(me.rerere.ai.ui.UIMessage.user(prompt).toMessageNode()))
            service.saveConversation(id, conversation)
            if (phase == "approval") {
                service.regenerateAtMessage(id, conversation.currentMessages.last())
                val waiting = terminal("WAITING_TOOL")
                assertTrue(waiting.currentMessages.last().getTools().any { it.isPending })
                report("result" to "PASS", "id" to id.toString())
            } else {
                val session = ConversationSession(id, conversation, this, {})
                session.initialize { conversation }
                var count = 0
                val base = koin.get<LocalTools>().javascriptTool
                val tool = base.copy(execute = {
                    count++
                    val saved = repo.getConversationById(id)!!.currentMessages.last().getTools()
                    assertTrue(saved.any { it.executionState == me.rerere.ai.ui.ToolExecutionState.RUNNING })
                    if (count == 2) {
                        assertTrue(saved.first().isExecuted)
                        report("tool_kill_ready" to "true", "id" to id.toString(), "executions" to count.toString())
                        awaitCancellation()
                    }
                    base.execute(it)
                })
                try {
                    koin.get<GenerationLoop>().generateText(settings = settings, model = model, messages = conversation.currentMessages,
                        assistant = assistant, tools = listOf(tool)).collect { chunk ->
                        when (chunk) {
                            is GenerationChunk.Messages -> session.checkpointGeneration(chunk.messages, repo::checkpointMessages)
                            is GenerationChunk.DurableCheckpoint -> {
                                session.checkpointGeneration(chunk.messages, repo::checkpointMessages)
                                chunk.committed.complete(Unit)
                            }
                        }
                    }
                    error("Expected two real model tool calls and host kill")
                } finally { /* Host process death preserves durable tool states. */ }
            }
        } else if (phase == "inspect") {
            val c = repo.getConversationById(id)!!
            val tools = c.currentMessages.flatMap { it.getTools() }
            report("state" to c.currentMessages.last().generationState.orEmpty(), "users" to c.currentMessages.count { it.role == MessageRole.USER }.toString(),
                "tools" to tools.joinToString(",") { "${it.toolCallId}:${it.executionState}:${it.isExecuted}" },
                "unknown_results" to tools.count { it.output.any { p -> p is UIMessagePart.Text && p.text.contains("\"status\":\"unknown\"") } }.toString())
        } else error("Unknown phase")
    }
}


