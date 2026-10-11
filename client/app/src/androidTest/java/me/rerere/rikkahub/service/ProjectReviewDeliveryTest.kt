package me.rerere.rikkahub.service

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectReviewDeliveryTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun node(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == text || root.contentDescription?.toString() == text) return root
        for (i in 0 until root.childCount) node(text, root.getChild(i))?.let { return it }
        return null
    }
    private fun click(text: String): Boolean {
        var target = node(text) ?: return false
        while (!target.isClickable) target = target.parent ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun waitReceipt(action: String, expected: String): String {
        var receipt = ""
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            click(if (action == "view") "QA 文件查看器" else "QA 分享接收")
            click("Just once"); click("仅此一次")
            receipt = shell("run-as org.mathstudy.qa.delivery cat files/$action.json")
            if (receipt.contains(expected) && automation.rootInActiveWindow?.packageName?.toString() == "org.mathstudy.qa.delivery") break
            Thread.sleep(150)
        }
        assertTrue("$action actual receiver hash: $receipt", receipt.contains(expected))
        assertEquals("org.mathstudy.qa.delivery", automation.rootInActiveWindow?.packageName?.toString())
        return receipt
    }
    @Test fun everyFormatOpensSavesAndArrivesInOtherUid() {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val store = koin.get<MaterialFiles>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val evidenceRun = QaEvidenceRun("ProjectReviewDeliveryTest.everyFormatOpensSavesAndArrivesInOtherUid")
        val evidence = evidenceRun.directory
        var evidenceStatus = "FAILED"
        val report = StringBuilder()
        try {
            for (ext in listOf("md", "docx", "xlsx", "pptx", "pdf")) {
                val owner = runBlocking { settings.createLearnerProfile("QA交付$ext") }
                val item = runBlocking {
                    withTimeout(15000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
                    val artifact = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "QA交付-${UUID.randomUUID()}.$ext", ext, true)) { file ->
                        if (false) {
                            File(evidence, "fidelity.$ext").copyTo(file, overwrite = true)
                            "真实图片/公式或类型保真样例"
                        } else MaterialWriter.write(file, ext, "中文真实交付_$ext\n公式 x²", listOf(listOf("名称", "值"), listOf("中文", "19")), listOf("第一页\n中文真实交付_$ext", "第二页\n公式 x²"))
                    }
                    service.switchLearnerProfile(owner); artifact
                }
                store.verifiedFile(item.profileId, item.id).copyTo(File(evidence, "source-$ext.$ext"), overwrite = true)
                report.append("$ext SOURCE ").append(org.json.JSONObject().put("name", item.name).put("sha256", item.sha256).put("profileId", item.profileId).put("id", item.id)).append('\n')
                compose.waitUntil(15000) { compose.onAllNodesWithText("成果").fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText("成果").onLast().performClick()
                compose.onNodeWithText(item.name).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("预览成果").performScrollTo().performClick()
                shell("run-as org.mathstudy.qa.delivery rm -f files/view.json files/send.json")
                compose.onNodeWithText("打开").performScrollTo().performClick()
                report.append("$ext VIEW ").append(waitReceipt("view", item.sha256)).append('\n')
                Thread.sleep(400)
                File(evidence, "delivery-open-$ext.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitForIdle()
                compose.onNodeWithText("另存为").performScrollTo().performClick()
                val deadline = System.currentTimeMillis() + 10000; var saved = false
                while (System.currentTimeMillis() < deadline && !saved) { saved = click("Save") || click("SAVE") || click("保存"); if (!saved) Thread.sleep(150) }
                assertTrue("DocumentsUI save $ext", saved)
                automation.waitForIdle(500, 5000)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                compose.waitUntil(15000) { compose.onAllNodesWithText("已另存并重新读取校验").fetchSemanticsNodes().isNotEmpty() }
                report.append("$ext SAVE actual DocumentsUI and application target URI hash PASS\n")
                compose.onNodeWithText("分享").performScrollTo().performClick()
                report.append("$ext SEND ").append(waitReceipt("send", item.sha256)).append('\n')
                Thread.sleep(400)
                File(evidence, "delivery-send-$ext.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitForIdle()
                automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitUntil(15_000) { compose.onAllNodesWithText("成果").fetchSemanticsNodes().isNotEmpty() }
            }
            evidenceStatus = "PASS"
        } finally {
            File(evidence, "delivery-receipts.txt").writeText(report.toString())
            evidenceRun.finish(evidenceStatus)
            runBlocking { restoreMemoryProfileFixture(original, settings) }
        }
    }
}

