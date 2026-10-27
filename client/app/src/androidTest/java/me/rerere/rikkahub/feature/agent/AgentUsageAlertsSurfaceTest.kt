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

/** Installed Dart -> generated bridge -> real SQLite; synthetic metadata only. */
@RunWith(AndroidJUnit4::class)
class AgentUsageAlertsSurfaceTest {
    @Test fun nativeCountsRenderInBothThemesWithoutStartingOrChangingTask(): Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val automation=inst.uiAutomation;val settings=GlobalContext.get().get<SettingsStore>()
        val original=settings.settingsFlow.first { !it.init };val owner=settings.createLearnerProfile("QA synthetic alerts")
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE)
        val oldTheme=prefs.getString("theme",null);val oldScale=if(prefs.contains("fontScale"))prefs.getFloat("fontScale",1f)else null
        val folder=File(context.cacheDir,"usage-alerts-${UUID.randomUUID()}").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>?=null
        fun text(n: AccessibilityNodeInfo?): String=if(n==null) "" else buildString {
            n.text?.let { append(it).append('\n') };n.contentDescription?.let { append(it).append('\n') }
            for(i in 0 until n.childCount) append(text(n.getChild(i)))
        }
        fun click(label: String,n: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(n==null)return false
            if(n.text?.toString()?.contains(label)==true || n.contentDescription?.toString()?.contains(label)==true) {
                var c=n;while(c!=null && !c.isClickable)c=c.parent
                return c?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }?:false
            }
            return (0 until n.childCount).any { click(label,n.getChild(it)) }
        }
        fun scroll(forward: Boolean,n: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(n==null)return false
            if(n.isScrollable && n.performAction(if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true
            return (0 until n.childCount).any { scroll(forward,n.getChild(it)) }
        }
        fun waitFor(message: String,condition: () -> Boolean) {
            val end=System.currentTimeMillis()+40_000
            while(System.currentTimeMillis()<end) { if(condition())return;Thread.sleep(100) }
            File(folder,"failure.txt").writeText(text(automation.rootInActiveWindow));fail(message)
        }
        fun capture(name: String) {
            File(folder,"$name.txt").writeText(text(automation.rootInActiveWindow))
            File(folder,"$name.png").outputStream().use { assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        }
        fun seek(message: String,condition: () -> Boolean) {
            var previous="";var unchanged=0;var forward=true
            waitFor(message) {
                if(condition())true else {
                    val current=text(automation.rootInActiveWindow)
                    unchanged=if(current==previous)unchanged+1 else 0
                    if(unchanged>=2){forward=!forward;unchanged=0}
                    scroll(forward);previous=current;Thread.sleep(100);false
                }
            }
        }
        try {
            settings.selectLearnerProfile(owner);settings.settingsFlow.first { it.activeProfileId==owner }
            AgentTaskStore(context).use { store ->
                val t=store.accept(owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Synthetic alert metadata",
                    "missing-private-reference","provider","model","HIGH","revision")
                repeat(22) { i ->
                    val id=UUID.randomUUID().toString()
                    store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
                        put("request_id",id);put("task_id",t.id);put("role",if(i%2==0) "main" else "ocr")
                        put("provenance",if(i==21)"unknown" else "reported");put("created_at",if(i>=20)100L else System.currentTimeMillis())
                        put("prompt_tokens",0);put("total_tokens",0)
                    })
                    store.writableDatabase.insertOrThrow("calls",null,ContentValues().apply {
                        put("task_id",t.id);put("call_id",id);put("tool","provider:main");put("input_hash","synthetic")
                        put("state",if(i==20)"UNKNOWN" else "VERIFIED")
                    })
                }
                val before=store.snapshot(owner,t.id)
                for((theme,scale) in listOf("light" to 1f,"dark" to 2f)) {
                    prefs.edit().putString("theme",theme).putFloat("fontScale",scale).commit()
                    scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
                    waitFor("drawer missing") { click("历史对话") };waitFor("usage route missing") { click("请求用量") }
                    seek("native uncertain count missing") { text(automation.rootInActiveWindow).contains("1 次请求结果未确认") }
                    capture("$theme-$scale-uncertain")
                    seek("native dense count missing") { text(automation.rootInActiveWindow).contains("共 20 次") }
                    capture("$theme-$scale-dense")
                    seek("native unreported count missing") { text(automation.rootInActiveWindow).contains("1 次已完成请求未报告用量") }
                    capture("$theme-$scale-unreported")
                    assertEquals(before,store.snapshot(owner,t.id));assertEquals(22,store.physicalRequestCount(owner,t.id))
                    scenario.close();scenario=null
                }
            }
            println("USAGE_ALERTS_EVIDENCE=${folder.name}")
        } finally {
            scenario?.close();val edit=prefs.edit();if(oldTheme==null)edit.remove("theme") else edit.putString("theme",oldTheme)
            if(oldScale==null)edit.remove("fontScale") else edit.putFloat("fontScale",oldScale)
            edit.commit();restoreMemoryProfileFixture(original,settings)
        }
    }
}
