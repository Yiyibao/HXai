package me.rerere.rikkahub.feature.material

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.service.restoreMemoryProfileFixture
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

/** Real installed Flutter -> protected MethodChannel -> durable material read/delete, not a widget mock. */
class MaterialLibrarySurfaceRuntimeTest {
    @Test fun actualLibraryReadsFixedSourceAndDeletesIntoTrashInBothThemesAndLargeText(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val automation = instrumentation.uiAutomation
        val settings = GlobalContext.get().get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val owner = settings.createLearnerProfile("B04 synthetic library surface")
        val store = GlobalContext.get().get<MaterialFiles>()
        val prefs = context.getSharedPreferences("study_appearance", Context.MODE_PRIVATE)
        val oldTheme = prefs.getString("theme", null); val oldScale = if (prefs.contains("fontScale")) prefs.getFloat("fontScale", 1f) else null
        val evidence = File(context.cacheDir, "b04-library-surface").apply { mkdirs() }
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        fun text(node: AccessibilityNodeInfo?): String = if (node == null) "" else buildString {
            node.text?.let { append(it).append('\n') }; node.contentDescription?.let { append(it).append('\n') }
            for (i in 0 until node.childCount) append(text(node.getChild(i)))
        }
        fun click(label: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (node == null) return false
            if (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true) {
                var target = node
                while (!target!!.isClickable) target = target.parent ?: return false
                return target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until node.childCount).any { click(label, node.getChild(it)) }
        }
        fun scroll(node: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { Thread.sleep(400); return true }
            return (0 until node.childCount).any { scroll(node.getChild(it)) }
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40000
            while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
            File(evidence, "failure.txt").writeText(text(automation.rootInActiveWindow))
            File(evidence, "failure.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            fail(label)
        }
        fun capture(label: String) {
            File(evidence, "$label.txt").writeText(text(automation.rootInActiveWindow))
            File(evidence, "$label.png").outputStream().use { assertTrue(automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        }
        try {
            settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
            for ((theme, scale) in listOf("light" to 1f, "dark" to 2f)) {
                val name = "B04-$theme-真实资料库与跨会话固定版本引用的长文件名教材.md"
                val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, name, "md")) { it.writeText("# Chapter 1\nB04 REAL SOURCE $theme\n"); "Synthetic durable library source" }
                store.organize(owner, item.id, item.sha256, "数学/微积分", listOf("B04", theme))
                prefs.edit().putString("theme", theme).putFloat("fontScale", scale).commit()
                scenario = ActivityScenario.launch(Intent(context, StudyFlutterActivity::class.java))
                waitFor("drawer did not open") { click("历史对话") }
                waitFor("library entry did not navigate") { click("文件库") }
                waitFor("real native metadata not displayed") { text(automation.rootInActiveWindow).contains(name) }
                capture("$theme-catalog")
                waitFor("real fixed version detail not open") { click(name) }
                waitFor("source hash not displayed") { text(automation.rootInActiveWindow).contains(item.sha256) }
                capture("$theme-detail-before-read")
                waitFor("reader action not reachable at this text size") { if (click("读取真实正文")) true else { scroll(); false } }
                waitFor("real source body not delivered to Flutter") { if (text(automation.rootInActiveWindow).contains("B04 REAL SOURCE $theme")) true else { scroll(); false } }
                capture("$theme-real-source")
                assertEquals(item.sha256, MaterialFiles.digest(store.verifiedFile(owner, item.id)))
                waitFor("math source surface unavailable") { if (click("数学题目 / 解答批改")) true else { scroll(); false } }
                waitFor("math source check unavailable") { if (click("检查来源状态")) true else { scroll(); false } }
                waitFor("actual source readiness not displayed") { if (text(automation.rootInActiveWindow).contains("SOURCE_READY")) true else { scroll(); false } }
                capture("$theme-math-source")
                waitFor("delete command unavailable") { click("删除版本") }
                waitFor("delete confirmation not actionable") { click("移到回收站") }
                waitFor("native tombstone not committed") { store.list(owner).none { it.id == item.id } }
                waitFor("trash not reachable") { click("本机回收站") }
                waitFor("real tombstone not displayed") { text(automation.rootInActiveWindow).contains(name) }
                capture("$theme-trash")
                assertEquals(item.id, store.trash(owner).single { it.id == item.id }.id)
                scenario.close(); scenario = null
            }
            println("B04_LIBRARY_SURFACE_EVIDENCE=${evidence.absolutePath}")
        } finally {
            scenario?.close()
            val editor = prefs.edit(); if (oldTheme == null) editor.remove("theme") else editor.putString("theme", oldTheme)
            if (oldScale == null) editor.remove("fontScale") else editor.putFloat("fontScale", oldScale); editor.commit()
            restoreMemoryProfileFixture(original, settings)
        }
    }
}
