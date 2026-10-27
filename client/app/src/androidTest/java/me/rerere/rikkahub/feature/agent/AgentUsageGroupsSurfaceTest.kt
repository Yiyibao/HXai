package me.rerere.rikkahub.feature.agent

import android.content.ContentValues
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

/** Historical synthetic receipts exercise installed Dart -> Pigeon -> SQLite, never a paid request. */
@RunWith(AndroidJUnit4::class)
class AgentUsageGroupsSurfaceTest {
    @Test fun actualDrawerGroupingWaitsForNativeCountsAndKeepsQueueUnchanged(): Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val automation=instrumentation.uiAutomation;val settings=GlobalContext.get().get<SettingsStore>()
        val original=settings.settingsFlow.first { !it.init }
        val owner=settings.createLearnerProfile("QA synthetic usage groups")
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE)
        val oldTheme=prefs.getString("theme",null);val oldScale=if(prefs.contains("fontScale")) prefs.getFloat("fontScale",1f) else null
        val folder=File(context.cacheDir,"usage-groups-${UUID.randomUUID()}").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>?=null
        fun text(node: AccessibilityNodeInfo?): String = if(node==null) "" else buildString {
            node.text?.let { append(it).append('\n') };node.contentDescription?.let { append(it).append('\n') }
            for(index in 0 until node.childCount) append(text(node.getChild(index)))
        }
        fun click(label: String,node: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(node==null) return false
            if(node.text?.toString()?.contains(label)==true || node.contentDescription?.toString()?.contains(label)==true) {
                var candidate=node
                while(candidate!=null && !candidate.isClickable) candidate=candidate.parent
                return candidate?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
            }
            return (0 until node.childCount).any { click(label,node.getChild(it)) }
        }
        fun waitFor(message: String,condition: () -> Boolean) {
            val deadline=System.currentTimeMillis()+40_000
            while(System.currentTimeMillis()<deadline) { if(condition()) return;Thread.sleep(100) }
            File(folder,"failure.txt").writeText(text(automation.rootInActiveWindow));fail(message)
        }
        fun scrollForward(node: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(node==null) return false
            if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            return (0 until node.childCount).any { scrollForward(node.getChild(it)) }
        }
        fun capture(name: String) {
            File(folder,"$name.txt").writeText(text(automation.rootInActiveWindow))
            File(folder,"$name.png").outputStream().use { assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        }
        fun choose(current: String,desired: String) {
            waitFor("group selector not clickable: $current") { click(current) }
            waitFor("group selector popup absent: $desired") { click(desired) }
        }
        try {
            settings.selectLearnerProfile(owner);settings.settingsFlow.first { it.activeProfileId==owner }
            AgentTaskStore(context).use { store ->
                val task=store.accept(owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Synthetic groups metadata",
                    "missing-private-role-ref","task-provider","task-model","HIGH","config")
                for((provider,provenance,role) in listOf(Triple("provider-A","reported","main"),Triple("provider-B","estimated","ocr"),Triple(null,"unknown","ocr"))) {
                    store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
                        put("request_id",UUID.randomUUID().toString());put("task_id",task.id);put("role",role);put("provenance",provenance)
                        put("provider_id",provider);put("model_id",if(provider==null) null else "same-model")
                        put("created_at",100L);put("prompt_tokens",if(provenance=="estimated") 5L else 0L)
                        put("total_tokens",if(provenance=="estimated") 6L else 0L)
                    })
                }
                val before=store.snapshot(owner,task.id)
                for((theme,scale) in listOf("light" to 1f,"dark" to 2f)) {
                    prefs.edit().putString("theme",theme).putFloat("fontScale",scale).commit()
                    scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
                    waitFor("drawer did not open") { click("历史对话") }
                    waitFor("usage entry did not navigate") { click("请求用量") }
                    waitFor("actual request ledger DTO not returned") { text(automation.rootInActiveWindow).contains("范围内 3 次请求") }
                    choose("逐请求","按模型")
                    waitFor("actual grouped model DTO not returned") { text(automation.rootInActiveWindow).contains("共 3 个分组") }
                    assertFalse(text(automation.rootInActiveWindow).contains("task-model"))
                    capture("$theme-$scale-model")
                    choose("按模型","按提供商")
                    waitFor("actual grouped provider DTO not returned") { text(automation.rootInActiveWindow).contains("共 3 个分组") &&
                        text(automation.rootInActiveWindow).contains("提供商：未知提供商") && !text(automation.rootInActiveWindow).contains("模型：") }
                    capture("$theme-$scale-provider")
                    choose("按提供商","按角色")
                    waitFor("actual grouped role DTO not returned") { text(automation.rootInActiveWindow).contains("共 2 个分组") }
                    capture("$theme-$scale-role")
                    waitFor("actual role card not reachable by scrolling") {
                        if(text(automation.rootInActiveWindow).contains("角色：OCR")) true else { scrollForward();false }
                    }
                    capture("$theme-$scale-role-details")
                    assertEquals(before,store.snapshot(owner,task.id));assertEquals("QUEUED",store.get(owner,task.id).state)
                    assertEquals(3L,store.readUsageGroups(owner,null,null,null,"role",null,30).requestCount)
                    scenario.close();scenario=null
                }
            }
            println("USAGE_GROUPS_EVIDENCE=${folder.name}")
        } finally {
            scenario?.close()
            val editor=prefs.edit();if(oldTheme==null) editor.remove("theme") else editor.putString("theme",oldTheme)
            if(oldScale==null) editor.remove("fontScale") else editor.putFloat("fontScale",oldScale)
            editor.commit();restoreMemoryProfileFixture(original,settings)
        }
    }
}
