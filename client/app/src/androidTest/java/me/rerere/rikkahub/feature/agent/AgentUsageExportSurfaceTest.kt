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
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.service.restoreMemoryProfileFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentUsageExportSurfaceTest {
    @Test fun drawerExportPublishesOnlyReadbackReceiptAndExistingPreviewOpensActualFile(): Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext;check(context.packageName=="org.hxsi.agent.nativeqa")
        val automation=inst.uiAutomation;val k=GlobalContext.get();val settings=k.get<SettingsStore>();val files=k.get<MaterialFiles>()
        val original=settings.settingsFlow.first { !it.init };val owner=settings.createLearnerProfile("QA synthetic usage export")
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE);val theme=prefs.getString("theme",null)
        val scale=if(prefs.contains("fontScale")) prefs.getFloat("fontScale",1f) else null
        val folder=File(context.cacheDir,"usage-export-surface-${UUID.randomUUID()}").apply { mkdirs() };var scenario: ActivityScenario<StudyFlutterActivity>?=null
        fun text(n: AccessibilityNodeInfo?): String=if(n==null) "" else buildString {
            n.text?.let { append(it).append('\n') };n.contentDescription?.let { append(it).append('\n') }
            for(i in 0 until n.childCount) append(text(n.getChild(i)))
        }
        fun click(label: String,n: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(n==null)return false
            if(n.text?.toString()?.contains(label)==true || n.contentDescription?.toString()?.contains(label)==true) {
                var c=n;while(c!=null && !c.isClickable)c=c.parent
                return c?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }?:false
            };return (0 until n.childCount).any { click(label,n.getChild(it)) }
        }
        fun scroll(n: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(n==null)return false
            if(n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            return (0 until n.childCount).any { scroll(n.getChild(it)) }
        }
        fun visible(label: String,n: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(n==null)return false
            if(n.text?.toString()?.contains(label)==true || n.contentDescription?.toString()?.contains(label)==true) {
                var c=n;while(c!=null && !c.isClickable)c=c.parent
                return c?.let { it.isVisibleToUser && it.isEnabled }?:false
            }
            return (0 until n.childCount).any { visible(label,n.getChild(it)) }
        }
        fun waitFor(message: String,condition: () -> Boolean) {
            val deadline=System.currentTimeMillis()+40_000
            while(System.currentTimeMillis()<deadline){if(condition())return;Thread.sleep(100)}
            File(folder,"failure.txt").writeText(text(automation.rootInActiveWindow));fail(message)
        }
        fun capture(name: String){
            inst.waitForIdleSync();automation.waitForIdle(1000,5000)
            File(folder,"$name.txt").writeText(text(automation.rootInActiveWindow));
            File(folder,"$name.png").outputStream().use { assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        try {
            settings.selectLearnerProfile(owner);settings.settingsFlow.first { it.activeProfileId==owner }
            AgentTaskStore(context).use { store ->
                val task=store.accept(owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"synthetic export", "missing-private-ref","p","task-model","HIGH","config")
                repeat(75){i ->store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
                    put("request_id",UUID.randomUUID().toString());put("task_id",task.id);put("role",if(i%2==0) "ocr" else "main")
                    put("provenance",if(i%3==0) "unknown" else "reported");put("provider_id","provider-$i");put("model_id","model-$i")
                    put("prompt_tokens",i.toLong());put("total_tokens",0L);put("created_at",100L)
                })}
                val before=store.snapshot(owner,task.id)
                for((mode,font) in listOf("light" to 1f,"dark" to 2f)){
                    prefs.edit().putString("theme",mode).putFloat("fontScale",font).commit()
                    scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
                    waitFor("drawer missing"){click("历史对话")};waitFor("usage entry missing"){click("请求用量")}
                    waitFor("real ledger not returned"){text(automation.rootInActiveWindow).contains("范围内 75 次请求")}
                    val existing=files.list(owner).map {it.id}.toSet()
                    waitFor("export not clickable"){click("导出 JSON 快照")}
                    waitFor("real export receipt hash not published"){
                        val stored=files.list(owner).singleOrNull { it.id !in existing }
                        stored!=null && stored.state=="SUCCEEDED" && stored.sha256.length==64 &&
                            text(automation.rootInActiveWindow).contains("文件 SHA256：${stored.sha256}")
                    }
                    capture("$mode-$font-receipt")
                    val artifact=files.list(owner).single { it.id !in existing };assertEquals("SUCCEEDED",artifact.state)
                    val actual=files.verifiedFile(owner,artifact.id).readBytes();val data=AgentUsageSnapshot.decode(actual).getValue("data").jsonObject
                    assertEquals(75,data.getValue("records").jsonArray.size);assertFalse(actual.toString(Charsets.UTF_8).contains("missing-private-ref"))
                    File(folder,"$mode-$font-output.json").writeBytes(actual)
                    waitFor("preview control not visible after actual scrolling"){
                        if(visible("查看导出快照"))true else {scroll();false}
                    }
                    capture("$mode-$font-receipt-details")
                    waitFor("preview control not reachable"){if(click("查看导出快照"))true else {scroll();false}}
                    waitFor("existing actual artifact preview not opened"){text(automation.rootInActiveWindow).contains("成果预览") && text(automation.rootInActiveWindow).contains("sourceSha256")}
                    capture("$mode-$font-preview")
                    assertEquals(before,store.snapshot(owner,task.id));assertEquals("QUEUED",store.get(owner,task.id).state)
                    scenario.close();scenario=null
                }
            }
            println("USAGE_EXPORT_EVIDENCE=${folder.name}")
        } finally {
            scenario?.close();val edit=prefs.edit();if(theme==null)edit.remove("theme") else edit.putString("theme",theme)
            if(scale==null)edit.remove("fontScale") else edit.putFloat("fontScale",scale);edit.commit();restoreMemoryProfileFixture(original,settings)
        }
    }
}
