package me.rerere.rikkahub.feature.agent

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.service.restoreMemoryProfileFixture
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentUsageNotificationSurfaceTest {
    @Test fun flutterSettingsOptInAndBackgroundLedgerEventsReachRealSystem(): Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        check(context.packageName=="org.hxsi.agent.nativeqa")
        val settings=GlobalContext.get().get<SettingsStore>();val original=settings.settingsFlow.first { !it.init }
        // The isolated runner excludes the production ViewModel module (and Firebase).
        val nativeSettingsModule=module { viewModelOf(::SettingVM) }
        loadKoinModules(nativeSettingsModule)
        val owner=settings.createLearnerProfile("QA notification owner");val other=settings.createLearnerProfile("QA empty notification owner")
        val permission=Manifest.permission.POST_NOTIFICATIONS
        val automation=inst.uiAutomation;val manager=context.getSystemService(NotificationManager::class.java)
        val folder=File(context.cacheDir,"usage-notifications-${UUID.randomUUID()}").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>?=null
        fun hasNotification()=manager.activeNotifications.any { it.id==AgentUsageNotificationSink.ID }
        fun text(n: AccessibilityNodeInfo?): String=if(n==null) "" else buildString {
            n.text?.let { append(it).append('\n') };n.contentDescription?.let { append(it).append('\n') }
            for(i in 0 until n.childCount) append(text(n.getChild(i)))
        }
        fun waitFor(label: String,condition: () -> Boolean) {
            val end=System.currentTimeMillis()+30_000
            while(System.currentTimeMillis()<end) { if(condition())return;Thread.sleep(100) }
            File(folder,"failure.txt").writeText(text(automation.rootInActiveWindow));fail(label)
        }
        fun capture(name: String) {
            File(folder,"$name.txt").writeText(text(automation.rootInActiveWindow))
            File(folder,"$name.png").outputStream().use { assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        }
        fun find(label: String,n: AccessibilityNodeInfo?=automation.rootInActiveWindow): AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString()==label || n.contentDescription?.toString()==label)return n
            for(i in 0 until n.childCount) find(label,n.getChild(i))?.let { return it }
            return null
        }
        fun click(label: String): Boolean {
            var n=find(label);while(n!=null && !n.isClickable)n=n.parent
            return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)?:false
        }
        fun checkable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(n.isCheckable)return n
            for(i in 0 until n.childCount) n.getChild(i)?.let { checkable(it) }?.let { return it }
            return null
        }
        try {
            settings.selectLearnerProfile(owner);settings.settingsFlow.first { it.activeProfileId==owner }
            settings.update { it.copy(displaySetting=it.displaySetting.copy(enableAgentUsageNotifications=false)) }
            if(android.os.Build.VERSION.SDK_INT>=33)
                automation.grantRuntimePermission(context.packageName,permission)
            scenario=ActivityScenario.launch(Intent(context,StudyFlutterActivity::class.java))
            waitFor("drawer missing") { click("历史对话") };waitFor("usage missing") { click("请求用量") }
            waitFor("native settings button missing") { click("后台用量提醒设置") }
            waitFor("native setting route missing") { find("后台用量核对提醒")!=null };capture("settings-off")
            var node=find("后台用量核对提醒")
            var toggle: AccessibilityNodeInfo?=null
            while(node!=null && toggle==null) { toggle=checkable(node);node=node.parent }
            assertNotNull("usage toggle missing",toggle);assertTrue(toggle!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("setting did not persist") { settings.settingsFlow.value.displaySetting.enableAgentUsageNotifications };capture("settings-on")
            assertTrue("Native back button missing",click(context.getString(me.rerere.rikkahub.R.string.back)))
            waitFor("Flutter did not resume") { text(automation.rootInActiveWindow).contains("后台用量提醒设置") }
            AgentTaskStore(context).use { store ->
                fun synthetic(): AgentTask {
                    val t=store.accept(owner,"synthetic-notification-conversation",UUID.randomUUID().toString(),
                        "private synthetic goal","private-ref","provider","model","HIGH","revision")
                    val id=UUID.randomUUID().toString()
                    store.writableDatabase.insertOrThrow("usage",null,ContentValues().apply {
                        put("request_id",id);put("task_id",t.id);put("role","ocr");put("provenance","unknown");put("created_at",System.currentTimeMillis())
                    })
                    store.writableDatabase.insertOrThrow("calls",null,ContentValues().apply {
                        put("task_id",t.id);put("call_id",id);put("tool","provider:ocr");put("input_hash","synthetic");put("state","UNKNOWN")
                    })
                    AgentTaskStore.usageChanges.tryEmit(Unit);return t
                }
                val t=synthetic();val before=store.snapshot(owner,t.id)
                Thread.sleep(1_000);assertFalse("foreground notification",hasNotification())
                automation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                waitFor("activity did not enter actual background") { scenario.state==Lifecycle.State.CREATED }
                waitFor("background signal did not reach system") { hasNotification() }
                automation.executeShellCommand("cmd statusbar expand-notifications").close()
                waitFor("notification title not visible in actual system shade") {
                    hasNotification() && text(automation.rootInActiveWindow).contains("请求用量需要核对")
                }
                capture("system-background")
                automation.executeShellCommand("cmd statusbar collapse").close()
                settings.update { it.copy(displaySetting=it.displaySetting.copy(enableAgentUsageNotifications=false)) }
                waitFor("disabled setting did not cancel") { !hasNotification() }
                settings.update { it.copy(displaySetting=it.displaySetting.copy(enableAgentUsageNotifications=true)) }
                Thread.sleep(1_000);assertFalse("same task re-alerted",hasNotification())
                synthetic();waitFor("new task did not notify") { hasNotification() }
                settings.selectLearnerProfile(other)
                waitFor("old owner notification survived switch") { !hasNotification() }
                AgentTaskStore.usageChanges.tryEmit(Unit);Thread.sleep(700)
                assertFalse("foreign owner re-alerted",hasNotification())
                assertEquals(before,store.snapshot(owner,t.id));assertEquals(1,store.physicalRequestCount(owner,t.id))
            }
            println("USAGE_NOTIFICATIONS_EVIDENCE=${folder.name}")
        } finally {
            scenario?.close()
            automation.executeShellCommand("cmd statusbar collapse").close()
            settings.update { it.copy(displaySetting=it.displaySetting.copy(enableAgentUsageNotifications=false)) }
            restoreMemoryProfileFixture(original,settings)
            AgentUsageNotificationSink(context).cancel()
            unloadKoinModules(nativeSettingsModule)
            // Host permission phases restore the isolated QA permission outside this process.
        }
    }
}
