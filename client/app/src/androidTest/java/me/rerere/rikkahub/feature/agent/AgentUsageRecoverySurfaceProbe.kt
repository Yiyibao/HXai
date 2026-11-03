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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

/** Used by an external host which stops the whole synthetic app after actual UI export. */
@RunWith(AndroidJUnit4::class)
class AgentUsageRecoverySurfaceProbe {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also {require(UUID.fromString(it).toString()==it)}
    private val folder get()=context.cacheDir.resolve("usage-recovery-surface-$fixture").apply {mkdirs()}
    private fun durable(name: String,value: String) {folder.resolve(name).outputStream().use {it.write(value.toByteArray());it.fd.sync()}}
    private fun text(n: AccessibilityNodeInfo?=inst.uiAutomation.rootInActiveWindow): String=if(n==null) "" else buildString {
        n.text?.let {append(it).append('\n')};n.contentDescription?.let {append(it).append('\n')}
        for(i in 0 until n.childCount)append(text(n.getChild(i)))
    }
    private fun click(label: String,n: AccessibilityNodeInfo?=inst.uiAutomation.rootInActiveWindow): Boolean {
        if(n==null)return false
        if(n.text?.toString()?.contains(label)==true || n.contentDescription?.toString()?.contains(label)==true){
            var c=n;while(c!=null && !c.isClickable)c=c.parent
            return c?.let {it.isEnabled && it.isVisibleToUser && it.performAction(AccessibilityNodeInfo.ACTION_CLICK)}?:false
        };return (0 until n.childCount).any {click(label,n.getChild(it))}
    }
    private fun scroll(n: AccessibilityNodeInfo?=inst.uiAutomation.rootInActiveWindow): Boolean {
        if(n==null)return false
        if(n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true
        return (0 until n.childCount).any {scroll(n.getChild(it))}
    }
    private fun waitFor(label: String,check: ()->Boolean) {
        val end=System.currentTimeMillis()+40_000
        while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(100)}
        folder.resolve("failure.txt").writeText(text());fail(label)
    }
    private fun capture(name: String) {
        inst.waitForIdleSync();inst.uiAutomation.waitForIdle(1000,5000)
        folder.resolve("$name.txt").writeText(text())
        folder.resolve("$name.png").outputStream().use {assertTrue(inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
    }
    private fun openUsage(): ActivityScenario<StudyFlutterActivity> {
        val scenario=ActivityScenario.launch<StudyFlutterActivity>(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/chat"))
        waitFor("drawer missing"){click("历史对话")};waitFor("usage missing"){click("请求用量")}
        waitFor("actual ledger missing"){text().contains("范围内 0 次请求")}
        return scenario
    }
    @Test fun prepare(): Unit=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val k=GlobalContext.get();val settings=k.get<SettingsStore>();settings.settingsFlow.first {!it.init}
        val owner=settings.createLearnerProfile("QA recovery $fixture");settings.selectLearnerProfile(owner)
        settings.settingsFlow.first {it.activeProfileId==owner};durable("owner",owner)
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE)
        prefs.edit().putString("theme",args.getString("theme")?:"light").putFloat("fontScale",args.getString("font")?.toFloat()?:1f).commit()
        val scenario=openUsage();val files=k.get<MaterialFiles>()
        waitFor("UI export action missing"){click("导出 JSON 快照")}
        waitFor("no actual verified receipt"){files.list(owner).singleOrNull()?.let {it.state=="SUCCEEDED" && text().contains("文件 SHA256：${it.sha256}") }==true}
        val item=files.list(owner).single();val bytes=files.verifiedFile(owner,item.id).readBytes()
        folder.resolve("original.json").writeBytes(bytes);durable("artifact",item.id);durable("hash",item.sha256)
        capture("before-stop");durable("ready","ui-exported")
        while(true)delay(1000)
        @Suppress("UNREACHABLE_CODE") scenario.close()
    }
    @Test fun verify(): Unit=runBlocking {
        val owner=folder.resolve("owner").readText();val k=GlobalContext.get();val settings=k.get<SettingsStore>()
        assertEquals(owner,settings.settingsFlow.first{!it.init}.activeProfileId)
        val files=k.get<MaterialFiles>();val original=folder.resolve("original.json").readBytes()
        val hash=folder.resolve("hash").readText();val id=folder.resolve("artifact").readText()
        openUsage().use {
            waitFor("persistent intent not shown"){text().contains("文件尚待核验")}
            assertFalse(text().contains("已生成并核验"));capture("reopened-pending")
            waitFor("manual verification unreachable"){if(click("核验上次导出"))true else {scroll();false}}
            waitFor("manual original readback receipt missing"){text().contains("文件 SHA256：$hash") && text().contains("复用原快照")}
            capture("reopened-verified")
            assertEquals(1,files.list(owner).size);assertArrayEquals(original,files.verifiedFile(owner,id).readBytes())
            assertEquals(id,AgentUsageExportHistory(context).last(owner,null,null,null,"requests")!!.artifactId)
            waitFor("original preview unreachable"){if(click("查看导出快照"))true else {scroll();false}}
            waitFor("actual preview missing"){text().contains("成果预览") && text().contains("sourceSha256")}
            capture("reopened-preview")
        }
        println("USAGE_RECOVERY_ACTUAL_UI_PASS")
    }
}
