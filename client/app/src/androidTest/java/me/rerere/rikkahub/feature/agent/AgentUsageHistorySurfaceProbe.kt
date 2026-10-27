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
class AgentUsageHistorySurfaceProbe {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val fixture get()=requireNotNull(args.getString("fixture")).also {require(UUID.fromString(it).toString()==it)}
    private val folder get()=context.cacheDir.resolve("usage-history-surface-$fixture").apply {mkdirs()}
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
    private fun swipe(forward: Boolean,n: AccessibilityNodeInfo?=inst.uiAutomation.rootInActiveWindow): Boolean {
        if(n==null)return false
        if(n.isScrollable && n.isVisibleToUser) {
            val bounds=android.graphics.Rect();n.getBoundsInScreen(bounds)
            if(bounds.height()<100 || bounds.width()<100)return false
            val x=bounds.centerX().toFloat();val start=bounds.top+bounds.height()*(if(forward) 0.72f else 0.38f)
            val end=bounds.top+bounds.height()*(if(forward) 0.38f else 0.72f);val down=android.os.SystemClock.uptimeMillis()
            fun event(action: Int,y: Float) {
                val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
                e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(inst.uiAutomation.injectInputEvent(e,true));e.recycle()
            }
            event(android.view.MotionEvent.ACTION_DOWN,start)
            for(i in 1..6){Thread.sleep(35);event(android.view.MotionEvent.ACTION_MOVE,start+(end-start)*i/6)}
            event(android.view.MotionEvent.ACTION_UP,end);Thread.sleep(100);return true
        }
        return (0 until n.childCount).any {swipe(forward,n.getChild(it))}
    }
    private fun scroll()=swipe(true)
    private fun scrollBack()=swipe(false)
    private fun reveal(label: String) {
        waitFor("top controls unreachable") {if(text().contains("刷新用量"))true else {scrollBack();false}}
        waitFor("receipt text not reachable: $label") {
            if(text().contains(label))true else {scroll();false}
        }
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
        waitFor("actual ledger missing"){if(text().contains("范围内 0 次请求"))true else {scroll();false}}
        return scenario
    }
    @Test fun prepare(): Unit=runBlocking {
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val k=GlobalContext.get();val settings=k.get<SettingsStore>();settings.settingsFlow.first {!it.init}
        val owner=settings.createLearnerProfile("QA history ${fixture.take(8)}");settings.selectLearnerProfile(owner)
        settings.settingsFlow.first {it.activeProfileId==owner};durable("owner",owner)
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE)
        prefs.edit().putString("theme",args.getString("theme")?:"light").putFloat("fontScale",args.getString("font")?.toFloat()?:1f).commit()
        val scenario=openUsage();val files=k.get<MaterialFiles>()
        waitFor("UI export action missing"){if(click("导出 JSON 快照"))true else {scrollBack();false}}
        waitFor("no actual committed export"){files.list(owner).singleOrNull()?.state=="SUCCEEDED"}
        val item=files.list(owner).single();val bytes=files.verifiedFile(owner,item.id).readBytes()
        reveal("文件 SHA256：${item.sha256}")
        val host=me.rerere.rikkahub.flutter.StudyHostFacade(context,settings,k.get(),k.get(),k.get(),k.get(),k.get(),pickFiles={emptyList()},foregroundConfirmationAllowed={true})
        val oldRequest=UUID.randomUUID().toString()
        val task=AgentTaskStore(context).use {it.accept(owner,UUID.randomUUID().toString(),UUID.randomUUID().toString(),
            "synthetic history scope","missing","p","m","OFF","config").id}
        val scoped=try {val epoch=host.getBootstrap(owner,1).contextEpoch
            host.exportAgentUsage(owner,task,100,200,"role",oldRequest,epoch)
        } finally {host.close()}
        durable("request",oldRequest);durable("task",task)
        durable("scoped-artifact",scoped.artifactId);durable("scoped-hash",scoped.sha256)
        folder.resolve("scoped.json").writeBytes(files.verifiedFile(owner,scoped.artifactId).readBytes())
        folder.resolve("original.json").writeBytes(bytes);durable("artifact",item.id);durable("hash",item.sha256)
        capture("before-stop");durable("ready","history-exported")
        while(true)delay(1000)
        @Suppress("UNREACHABLE_CODE") scenario.close()
    }
    @Test fun verify(): Unit=runBlocking {
        val owner=folder.resolve("owner").readText();val k=GlobalContext.get();val settings=k.get<SettingsStore>()
        assertEquals(owner,settings.settingsFlow.first{!it.init}.activeProfileId)
        val files=k.get<MaterialFiles>();val original=folder.resolve("original.json").readBytes()
        val hash=folder.resolve("hash").readText();val id=folder.resolve("artifact").readText()
        openUsage().use {
            waitFor("history entry unreachable"){if(click("浏览历史导出"))true else {scrollBack();false}}
            waitFor("history data missing"){text().contains("历史导出") && text().contains("原范围：任务")}
            assertFalse(text().contains("已核验原快照"));capture("history-pending")
            val request=folder.resolve("request").readText();val task=folder.resolve("task").readText()
            val scopedId=folder.resolve("scoped-artifact").readText();val scopedHash=folder.resolve("scoped-hash").readText()
            waitFor("original request unreachable"){if(text().contains(request))true else {scroll();false}}
            capture("history-original-scope")
            // The first entry is the newest intent in another task/period/group, not the current all-requests filter.
            waitFor("original manual verification unreachable"){if(click("按原范围核验"))true else {scroll();false}}
            waitFor("original verified receipt missing"){if(text().contains("已核验原快照"))true else {scroll();false}}
            capture("history-reused")
            waitFor("original hash missing"){if(text().contains("文件 SHA256：$scopedHash"))true else {scroll();false}}
            capture("history-verified")
            assertEquals(2,files.list(owner).size);assertArrayEquals(original,files.verifiedFile(owner,id).readBytes())
            assertArrayEquals(folder.resolve("scoped.json").readBytes(),files.verifiedFile(owner,scopedId).readBytes())
            assertEquals(request,AgentUsageExportHistory(context).get(owner,task,100,200,"role",request).requestId)
            waitFor("history preview unreachable"){if(click("查看历史快照"))true else {scroll();false}}
            waitFor("actual historical preview missing"){text().contains("成果预览") && text().contains("sourceSha256")}
            capture("history-preview")

        }
        println("USAGE_HISTORY_ACTUAL_UI_PASS")
    }
}
