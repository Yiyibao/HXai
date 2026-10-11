package me.rerere.rikkahub.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.feature.studychat.LearnerProfile
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

class DocumentServicePageTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    @Test fun downloadedResultShowsCoverageAndOriginalSourceLinks() {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val repository = koin.get<DocumentServiceRepository>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val evidence = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "document-service-live-evidence.json")
        val owner = Json.parseToJsonElement(evidence.readText()).jsonObject.getValue("owner").jsonPrimitive.content
        val config = repository.config(owner)
        try {
            repository.configure(owner, config.baseUrl, "", true)
            runBlocking {
                restoreMemoryProfileFixture(original.copy(learnerProfiles = original.learnerProfiles + LearnerProfile(owner, "QA文档服务")), settings)
                koin.get<ChatService>().switchLearnerProfile(owner)
            }
            compose.waitUntil(20000) { compose.onAllNodesWithText("材料").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("材料").onLast().performClick()
            compose.onNodeWithText("自建增强解析：已启用").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("查看结果").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Basic 解析结果").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("实际物理页：1, 2, 3, 4, 5 / 共 5 页；全文范围").assertExists()
            compose.onAllNodesWithText("核对原材料第 1 页").onFirst().assertExists()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, "document-service-preview.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText("自建增强解析：已启用").performScrollTo().performClick()
            compose.onNodeWithText("当前档案的自建文档服务").assertExists()
            File(context.cacheDir, "document-service-config.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("取消").performClick()
        } finally {
            repository.configure(owner, config.baseUrl, "", false)
            runBlocking { restoreMemoryProfileFixture(original, settings) }
        }
    }
}
