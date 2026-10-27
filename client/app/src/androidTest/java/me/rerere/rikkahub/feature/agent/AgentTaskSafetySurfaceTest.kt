package me.rerere.rikkahub.feature.agent

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.service.restoreMemoryProfileFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentTaskSafetySurfaceTest {
    @Test fun installedTaskCenterCanPauseRevokeAndCancelAnOrphanWithoutExecuting(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val automation = instrumentation.uiAutomation
        val settings = GlobalContext.get().get<SettingsStore>()
        val original = settings.settingsFlow.first { !it.init }
        val owner = settings.createLearnerProfile("QA task safety")
        val prefs = context.getSharedPreferences("study_appearance", Context.MODE_PRIVATE)
        val previous = prefs.all
        val output = File(context.cacheDir, "task-safety-${UUID.randomUUID()}").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        fun text(node: AccessibilityNodeInfo?): String = if(node == null) "" else buildString {
            node.text?.let { append(it).append('\n') }; node.contentDescription?.let { append(it).append('\n') }
            for(index in 0 until node.childCount) append(text(node.getChild(index)))
        }
        fun click(label: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if(node == null) return false
            if(node.text?.toString() == label || node.contentDescription?.toString()?.lines()?.contains(label) == true) {
                var target = node
                while(!target!!.isClickable) target = target.parent ?: return false
                return target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until node.childCount).any { click(label, node.getChild(it)) }
        }
        fun waitFor(label: String, check: () -> Boolean) {
            val end = System.currentTimeMillis() + 40000
            while(System.currentTimeMillis() < end) { if(check()) return; Thread.sleep(100) }
            File(output, "failure.txt").writeText(text(automation.rootInActiveWindow)); fail(label)
        }
        fun capture(label: String) {
            File(output, "$label.txt").writeText(text(automation.rootInActiveWindow))
            File(output, "$label.png").outputStream().use {
                assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        try {
            settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
            AgentTaskStore(context).use { store ->
                for((theme, scale) in listOf("light" to 1f, "dark" to 2f)) {
                    prefs.edit().putString("theme", theme).putFloat("fontScale", scale).commit()
                    val goal = "QA安全控制-${UUID.randomUUID().toString().take(8)}"
                    val task = store.accept(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), goal,
                        "missing-vault-ref", "safety-provider", "safety-model", "HIGH", "safety-config")
                    val untouched = store.accept(owner, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "other queued goal",
                        "missing-vault-ref", "safety-provider", "other-model", "LOW", "other-config")
                    scenario = ActivityScenario.launch(Intent(context, StudyFlutterActivity::class.java).putExtra("route", "/chat"))
                    waitFor("drawer missing") { click("历史对话") }
                    waitFor("task center navigation missing") { click("任务中心") }
                    waitFor("owned task not listed") { click(goal) }
                    waitFor("safety control not installed") { text(automation.rootInActiveWindow).contains("暂停任务") }
                    capture("$theme-before")
                    waitFor("pause callback missing") { click("暂停任务") }
                    waitFor("pause did not reach durable store") { store.get(owner, task.id).state == "PAUSED_BY_USER" }
                    waitFor("pause ACK not followed by DTO") { !text(automation.rootInActiveWindow).contains("查询暂停回执") }
                    capture("$theme-paused")
                    waitFor("revoke callback missing") { click("主动撤权") }
                    waitFor("revoke did not reach durable store") { store.control(owner, task.id).authorization == "REVOKED" }
                    waitFor("revoke ACK not followed by DTO") { !text(automation.rootInActiveWindow).contains("查询撤权回执") }
                    waitFor("cancel callback missing") { click("取消任务") }
                    waitFor("cancel did not reach durable store") { store.get(owner, task.id).state == "CANCELED" }
                    waitFor("cancel ACK not followed by DTO") { !text(automation.rootInActiveWindow).contains("查询取消回执") }
                    assertEquals(untouched, store.get(owner, untouched.id))
                    assertEquals(task.modelId, store.get(owner, task.id).modelId)
                    assertEquals(task.reasoning, store.get(owner, task.id).reasoning)
                    assertFalse(click("暂停任务")); assertFalse(click("取消任务")); assertFalse(click("主动撤权"))
                    assertTrue(store.readUsage(owner, task.id, null, null, null, 20).entries.isEmpty())
                    capture("$theme-canceled")
                    scenario.close(); scenario = null
                    assertEquals("CANCELED", store.readTaskDetail(owner, task.id, null, 20).task.state)
                }
            }
            println("TASK_SAFETY_SURFACES=${output.name}")
        } finally {
            scenario?.close()
            val editor = prefs.edit()
            (previous["theme"] as? String)?.let { editor.putString("theme", it) } ?: editor.remove("theme")
            (previous["fontScale"] as? Float)?.let { editor.putFloat("fontScale", it) } ?: editor.remove("fontScale")
            editor.commit(); restoreMemoryProfileFixture(original, settings)
        }
    }
}
