package me.rerere.rikkahub.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MaterialPageTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    @Test fun actualPageContentShareAndProfileIsolation() {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val store = koin.get<MaterialFiles>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val owner = runBlocking { settings.createLearnerProfile("QA文件A") }
        val other = runBlocking { settings.createLearnerProfile("QA文件B") }
        val artifactName = "QA真实成果${UUID.randomUUID().toString().take(8)}.md"
        try {
            runBlocking {
                withTimeout(20000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == other } } }
                store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, artifactName, "md", true)) { it.writeText("QA成果独有内容"); "已生成并校验" }
                service.switchLearnerProfile(owner)
            }
            compose.waitUntil(20000) { compose.onAllNodesWithText("成果").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("成果").onLast().performClick()
            compose.onNodeWithText(artifactName).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("预览成果").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("QA成果独有内容").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("QA成果独有内容", substring = true).assertExists()
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitForIdle()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, "material-page.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("另存为").performScrollTo().performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            fun saveButton(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString()?.lowercase() in setOf("save", "保存")) return if (node.isClickable) node else node.parent
                for (i in 0 until node.childCount) saveButton(node.getChild(i))?.let { return it }
                return null
            }
            var button: android.view.accessibility.AccessibilityNodeInfo? = null
            repeat(30) { if (button == null) { Thread.sleep(100); button = saveButton(automation.rootInActiveWindow) } }
            org.junit.Assert.assertNotNull("actual DocumentsUI Save button", button)
            File(context.cacheDir, "material-save-dialog.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            org.junit.Assert.assertTrue(button!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(15000) { compose.onAllNodesWithText("已另存并重新读取校验").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("分享").performScrollTo().performClick()
            Thread.sleep(1000)
            automation.waitForIdle(500, 5000)
            var shareRoot = automation.rootInActiveWindow
            val shareDeadline = System.currentTimeMillis() + 15000
            while ((shareRoot == null || shareRoot.packageName?.toString() == context.packageName) && System.currentTimeMillis() < shareDeadline) { Thread.sleep(150); shareRoot = automation.rootInActiveWindow }
            org.junit.Assert.assertNotNull(shareRoot)
            File(context.cacheDir, "material-share-ui.txt").writeText("Active sharing window package: ${shareRoot!!.packageName}\n")
            org.junit.Assert.assertNotEquals(context.packageName, shareRoot.packageName?.toString())
            File(context.cacheDir, "material-share-dialog.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitForIdle()
            runBlocking { service.switchLearnerProfile(other) }
            compose.waitUntil(20000) { compose.onAllNodesWithText(artifactName).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("QA成果独有内容", substring = true).assertDoesNotExist()
            File(context.cacheDir, "material-profile-b.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { runBlocking { restoreMemoryProfileFixture(original, settings) } }
    }
}
