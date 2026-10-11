package me.rerere.rikkahub.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.feature.material.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MaterialPaginationTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    @Test fun toolCanReadTailAndPreviewCanChangePages() {
        val koin = GlobalContext.get(); val settings = koin.get<SettingsStore>(); val service = koin.get<ChatService>(); val store = koin.get<MaterialFiles>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val owner = runBlocking { settings.createLearnerProfile("QA长内容") }
        val text = "长内容保真。".repeat(1000) + "\n姓名\t分数\n中文\t19\nTAIL_PREVIEW"
        try {
            val item = runBlocking {
                withTimeout(15000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner } } }
                val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "QA分页.md", "md", false)) { it.writeText(text); "真实长文本" }
                val config = settings.settingsFlow.value
                val tool = materialTools(store, owner, config, koin.get(), config.getCurrentChatModel()!!).single { it.name == "read_material_text_page" }
                fun args(offset: Int, count: Int) = buildJsonObject { put("id", item.id); put("offset", offset.toString()); put("count", count.toString()) }
                val first = Json.parseToJsonElement((tool.execute(args(0, 4000)).single() as UIMessagePart.Text).text).jsonObject
                assertEquals(4000, first["next_offset"]!!.jsonPrimitive.int)
                val tail = Json.parseToJsonElement((tool.execute(args(4000, 24000)).single() as UIMessagePart.Text).text).jsonObject
                assertTrue(tail["untrusted_file_data"]!!.jsonPrimitive.content.endsWith("TAIL_PREVIEW")); assertNull(tail["next_offset"])
                val invalid = (tool.execute(args(-1, 24000)).single() as UIMessagePart.Text).text
                assertTrue(invalid.contains("FAILED"))
                service.switchLearnerProfile(owner); item
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("材料").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("材料").onLast().performClick()
            compose.onNodeWithText(item.name).performScrollTo()
            compose.onNodeWithText("查看内容").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("下一页").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("下一页").performClick()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                .onLast().performScrollToNode(hasText("TAIL_PREVIEW"))
            compose.onNodeWithText("TAIL_PREVIEW").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("中文").assertExists(); compose.onNodeWithText("19").assertExists()
            compose.onNodeWithText("上一页").performClick()
            compose.onNodeWithText("TAIL_PREVIEW").assertDoesNotExist()
            compose.onNodeWithText("关闭").performClick()
        } finally { runBlocking { restoreMemoryProfileFixture(original, settings) } }
    }
}
