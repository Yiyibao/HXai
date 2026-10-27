package me.rerere.rikkahub.feature.agent

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.restoreMemoryProfileFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class TaskResumeSurfaceTest {
    @Test fun installedTaskDetailResumesOriginalCheckpointAndLockedModel() = runSurface(false)
    @Test fun installedTaskDetailDiscoversRecordedResumeBeforeExplicitCoordination() = runSurface(true)
    @Test fun installedRegeneratedCheckpointCanBeDiscoveredAndCoordinated() = runSurface(true, true)
    private fun runSurface(recorded: Boolean, regenerated: Boolean = false): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val automation = instrumentation.uiAutomation
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val service = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val scheduler = AgentScheduler.shared(context)
        val owner = settings.createLearnerProfile("QA checkpoint resume")
        val prefs = context.getSharedPreferences("study_appearance", Context.MODE_PRIVATE)
        val previous = prefs.all
        val output = File(context.cacheDir, "task-resume-${UUID.randomUUID()}").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        var conversationId: Uuid? = null
        fun text(node: AccessibilityNodeInfo?): String = if (node == null) "" else buildString {
            node.text?.let { append(it).append('\n') }; node.contentDescription?.let { append(it).append('\n') }
            for (i in 0 until node.childCount) append(text(node.getChild(i)))
        }
        fun click(label: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (node == null) return false
            if (node.text?.toString() == label || node.contentDescription?.toString()?.lines()?.contains(label) == true) {
                var target = node
                while (!target!!.isClickable) target = target.parent ?: return false
                return target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until node.childCount).any { click(label, node.getChild(it)) }
        }
        fun scroll(back: Boolean, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            return (0 until node.childCount).any { scroll(back, node.getChild(it)) }
        }
        fun waitFor(label: String, check: () -> Boolean) {
            val end = System.currentTimeMillis() + 40000
            while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(100) }
            File(output, "failure.txt").writeText(text(automation.rootInActiveWindow)); fail(label)
        }
        fun capture(label: String) {
            File(output, "$label.txt").writeText(text(automation.rootInActiveWindow))
            File(output, "$label.png").outputStream().use {
                assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        LocalModelServer { LocalModelServer.completion("synthetic resumed surface answer") }.use { server ->
            try {
                settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
                val locked = Model(modelId = "qa-surface-locked")
                val changed = Model(modelId = "qa-surface-picker")
                val provider = ProviderSetting.OpenAI(baseUrl = server.baseUrl, apiKey = "synthetic", models = listOf(locked, changed))
                val assistant = Assistant(chatModelId = locked.id, reasoningLevel = ReasoningLevel.OFF,
                    streamOutput = false, localTools = emptyList())
                val accepted = settings.settingsFlow.value.copy(providers = listOf(provider), assistants = listOf(assistant),
                    assistantId = assistant.id, chatModelId = locked.id, enableSuggestion = false,
                    learnerProfiles = settings.settingsFlow.value.learnerProfiles.map { it.copy(memoryEnabled = false) },
                    networkSetting = original.networkSetting.copy(proxyUrl = "", enableAutoRetry = false))
                for ((theme, scale) in listOf("light" to 1f, "dark" to 2f)) {
                    settings.update(accepted); settings.settingsFlow.first { it.chatModelId == locked.id }
                    prefs.edit().putString("theme", theme).putFloat("fontScale", scale).commit()
                    val id = Uuid.random(); conversationId = id
                    val source = UIMessage.user("QA恢复-${UUID.randomUUID().toString().take(8)}")
                    val conversation = Conversation.ofId(id, assistant.id).copy(profileId = owner, newConversation = true,
                        title = "QA restore checkpoint", messageNodes = listOf(MessageNode(messages = listOf(source)),
                            MessageNode(messages = listOf(UIMessage.assistant("saved synthetic checkpoint").copy(generationState = "INTERRUPTED")))))
                    service.addConversationReference(id); service.saveConversation(id, conversation)
                    val task = if (regenerated) {
                        service.regenerateAtMessage(id, conversation.currentMessages.last())
                        withTimeout(20_000) { while (service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                        requireNotNull(scheduler.current(service.getConversationFlow(id).value))
                    } else scheduler.accept(conversation, source.id.toString(), Json.encodeToString(source.parts), accepted).also {
                        AgentTaskStore(context).use { store -> val lease = store.claim(owner, it.id); store.finish(lease, "INTERRUPTED") }
                    }
                    val before = scheduler.get(owner, task.id)
                    scheduler.command(owner, task.id, "pause", before.revision, before.fence, "pause", "")
                    if (recorded) {
                        val paused = scheduler.get(owner, task.id)
                        scheduler.command(owner, task.id, "durable-surface-resume", paused.revision, paused.fence, "resume", "")
                    }
                    settings.update(accepted.copy(chatModelId = changed.id, assistants = listOf(assistant.copy(chatModelId = changed.id))))
                    settings.settingsFlow.first { it.chatModelId == changed.id }
                    val count = server.bodies.size
                    scenario = ActivityScenario.launch(Intent(context, StudyFlutterActivity::class.java).putExtra("route", "/chat"))
                    waitFor("drawer missing") { click("历史对话") }
                    waitFor("task center missing") { click("任务中心") }
                    val goal = (source.parts.first() as me.rerere.ai.ui.UIMessagePart.Text).text
                    waitFor("task not listed") { click(Json.encodeToString(source.parts)) || click(goal) }
                    waitFor("resume control missing") {
                        if (text(automation.rootInActiveWindow).contains(if (recorded) "继续协调原恢复" else "恢复此任务")) true else { scroll(false); false }
                    }
                    assertEquals("Opening the detail must not dispatch", count, server.bodies.size)
                    if (recorded) assertTrue(text(automation.rootInActiveWindow).contains("原恢复命令已记录"))
                    capture("$theme-before")
                    waitFor("resume callback missing") { if (click(if (recorded) "继续协调原恢复" else "恢复此任务")) true else { scroll(false); false } }
                    withTimeout(20_000) { while (server.bodies.size == count || service.getGenerationJobStateFlow(id).first() != null) delay(20) }
                    assertEquals(count + 1, server.bodies.size)
                    assertEquals("qa-surface-locked", Json.parseToJsonElement(server.bodies.last()).jsonObject.getValue("model").jsonPrimitive.content)
                    assertEquals(task.id, scheduler.current(requireNotNull(repo.getConversationById(id)))?.id)
                    assertEquals("COMPLETED_UNVERIFIED", scheduler.get(owner, task.id).state)
                    waitFor("detail refresh unavailable") { if (click("刷新详情")) true else { scroll(true); false } }
                    waitFor("new task DTO missing") {
                        if (text(automation.rootInActiveWindow).contains("执行已结束")) true else { scroll(false); false }
                    }
                    capture("$theme-after")
                    scenario.close(); scenario = null
                    service.removeConversationReference(id); repo.getConversationById(id)?.let { repo.deleteConversation(it) }
                    conversationId = null
                }
                println("TASK_RESUME_SURFACES=${output.name}")
            } finally {
                scenario?.close()
                conversationId?.let { id -> service.stopGeneration(id); service.removeConversationReference(id); repo.getConversationById(id)?.let { repo.deleteConversation(it) } }
                val editor = prefs.edit()
                (previous["theme"] as? String)?.let { editor.putString("theme", it) } ?: editor.remove("theme")
                (previous["fontScale"] as? Float)?.let { editor.putFloat("fontScale", it) } ?: editor.remove("fontScale")
                editor.commit(); restoreMemoryProfileFixture(original, settings)
            }
        }
    }
}
