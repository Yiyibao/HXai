package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.feature.memory.LearningMemoryStore
import me.rerere.rikkahub.feature.memory.MemoryType
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Actual generated Dart/AAR → Pigeon → native store, synthetic QA data only. */
class MemoryProtectionSurfaceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun nodes(n: AccessibilityNodeInfo? = inst.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { nodes(n.getChild(it)) }
    private fun text() = nodes().joinToString("\n") { "${it.text ?: ""} ${it.contentDescription ?: ""}" }
    private fun click(label: String): Boolean {
        val node = nodes().firstOrNull { it.text?.toString()?.contains(label) == true || it.contentDescription?.toString()?.contains(label) == true } ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current?.let { it.isEnabled && it.isVisibleToUser && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
    }
    private fun waitFor(label: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(100) }
        context.cacheDir.resolve("memory-protection-surface-failure.txt").writeText(text())
        fail(label)
    }
    private fun capture(label: String) {
        inst.waitForIdleSync()
        val folder = context.cacheDir.resolve("memory-protection-surface").apply { mkdirs() }
        folder.resolve("$label.txt").writeText(text())
        folder.resolve("$label.png").outputStream().use {
            assertTrue(inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun runSurface(dark: Boolean): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val k = GlobalContext.get()
        val settings = k.get<SettingsStore>()
        val originalOwner = settings.settingsFlow.first { !it.init }.activeProfileId
        val owner = settings.createLearnerProfile(if (dark) "QA protected dark" else "QA protected light")
        settings.selectLearnerProfile(owner)
        settings.setLearnerMemoryEnabled(owner, true)
        val store = k.get<LearningMemoryStore>()
        val id = store.addManual(owner, "qa-surface", MemoryType.PREFERENCE, "可管理的原记录哨兵")
        store.change(owner) { it.copy(recoveryBlocked = true) to Unit }
        val db = k.get<AppDatabase>()
        val payload = db.learningMemoryDao().get(owner)!!.payload
        val appearance = context.getSharedPreferences("study_appearance", Context.MODE_PRIVATE)
        val priorTheme = appearance.getString("theme", "system")
        val priorFont = appearance.getFloat("fontScale", 1f)
        appearance.edit().putString("theme", if (dark) "dark" else "light").putFloat("fontScale", if (dark) 2f else 1f).commit()
        val label = if (dark) "dark-200" else "light-100"
        try {
            ActivityScenario.launch<StudyFlutterActivity>(Intent(context, StudyFlutterActivity::class.java)).use {
                waitFor("actual drawer missing") { click("历史对话") }
                waitFor("actual settings missing") { click("设置") }
                waitFor("actual memory page missing") { click("记忆") }
                waitFor("actual protected status missing") { text().contains("记忆保护中：") }
                assertFalse(text().contains("记忆已开启："))
                capture("$label-before-disable")
                waitFor("actual switch missing") { click("自动抽取与聊天记忆引用设置") }
                waitFor("switch did not persist") { !settings.settingsFlow.value.learnerProfiles.single { it.id == owner }.memoryEnabled }
                waitFor("protection lost on switch") { text().contains("记忆保护中：") && !text().contains("正在核对记忆状态") }
                assertFalse(click("自动抽取与聊天记忆引用设置"))
                assertEquals(payload, db.learningMemoryDao().get(owner)!!.payload)
                assertEquals(id, store.read(owner).items.single().id)
                capture("$label-disabled-protected")
                assertTrue(nodes().any { it.text?.toString()?.contains("记忆保护中：") == true || it.contentDescription?.toString()?.contains("记忆保护中：") == true })
            }
            println("MEMORY_PROTECTION_ACTUAL_UI_PASS $label")
        } finally {
            appearance.edit().putString("theme", priorTheme).putFloat("fontScale", priorFont).commit()
            settings.selectLearnerProfile(originalOwner)
        }
    }
    @Test fun protectedLightUiUsesRealNativeState() = runSurface(false)
    @Test fun protectedDarkLargeUiUsesRealNativeState() = runSurface(true)
    @Test fun resumedPageRechecksProtectionWithoutChangingProfileOrEpoch(): Unit = runBlocking {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val k = GlobalContext.get(); val settings = k.get<SettingsStore>()
        val originalOwner = settings.settingsFlow.first { !it.init }.activeProfileId
        val owner = settings.createLearnerProfile("QA resume protection")
        settings.selectLearnerProfile(owner); settings.setLearnerMemoryEnabled(owner, true)
        val store = k.get<LearningMemoryStore>()
        store.addManual(owner, "resume", MemoryType.PREFERENCE, "恢复后仍保留的原记录哨兵")
        try {
            ActivityScenario.launch<StudyFlutterActivity>(Intent(context, StudyFlutterActivity::class.java)).use { scenario ->
                waitFor("drawer missing") { click("历史对话") }
                waitFor("settings missing") { click("设置") }
                waitFor("memory page missing") { click("记忆") }
                waitFor("healthy state missing") { text().contains("记忆已开启：") }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                store.change(owner) { it.copy(recoveryBlocked = true) to Unit }
                val payload = k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                waitFor("resume kept stale healthy memory state") { text().contains("记忆保护中：") }
                assertFalse(text().contains("记忆已开启："))
                assertEquals(payload, k.get<AppDatabase>().learningMemoryDao().get(owner)!!.payload)
                capture("resume-rechecked")
            }
        } finally { settings.selectLearnerProfile(originalOwner) }
    }
}
