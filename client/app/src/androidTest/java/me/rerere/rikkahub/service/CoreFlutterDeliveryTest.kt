package me.rerere.rikkahub.service

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.flutter.*
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real Flutter buttons -> native bridge -> DocumentsUI / independent receiver UID. */
class CoreFlutterDeliveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun node(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == text || root.contentDescription?.toString() == text ||
            (text.endsWith(".docx") && (root.text?.toString()?.contains(text) == true || root.contentDescription?.toString()?.contains(text) == true))) return root
        for (i in 0 until root.childCount) node(text, root.getChild(i))?.let { return it }
        return null
    }
    private fun click(text: String): Boolean {
        var target = node(text) ?: return false
        while (!target.isClickable) target = target.parent ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(150) }
        val nodes = StringBuilder()
        fun dump(root: AccessibilityNodeInfo?) {
            if (root == null) return
            nodes.append(root.className).append(" text=").append(root.text).append(" label=").append(root.contentDescription).append('\n')
            for (i in 0 until root.childCount) dump(root.getChild(i))
        }
        dump(automation.rootInActiveWindow)
        File(context.cacheDir, "core-v1-flutter-accessibility.txt").writeText(nodes.toString())
        fail(description)
    }
    private fun receipt(action: String, hash: String): String {
        var text = ""
        waitUntil("receiver $action hash mismatch") {
            click(if (action == "view") "QA 文件查看器" else "QA 分享接收"); click("Just once"); click("仅此一次")
            text = shell("run-as org.mathstudy.qa.delivery cat files/$action.json")
            text.contains(hash) && automation.rootInActiveWindow?.packageName?.toString() == "org.mathstudy.qa.delivery"
        }
        return text
    }
    @Test fun actualFlutterArtifactOpensSavesAndShares() {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val store = koin.get<MaterialFiles>(); val service = koin.get<ChatService>(); val repository = koin.get<ConversationRepository>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val marker = "QA-Flutter-${UUID.randomUUID().toString().take(8)}"
        val item = runBlocking { store.runTask(MaterialFile(UUID.randomUUID().toString(), original.activeProfileId, "$marker.docx", "docx", output = true)) { file -> MaterialWriter.write(file, "docx", "Synthetic Flutter delivery $marker", emptyList(), emptyList()) } }
        val tool = UIMessagePart.Tool("qa-delivery", "create_material_file", "{}", listOf(UIMessagePart.Text("合成验收成果，真实文件"), UIMessagePart.Document(android.net.Uri.fromFile(store.file(item)).toString(), item.name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")), executionState = ToolExecutionState.COMPLETED)
        val conversation = Conversation(assistantId = original.assistantId, title = marker, profileId = original.activeProfileId, newConversation = true, messageNodes = listOf(UIMessage.user("生成合成Word成果").toMessageNode(), UIMessage.assistant("").copy(parts = listOf(tool), generationState = "COMPLETED").toMessageNode()))
        val saved = AtomicReference<String?>()
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        try {
            runBlocking { service.addConversationReference(conversation.id); service.initializeConversation(conversation.id); service.saveConversation(conversation.id, conversation) }
            scenario = ActivityScenario.launch(StudyFlutterActivity::class.java)
            waitUntil("Flutter file card missing") { node(item.name) != null }
            scenario.onActivity { activity ->
                val facade = StudyFlutterActivity::class.java.getDeclaredField("facade").apply { isAccessible = true }.get(activity) as StudyHostFacade
                val previous = facade.eventSink
                facade.eventSink = object : EventChannel.EventSink {
                    override fun success(event: Any?) { previous?.success(event); if (event is Map<*, *> && event["type"] == "artifact_saved") saved.set((event["payload"] as? Map<*, *>)?.get("uri") as? String) }
                    override fun error(code: String, message: String?, details: Any?) { previous?.error(code, message, details) }
                    override fun endOfStream() { previous?.endOfStream() }
                }
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(1200) // Accessibility semantics precede the first rendered Flutter frame.
            File(context.cacheDir, "core-v1-flutter-file.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(click("预览")); val view = receipt("view", item.sha256)
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitUntil("Flutter card after view") { node(item.name) != null }
            assertTrue(click("保存"))
            waitUntil("DocumentsUI not foreground") { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            waitUntil("DocumentsUI save button missing") { click("Save") || click("SAVE") || click("保存") }
            waitUntil("Verified destination receipt missing") { saved.get() != null }
            val destination = android.net.Uri.parse(saved.get())
            val actual = context.contentResolver.openInputStream(destination)!!.use { input -> java.security.MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) } }
            assertEquals(item.sha256, actual)
            waitUntil("Flutter card after save") { node(item.name) != null }
            assertTrue(click("文件操作"))
            waitUntil("File share menu missing") { node("分享文件") != null }
            assertTrue(click("分享文件")); val send = receipt("send", item.sha256)
            File(context.cacheDir, "core-v1-flutter-delivery.json").writeText(org.json.JSONObject().put("source_sha256", item.sha256).put("view", org.json.JSONObject(view)).put("send", org.json.JSONObject(send)).put("saved_target_sha256", actual).put("documents_ui", true).put("flutter_buttons", true).toString())
        } finally {
            saved.get()?.let { runCatching { context.contentResolver.delete(android.net.Uri.parse(it), null, null) } }
            scenario?.close()
            runBlocking { repository.getConversationById(conversation.id)?.let { service.deleteConversation(it) }; service.removeConversationReference(conversation.id) }
            store.file(item).delete(); File(context.filesDir, "materials/${item.profileId}/${item.id}.meta.json").delete()
        }
    }
}
