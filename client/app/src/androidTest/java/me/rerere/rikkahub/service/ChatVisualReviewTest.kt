package me.rerere.rikkahub.service

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.interactive.interactiveUiTool
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Synthetic visual fixtures through the real Kotlin -> Flutter message bridge. No model requests. */
class ChatVisualReviewTest {
    private fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return true
        return (0 until node.childCount).any { contains(node.getChild(it), text) }
    }
    private fun scrollBack(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        return (0 until node.childCount).any { scrollBack(node.getChild(it)) }
    }
    @Test fun captureReferenceScenes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val koin = GlobalContext.get()
        val settings = runBlocking { koin.get<SettingsStore>().settingsFlow.first { !it.init } }
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val scenes = listOf(
            Triple("chat", "帮我总结这份研究报告。", ""),
            Triple("analysis", "看看哪些月份增长最快。", """{"version":3,"title":"销售趋势","components":[{"id":"source","type":"text","text":"来源：季度销售.xlsx · Sheet1"},{"id":"region","type":"select","label":"地区","options":["*","华东","华南"],"value":"*"},{"id":"total","type":"card","text":"累计销售额\n¥ 146,200"},{"id":"trend","type":"line_chart","points":[[1,8400],[2,15600],[3,22000],[4,27000],[5,31200],[6,42000]],"xLabel":"月份","yLabel":"元","selectable":true},{"id":"go","type":"button","label":"分析选中月份","action":"continue"}],"layout":[["source"],["region"],["total"],["trend"],["go"]]}"""),
            Triple("parameters", "利率变化会怎样影响我的月供？", """{"version":3,"title":"房贷月供试算","components":[{"id":"source","type":"text","text":"等额本息 · 仅作估算"},{"id":"amount","type":"slider","label":"贷款金额（万元）","min":1,"max":1000,"value":100},{"id":"years","type":"slider","label":"期限（年）","min":1,"max":40,"value":30},{"id":"rate","type":"slider","label":"年利率（%）","min":0.1,"max":10,"value":3.5},{"id":"payment","type":"calculation","label":"预计月供（元 / 月）","expression":{"op":"divide","args":[{"op":"multiply","args":[{"op":"multiply","args":[{"parameter":"amount"},10000]},{"op":"divide","args":[{"parameter":"rate"},1200]}]},{"op":"subtract","args":[1,{"op":"power","args":[{"op":"add","args":[1,{"op":"divide","args":[{"parameter":"rate"},1200]}]},{"op":"multiply","args":[{"parameter":"years"},-12]}]}]}]}},{"id":"go","type":"button","label":"按当前参数解释","action":"continue"}],"layout":[["source"],["amount","years"],["rate"],["payment"],["go"]]}""")
        )
        for ((name, prompt, spec) in scenes) {
            val answer = if (spec.isEmpty()) listOf(UIMessagePart.Text("这篇报告围绕研究背景、方法与结果展开，关键结论已整理在下方。\n\n### 结论摘要\n\n- **研究方法：** 结合调查与实验分析，建立模型解释关键变量之间的关系。\n- **主要结果：** 在多个样本组中观察到一致的趋势，支持研究假设。\n\n需要我继续整理成一页阅读笔记吗？")) else {
                val input = buildJsonObject { put("definition", JsonPrimitive(spec)) }
                listOf(UIMessagePart.Tool("visual-$name", "render_interactive_ui", input.toString(), interactiveUiTool().let { tool -> runBlocking { tool.execute(input) } }, executionState = ToolExecutionState.COMPLETED), UIMessagePart.Text(if (name == "analysis") "5 月增长明显。点选月份查看数值，再让我分析当前选择。" else "利率越高，月供越高。调整参数后，可让我解释当前方案。"))
            }
            val conversation = Conversation(assistantId = settings.assistantId, profileId = settings.activeProfileId, title = "Visual QA $name", newConversation = true,
                messageNodes = listOf(UIMessage.user(prompt).toMessageNode(), UIMessage.assistant("").copy(parts = answer, generationState = "COMPLETED").toMessageNode()))
            try {
                runBlocking { service.addConversationReference(conversation.id); service.initializeConversation(conversation.id); service.saveConversation(conversation.id, conversation) }
                ActivityScenario.launch(StudyFlutterActivity::class.java).use {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(3000)
                    repeat(4) { scrollBack(instrumentation.uiAutomation.rootInActiveWindow); Thread.sleep(250) }
                    val expected = if (name == "chat") "结论摘要" else if (name == "analysis") "销售趋势" else "房贷月供试算"
                    assertTrue("Missing rendered scene: $name", contains(instrumentation.uiAutomation.rootInActiveWindow, expected))
                    File(context.cacheDir, "visual-$name.png").outputStream().use { stream ->
                        assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                    }
                }
            } finally {
                runBlocking { repository.getConversationById(conversation.id)?.let { service.deleteConversation(it) }; service.removeConversationReference(conversation.id) }
            }
        }
    }
}
