package me.rerere.rikkahub.service

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

/** Real repository + real Flutter accessibility tree, without mocked bridge reads. */
class DetailIsolationSurfaceTest {
    @Test fun deletedFileDetailIsRevokedWhenReturningFromNativeTools() = verifyRevocation(switchProfile = false)
    @Test fun oldProfileDetailIsRevokedWhenResumingTheReturnStack() = verifyRevocation(switchProfile = true)

    private fun verifyRevocation(switchProfile: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val materials = koin.get<MaterialFiles>()
        val chat = koin.get<ChatService>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val owner = runBlocking { settings.createLearnerProfile("QA详情隔离A") }
        val other = if (switchProfile) runBlocking { settings.createLearnerProfile("QA详情隔离B") } else null
        val marker = "PRIVATE-DETAIL-${UUID.randomUUID().toString().take(8)}"
        val suffix = if (switchProfile) "profile" else "deleted"
        var item: MaterialFile? = null
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        fun contains(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (root == null) return false
            if (root.text?.toString()?.contains(text) == true ||
                root.contentDescription?.toString()?.contains(text) == true ||
                root.hintText?.toString()?.contains(text) == true) return true
            return (0 until root.childCount).any { contains(text, root.getChild(it)) }
        }
        fun click(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (root == null) return false
            if (root.text?.toString()?.contains(text) == true || root.contentDescription?.toString()?.contains(text) == true) {
                var target: AccessibilityNodeInfo = root
                while (!target.isClickable) target = target.parent ?: return false
                if (!target.isEnabled || !target.isVisibleToUser) return false
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return (0 until root.childCount).any { click(text, root.getChild(it)) }
        }
        fun actionable(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (root == null) return false
            if (root.text?.toString()?.contains(text) == true || root.contentDescription?.toString()?.contains(text) == true) {
                var target: AccessibilityNodeInfo = root
                while (!target.isClickable) target = target.parent ?: return false
                return target.isEnabled && target.isVisibleToUser
            }
            return (0 until root.childCount).any { actionable(text, root.getChild(it)) }
        }
        fun saveSurface(stage: String): String {
            val tree = StringBuilder()
            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null) return
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                tree.append("  ".repeat(depth)).append(node.className)
                    .append(" text=").append(node.text)
                    .append(" description=").append(node.contentDescription)
                    .append(" hint=").append(node.hintText)
                    .append(" clickable=").append(node.isClickable)
                    .append(" editable=").append(node.isEditable)
                    .append(" enabled=").append(node.isEnabled)
                    .append(" visible=").append(node.isVisibleToUser)
                    .append(" bounds=").append(bounds).append('\n')
                for (child in 0 until node.childCount) visit(node.getChild(child), depth + 1)
            }
            visit(automation.rootInActiveWindow, 0)
            val name = "review-fixed-detail-$suffix-$stage"
            File(context.cacheDir, "$name.tree.txt").writeText(tree.toString())
            automation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            return tree.toString()
        }
        fun waitFor(stage: String, description: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(150)
            }
            val tree = saveSurface("$stage-timeout")
            fail("$description; current accessibility surface:\n$tree")
        }
        try {
            item = runBlocking {
                settings.settingsFlow.first { state -> state.learnerProfiles.any { it.id == owner } }
                other?.let { id -> settings.settingsFlow.first { state -> state.learnerProfiles.any { it.id == id } } }
                chat.switchLearnerProfile(owner)
                materials.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "$marker.txt", "txt")) { file ->
                    file.writeText("private source content", Charsets.UTF_8)
                    "真实文件已保存"
                }
            }
            scenario = ActivityScenario.launch(Intent(context, StudyFlutterActivity::class.java).putExtra("route", "/files"))
            waitFor("list", "real file list not rendered") { contains("文件库") && contains(marker) }
            var nextClickAt = 0L
            waitFor("detail", "file row action did not navigate to a usable detail") {
                val onDetail = contains("文件详情")
                if (!onDetail && contains("文件库") && contains(marker) &&
                    System.currentTimeMillis() >= nextClickAt) {
                    click(marker)
                    nextClickAt = System.currentTimeMillis() + 500
                }
                onDetail && contains(marker) && actionable("查看原文件")
            }
            saveSurface("opened")
            // This is the same stopped/resumed boundary as the native backup/file tools.
            scenario.moveToState(Lifecycle.State.CREATED)
            if (other != null) {
                runBlocking { chat.switchLearnerProfile(other) }
            } else {
                assertTrue(materials.file(item!!).delete())
                assertTrue(File(context.filesDir, "materials/$owner/${item!!.id}.meta.json").delete())
                assertFalse(materials.list(owner).any { it.id == item!!.id })
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("revoked", "cached private detail remained visible after resume") {
                contains("文件详情") && contains("文件不可用") &&
                    !contains(marker) && !actionable("查看原文件")
            }
            assertFalse(contains(marker))
            assertFalse(actionable("查看原文件"))
            assertFalse("revoked file action must not be callable", click("查看原文件"))
            saveSurface("revoked")
            File(context.cacheDir, "review-fixed-detail-$suffix-revoked.png").copyTo(
                File(context.cacheDir, "review-fixed-detail-$suffix.png"), overwrite = true,
            )
        } finally {
            scenario?.close()
            item?.let { materials.file(it).delete(); File(context.filesDir, "materials/$owner/${it.id}.meta.json").delete() }
            runBlocking { restoreMemoryProfileFixture(original, settings) }
        }
    }
}
