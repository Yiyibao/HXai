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

/** Actual installed Flutter drawer -> owner-scoped native catalogue -> durable missing-conversation detail. */
@RunWith(AndroidJUnit4::class)
class AgentTaskCenterSurfaceTest {
    @Test fun drawerOpensDurableTasksAndGlobalUsageWithoutExecuting():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val automation=instrumentation.uiAutomation
        val settings=GlobalContext.get().get<SettingsStore>()
        val original=settings.settingsFlow.first { !it.init }
        val owner=settings.createLearnerProfile("QA synthetic task center")
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE)
        val oldTheme=prefs.getString("theme",null)
        val oldScale=if(prefs.contains("fontScale")) prefs.getFloat("fontScale",1f) else null
        val outputDir=File(context.cacheDir,"task-center-${UUID.randomUUID()}").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>?=null
        fun text(node: AccessibilityNodeInfo?): String {
            if(node==null) return ""
            return buildString {
                node.text?.let { append(it).append('\n') };node.contentDescription?.let { append(it).append('\n') }
                for(index in 0 until node.childCount) append(text(node.getChild(index)))
            }
        }
        fun click(label: String,node: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(node==null) return false
            if(node.text?.toString()?.contains(label)==true || node.contentDescription?.toString()?.contains(label)==true) {
                var candidate: AccessibilityNodeInfo=node
                while(!candidate.isClickable) candidate=candidate.parent ?: return false
                return candidate.isEnabled && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until node.childCount).any { click(label,node.getChild(it)) }
        }
        fun waitFor(label: String,condition:()->Boolean) {
            val deadline=System.currentTimeMillis()+40_000
            while(System.currentTimeMillis()<deadline) { if(condition()) return;Thread.sleep(100) }
            File(outputDir,"failure.txt").writeText(text(automation.rootInActiveWindow));fail(label)
        }
        fun scrollForward(node: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(node==null) return false
            if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            return (0 until node.childCount).any { scrollForward(node.getChild(it)) }
        }
        fun capture(name: String) {
            File(outputDir,"$name.txt").writeText(text(automation.rootInActiveWindow))
            File(outputDir,"$name.png").outputStream().use { assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        }
        try {
            settings.selectLearnerProfile(owner);settings.settingsFlow.first { it.activeProfileId==owner }
            AgentTaskStore(context).use { store ->
                val goal="QA task center durable-${UUID.randomUUID().toString().take(8)}"
                val task=store.accept(owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),goal,
                    "missing-synthetic-secret-ref","center-provider","center-model","HIGH","center-config")
                val before=store.snapshot(task.owner,task.id)
                for((theme,scale) in listOf("light" to 1f,"dark" to 2f)) {
                    prefs.edit().putString("theme",theme).putFloat("fontScale",scale).commit()
                    scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
                    waitFor("actual drawer cannot open") { click("历史对话") }
                    waitFor("native drawer task entry absent") { text(automation.rootInActiveWindow).contains("任务中心") }
                    waitFor("native drawer task entry does not navigate") { click("任务中心") }
                    waitFor("persisted task did not reach installed Dart") { text(automation.rootInActiveWindow).contains(goal) }
                    assertTrue(text(automation.rootInActiveWindow).contains("center-model"))
                    capture("$theme-$scale-catalog")
                    waitFor("catalog cannot navigate to task detail") { click(goal) }
                    waitFor("detail controls not loaded") { text(automation.rootInActiveWindow).contains("打开关联会话") }
                    assertFalse(click("打开关联会话"))
                    // At 200% the safety controls push lower metadata out of view.
                    // Read it through actual accessibility scrolling, not a hidden-node assumption.
                    waitFor("missing conversation detail not wired") {
                        if(text(automation.rootInActiveWindow).contains("关联会话已不可用")) true
                        else { scrollForward(); false }
                    }
                    waitFor("locked model metadata not visible") {
                        if(text(automation.rootInActiveWindow).contains("center-model")) true
                        else { scrollForward(); false }
                    }
                    capture("$theme-$scale-detail")
                    assertEquals(before,store.snapshot(task.owner,task.id))
                    scenario.close();scenario=null
                    scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
                    waitFor("usage drawer cannot open") { click("历史对话") }
                    waitFor("native global usage cannot open") { click("请求用量") }
                    waitFor("global usage did not query native ledger") { text(automation.rootInActiveWindow).contains("当前档案（含辅助任务）") &&
                        text(automation.rootInActiveWindow).contains("费用未知") }
                    // Those labels are static and appear while the query is still loading.
                    // Only the returned ledger DTO can produce this request count.
                    waitFor("global usage did not return a native ledger page") {
                        val actual=text(automation.rootInActiveWindow)
                        if(actual.contains("范围内 0 次请求")) true
                        else { scrollForward(); false }
                    }
                    capture("$theme-$scale-global-usage")
                    assertEquals(before,store.snapshot(task.owner,task.id));assertEquals("QUEUED",store.get(task.owner,task.id).state)
                    scenario.close();scenario=null
                }
            }
            println("TASK_CENTER_EVIDENCE=${outputDir.name}")
        } finally {
            scenario?.close()
            val editor=prefs.edit()
            if(oldTheme==null) editor.remove("theme") else editor.putString("theme",oldTheme)
            if(oldScale==null) editor.remove("fontScale") else editor.putFloat("fontScale",oldScale)
            editor.commit()
            restoreMemoryProfileFixture(original,settings)
        }
    }
}
