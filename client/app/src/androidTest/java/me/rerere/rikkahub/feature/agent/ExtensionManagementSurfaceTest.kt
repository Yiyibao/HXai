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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class ExtensionManagementSurfaceTest {
    @Test fun flutterManagementCommandsReadBackDurableStateAndHideOldProfile(): Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext;val automation=inst.uiAutomation
        val settings=GlobalContext.get().get<SettingsStore>();val original=settings.settingsFlow.first { !it.init }
        val a=settings.createLearnerProfile("QA Extension A");val b=settings.createLearnerProfile("QA Extension B")
        val prefs=context.getSharedPreferences("study_appearance",Context.MODE_PRIVATE)
        val originalTheme=prefs.getString("theme",null);val originalScale=if(prefs.contains("fontScale")) prefs.getFloat("fontScale",1f) else null
        val folder=context.cacheDir.resolve("extension-management-surface").apply { mkdirs() }
        fun text(n: AccessibilityNodeInfo?): String=if(n==null) "" else buildString {
            n.text?.let { append(it).append('\n') };n.contentDescription?.let { append(it).append('\n') }
            for(i in 0 until n.childCount) append(text(n.getChild(i)))
        }
        fun click(label: String,n: AccessibilityNodeInfo?=automation.rootInActiveWindow): Boolean {
            if(n==null)return false
            if(n.text?.toString()==label || n.contentDescription?.toString()==label) {
                var target=n;while(target!=null && !target.isClickable)target=target.parent
                return target?.let { it.isEnabled && it.isVisibleToUser && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
            }
            return (0 until n.childCount).any { click(label,n.getChild(it)) }
        }
        fun waitFor(message: String,condition: () -> Boolean) {
            val end=System.currentTimeMillis()+15_000
            while(System.currentTimeMillis()<end) { if(condition())return;Thread.sleep(100) }
            folder.resolve("failure.txt").writeText(text(automation.rootInActiveWindow));fail(message)
        }
        fun capture(name: String) {
            folder.resolve("$name.txt").writeText(text(automation.rootInActiveWindow))
            automation.takeScreenshot().let { bitmap -> folder.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        }
        GeneralMcpFixture().use { fixture ->
            try {
                settings.selectLearnerProfile(a);settings.settingsFlow.first { it.activeProfileId==a }
                ExtensionInstallationStore(context).use { it.install(a,"surface",0,fixture.config().copy(name="QA Extension service 长名称 αβ"),"") }
                prefs.edit().putString("theme","light").putFloat("fontScale",1f).commit()
                ActivityScenario.launch<StudyFlutterActivity>(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/settings/extensions")).use { scenario ->
                    waitFor("MCP management missing") { text(automation.rootInActiveWindow).contains("QA Extension service") }
                    capture("light-disabled")
                    waitFor("Enable action missing") { click("启用") };waitFor("Enable confirmation missing") { click("确认") }
                    waitFor("Enable not persisted") { ExtensionInstallationStore(context).use { it.get(a,"surface")?.state=="ENABLED" } }
                    scenario.recreate();waitFor("Durable state missing after recreation") { text(automation.rootInActiveWindow).contains("ENABLED") }
                    capture("light-enabled")
                    waitFor("Revoke action missing") { click("撤权") };waitFor("Revoke confirmation missing") { click("确认") }
                    waitFor("Revoke not persisted") { ExtensionInstallationStore(context).use { it.get(a,"surface")?.state=="REVOKED" } }
                    // Use the actual Activity's bridge command so profile_changed/epoch
                    // reaches Flutter, just as the user's profile picker does.
                    var activeFacade: me.rerere.rikkahub.flutter.StudyHostFacade?=null
                    scenario.onActivity { activity ->
                        val field=StudyFlutterActivity::class.java.getDeclaredField("facade").apply { isAccessible=true }
                        activeFacade=field.get(activity) as me.rerere.rikkahub.flutter.StudyHostFacade
                    }
                    assertTrue(requireNotNull(activeFacade).switchProfile(b,1).success)
                    settings.settingsFlow.first { it.activeProfileId==b }
                    waitFor("Old profile remained visible") { !text(automation.rootInActiveWindow).contains("QA Extension service") }
                    capture("profile-b")
                }
                settings.selectLearnerProfile(a);settings.settingsFlow.first { it.activeProfileId==a }
                prefs.edit().putString("theme","dark").putFloat("fontScale",2f).commit()
                ActivityScenario.launch<StudyFlutterActivity>(Intent(context,StudyFlutterActivity::class.java).putExtra("route","/settings/extensions")).use {
                    waitFor("Large dark page missing") { text(automation.rootInActiveWindow).contains("QA Extension service") }
                    capture("dark-large-revoked")
                }
            } finally {
                prefs.edit().apply { if(originalTheme==null)remove("theme") else putString("theme",originalTheme);if(originalScale==null)remove("fontScale") else putFloat("fontScale",originalScale) }.commit()
                settings.selectLearnerProfile(original.activeProfileId)
                me.rerere.rikkahub.service.restoreMemoryProfileFixture(original,settings)
            }
        }
    }
}
