package me.rerere.rikkahub.service

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.feature.diagnostics.DiagnosticsPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import org.junit.Assert.*
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class DiagnosticsPageTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    @Test fun phoneFeedbackEntryAndClearRequireExplicitAction() {
        compose.activity.setContent { MaterialTheme { DiagnosticsPanel() } }
        compose.waitUntil(15000) { runCatching { compose.onAllNodesWithText("诊断与反馈").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithText("诊断与反馈").performClick()
        compose.onNodeWithText("保存诊断包").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("分享诊断包").performScrollTo().assertIsEnabled()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("锁屏后重开，结果未显示")
        compose.onNodeWithText("清除本机诊断记录").performScrollTo().performClick()
        compose.onNodeWithText("清除诊断记录？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("诊断与反馈").assertIsDisplayed()
    }

    @Test fun actualDocumentsSaveAndOtherUidShareDeliverCompleteZip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun node(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.text?.toString() == text || root.contentDescription?.toString() == text) return root
            for (index in 0 until root.childCount) node(text, root.getChild(index))?.let { return it }
            return null
        }
        fun click(text: String): Boolean {
            var target = node(text) ?: return false
            while (!target.isClickable) target = target.parent ?: return false
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val context = instrumentation.targetContext
        compose.activity.setContent { MaterialTheme { DiagnosticsPanel() } }
        compose.onNodeWithText("诊断与反馈").performClick()
        compose.waitForIdle()
        val screenshotDir = File(context.cacheDir, "diagnostic-evidence").apply { mkdirs() }
        File(screenshotDir, "panel.png").outputStream().use {
            automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("QA 诊断保存分享")
        compose.onNodeWithText("保存诊断包").performScrollTo().performClick()
        var saved = false
        val deadline = System.currentTimeMillis() + 15000
        while (!saved && System.currentTimeMillis() < deadline) {
            saved = click("Save") || click("SAVE") || click("保存")
            if (!saved) Thread.sleep(150)
        }
        assertTrue("Real DocumentsUI save", saved)
        compose.waitUntil(15000) { compose.onAllNodesWithText("诊断包已保存，可通过手机发送给开发者").fetchSemanticsNodes().isNotEmpty() }
        // Success requires re-reading the selected URI and matching its SHA-256 in production code.
        shell("run-as org.mathstudy.qa.delivery rm -f files/send.json")
        compose.onNodeWithText("分享诊断包").performScrollTo().performClick()
        val shareDeadline = System.currentTimeMillis() + 15000
        var receipt = ""
        while (System.currentTimeMillis() < shareDeadline) {
            click("QA 分享接收"); click("Just once"); click("仅此一次")
            receipt = shell("run-as org.mathstudy.qa.delivery cat files/send.json")
            if (receipt.startsWith("{") && automation.rootInActiveWindow?.packageName?.toString() == "org.mathstudy.qa.delivery") break
            Thread.sleep(150)
        }
        assertEquals("Receiver opened under another UID", "org.mathstudy.qa.delivery", automation.rootInActiveWindow?.packageName?.toString())
        val result = JSONObject(receipt)
        val exported = File(context.cacheDir, "diagnostic-exports").listFiles()!!.maxBy { it.lastModified() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(exported.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, result.getString("sha256"))
        assertEquals(exported.length(), result.getLong("size"))
        assertEquals("application/zip", result.getString("mime"))
        val evidence = File(context.cacheDir, "diagnostic-evidence").apply { mkdirs() }
        File(evidence, "delivery.json").writeText(JSONObject().put("size", exported.length()).put("sha256", expected).put("saveReReadVerified", true).put("receiverPackage", "org.mathstudy.qa.delivery").toString())
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        val returnDeadline = System.currentTimeMillis() + 10000
        while (automation.rootInActiveWindow?.packageName?.toString() != context.packageName && System.currentTimeMillis() < returnDeadline) Thread.sleep(100)
        assertEquals(context.packageName, automation.rootInActiveWindow?.packageName?.toString())
        compose.waitForIdle()
        compose.onNodeWithText("关闭").performClick()
    }
}
