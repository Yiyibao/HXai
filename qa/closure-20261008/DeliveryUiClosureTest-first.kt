package me.rerere.rikkahub.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import android.view.accessibility.AccessibilityNodeInfo

class DeliveryUiClosureTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun node(text: String, root: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
        if(root == null) return null
        if(root.text?.toString() == text || root.contentDescription?.toString() == text) return root
        for(i in 0 until root.childCount) node(text,root.getChild(i))?.let { return it }
        return null
    }
    private fun click(text:String):Boolean { var target=node(text) ?: return false; while(!target.isClickable) target=target.parent ?: return false; return target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    @Test fun pickerCancelAndProfileChangeDoNotDeliverOldSource() {
        val settings=GlobalContext.get().get<SettingsStore>(); val service=GlobalContext.get().get<ChatService>(); val store=GlobalContext.get().get<MaterialFiles>(); val original=runBlocking { settings.settingsFlow.first { !it.init } }
        val evidence=QaEvidenceRun("DeliveryUiClosureTest.pickerCancelAndProfileChangeDoNotDeliverOldSource"); val report=StringBuilder(); val created=mutableListOf<MaterialFile>(); var status="FAILED"
        try {
            val owner=runBlocking { settings.createLearnerProfile("QA cancel ${evidence.runId.take(8)}") }
            val other=runBlocking { settings.createLearnerProfile("QA switch ${evidence.runId.take(8)}") }
            runBlocking { withTimeout(20000) { settings.settingsFlow.first { s -> s.learnerProfiles.any { it.id == owner } && s.learnerProfiles.any { it.id == other } } } }
            val item=runBlocking { val value=store.runTask(MaterialFile(UUID.randomUUID().toString(),owner,"QA-${UUID.randomUUID()}.md","md",true)) { it.writeText("QA immutable source"); "synthetic" }; service.switchLearnerProfile(owner); value }; created+=item
            compose.waitUntil(15000) { compose.onAllNodesWithText("成果").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("成果").onLast().performClick(); compose.onNodeWithText(item.name).performScrollTo().assertIsDisplayed(); compose.onNodeWithText("预览成果").performScrollTo().performClick()
            compose.onNodeWithText("另存为").performScrollTo().performClick()
            compose.waitUntil(15000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(15000) { compose.onAllNodesWithText("已取消另存，本地文件完整保留").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(item.sha256,MaterialFiles.digest(store.verifiedFile(owner,item.id))); report.append("pickerCancel sourceUnchanged=true noSuccess=true\n")
            compose.onNodeWithText("另存为").performScrollTo().performClick()
            compose.waitUntil(15000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            runBlocking { service.switchLearnerProfile(other) }
            // Deliver the late activity result after the underlying owner has changed.
            val deadline=System.currentTimeMillis()+15000; var saved=false
            while(System.currentTimeMillis()<deadline && !saved) { saved=click("SAVE") || click("保存"); if(!saved) Thread.sleep(100) }
            assertTrue("actual late DocumentsUI result",saved)
            compose.waitUntil(15000) { compose.onAllNodesWithText("另存取消：学习者或成果已改变，请重新选择").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(item.sha256,MaterialFiles.digest(store.verifiedFile(owner,item.id)))
            assertTrue(runCatching { store.verifiedFile(other,item.id) }.isFailure)
            val target="/sdcard/Download/${item.name}"
            val size=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("stat -c %s '$target'")).bufferedReader().use { it.readText().trim() }
            assertEquals("Rejected callback writes no source bytes", "0", size)
            report.append("profileChanged latePickerResult rejected=true exactTarget=$target bytes=$size sourceUnchanged=true\n")
            evidence.file("screen.png").outputStream().use { automation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; status="PASS"
        } finally {
            runBlocking { restoreMemoryProfileFixture(original,settings) }
            created.forEach { item -> val file=store.file(item); require(file.parentFile!!.name==item.profileId); file.delete(); File(file.parentFile,"${item.id}.meta.json").delete(); file.parentFile!!.delete() }
            evidence.file("assertions.txt").writeText(report.toString()); evidence.finish(status)
        }
    }
}
