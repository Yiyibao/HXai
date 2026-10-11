package me.rerere.rikkahub.service

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.feature.material.MaterialWriter
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.flutter.StudyHostFacade
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

/** Actual generated DOCX -> verified native extraction -> Flutter detail surface. */
class ArtifactPreviewContentTest {
    @Test fun actualDocxBodyAndScopeAreVisibleNotProcessingStatus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val materials = koin.get<MaterialFiles>()
        val chat = koin.get<ChatService>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val owner = runBlocking { settings.createLearnerProfile("QA正文预览") }
        val marker = "ACTUAL-DOCX-BODY-${UUID.randomUUID().toString().take(8)}"
        var item: MaterialFile? = null
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        fun contains(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (root == null) return false
            if (root.text?.toString()?.contains(text) == true || root.contentDescription?.toString()?.contains(text) == true) return true
            return (0 until root.childCount).any { contains(text, root.getChild(it)) }
        }
        fun click(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (root == null) return false
            if (root.text?.toString()?.contains(text) == true || root.contentDescription?.toString()?.contains(text) == true) {
                var target: AccessibilityNodeInfo = root
                while (!target.isClickable) target = target.parent ?: return false
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until root.childCount).any { click(text, root.getChild(it)) }
        }
        fun waitFor(description: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(150)
            }
            fail(description)
        }
        try {
            item = runBlocking {
                settings.settingsFlow.first { state -> state.learnerProfiles.any { it.id == owner } }
                chat.switchLearnerProfile(owner)
                materials.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "QA-preview.docx", "docx", output = true)) { file ->
                    MaterialWriter.write(file, "docx", marker, emptyList(), emptyList())
                    "操作已完成"
                }
            }
            val facade = StudyHostFacade(context, settings, koin.get(), chat, koin.get(), materials, koin.get(), pickFiles = { emptyList() })
            try {
                runBlocking {
                    val bootstrap = facade.getBootstrap(owner, 1L)
                    val listed = facade.getArtifacts(owner).single { it.id == item!!.id }
                    assertNull(listed.previewContent)
                    val preview = facade.getArtifactPreview(owner, item!!.id, bootstrap.contextEpoch)
                    assertTrue(preview.previewContent!!.contains(marker))
                    assertEquals("操作已完成", preview.statusDetail)
                    assertTrue(preview.previewCoverage!!.contains("Office 文本提取"))
                }
            } finally { facade.close() }
            scenario = ActivityScenario.launch(Intent(context, StudyFlutterActivity::class.java).putExtra("route", "/artifacts"))
            waitFor("artifact list not rendered") { contains("QA-preview.docx") }
            waitFor("artifact row not clickable") { click("QA-preview.docx") }
            waitFor("actual DOCX body absent from Flutter preview") { contains(marker) }
            assertTrue(contains("Office 文本提取"))
            assertTrue(contains("处理状态：操作已完成"))
            File(context.cacheDir, "review-fixed-artifact-body.png").outputStream().use {
                automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            scenario?.close()
            item?.let { materials.file(it).delete(); File(context.filesDir, "materials/$owner/${it.id}.meta.json").delete() }
            runBlocking { restoreMemoryProfileFixture(original, settings) }
        }
    }
}
