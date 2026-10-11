package me.rerere.rikkahub.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

/** Regression assertions for defects found in the complete project review. */
@RunWith(AndroidJUnit4::class)
class ProjectReviewAuditTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "review-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        File(instrumentation.targetContext.cacheDir, "review-$name.txt").writeText(
            compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n") { it.config.toString() }
        )
    }
    private fun navigate(screen: Screen) {
        compose.waitUntil(20_000) {
            RouteActivity::class.java.getDeclaredField("navStack").apply { isAccessible = true }.get(compose.activity) != null
        }
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val stack = RouteActivity::class.java.getDeclaredField("navStack").apply { isAccessible = true }
                .get(compose.activity) as MutableList<NavKey>
            stack.add(screen)
        }
        compose.waitForIdle()
    }
    private fun fixture(output: Boolean = false, extension: String = "md", block: (MaterialFiles, MaterialFile) -> Unit) {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val store = koin.get<MaterialFiles>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val owner = runBlocking { settings.createLearnerProfile("QA本轮审查") }
        try {
            val item = runBlocking {
                settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == owner } }
                val item = store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "审查材料.$extension", extension, output)) { file ->
                    MaterialWriter.write(file, extension, "AUDIT_UNIQUE_REAL_CONTENT", emptyList(), emptyList())
                }
                koin.get<ChatService>().switchLearnerProfile(owner)
                item
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("材料").fetchSemanticsNodes().isNotEmpty() }
            block(store, item)
        } finally { runBlocking { restoreMemoryProfileFixture(original, settings) } }
    }
    @Test fun parsedMaterialRemainsVisibleWhenFiltered() = fixture { store, item ->
        runBlocking { store.readContent(item.profileId, item.id) }
        compose.onAllNodesWithText("材料").onLast().performClick()
        compose.onNodeWithText(item.name).performScrollTo().assertIsDisplayed()
        capture("filter-before")
        compose.onNode(hasText("已解析") and hasClickAction()).performClick()
        capture("filter-after")
        compose.onNodeWithText(item.name).assertExists()
    }
    @Test fun unrelatedTextDoesNotInventRecognizedFormula() = fixture { _, item ->
        compose.onAllNodesWithText("材料").onLast().performClick()
        compose.onNodeWithText(item.name).performScrollTo()
        compose.onNodeWithText("核对符号").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("材料提取片段（请对照原文件）：").fetchSemanticsNodes().isNotEmpty() }
        capture("invented-symbol")
        compose.onNodeWithText("f'(x) = 2\\sin(x)\\cos(x) ?").assertDoesNotExist()
    }
    @Test fun manualSymbolCorrectionPersistsAndRejectsChangedSource() = fixture { store, item ->
        compose.onAllNodesWithText("材料").onLast().performClick()
        compose.onNodeWithText(item.name).performScrollTo()
        compose.onNodeWithText("核对符号").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("手动输入正确符号或公式 (LaTeX)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("确认提交").assertIsNotEnabled()
        compose.onNodeWithText("手动输入正确符号或公式 (LaTeX)").performTextInput("x^2 + 1")
        compose.onNodeWithText("确认提交").performClick()
        compose.waitUntil(10000) { store.get(item.profileId, item.id).symbolCorrection == "x^2 + 1" }
        assertEquals("x^2 + 1", MaterialFiles(InstrumentationRegistry.getInstrumentation().targetContext).get(item.profileId, item.id).symbolCorrection)
        assertTrue(runCatching { store.confirmSymbol(item.profileId, item.id, "wrong-source", "x") }.isFailure)
        compose.waitUntil(10000) { compose.onAllNodesWithText("确认提交").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("核对符号").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("稍后处理").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("手动输入正确符号或公式 (LaTeX)").assertTextContains("x^2 + 1")
        compose.onNodeWithText("稍后处理").performClick()
        compose.waitUntil(10000) { store.get(item.profileId, item.id).symbolCheckPending }
        assertTrue(MaterialFiles(InstrumentationRegistry.getInstrumentation().targetContext).get(item.profileId, item.id).symbolCheckPending)
    }
    @Test fun missingArtifactDoesNotShowUnrelatedWorkedExample() {
        navigate(Screen.StudyArtifactPreview(UUID.randomUUID().toString(), ""))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("状态：成果不可用").fetchSemanticsNodes().isNotEmpty() }
        capture("missing-artifact")
        compose.onNodeWithText("函数定义域与解析推导", substring = true).assertDoesNotExist()
        compose.onNodeWithText("成果不存在或不属于当前学习者").assertExists()
        compose.onNodeWithText("打开").assertIsNotEnabled()
        compose.onNodeWithText("另存为").assertIsNotEnabled()
    }
    @Test fun docxPreviewReadsDocumentInsteadOfZipBytes() = fixture(true, "docx") { _, item ->
        navigate(Screen.StudyArtifactPreview(item.id, ""))
        compose.waitForIdle()
        // Allow asynchronous disk loading before checking the actual rendered content.
        Thread.sleep(1500)
        compose.waitForIdle()
        capture("docx-preview")
        compose.onNodeWithText("AUDIT_UNIQUE_REAL_CONTENT", substring = true).assertExists()
    }
    @Test fun currentMaterialTextPreviewAndPaginationWork() = fixture { _, item ->
        compose.onAllNodesWithText("材料").onLast().performClick()
        compose.onNodeWithText(item.name).performScrollTo()
        compose.onNodeWithText("查看内容").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("内容及限制").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("AUDIT_UNIQUE_REAL_CONTENT", substring = true).assertExists()
        capture("material-text-preview")
        compose.onNodeWithText("关闭").performClick()
    }
    @Test fun currentLongMaterialPreviewPagesReachTail() = fixture { store, item ->
        runBlocking { store.runTask(item) { file -> file.writeText("长内容。".repeat(2000) + "\nTAIL_AUDIT"); "长内容测试" } }
        compose.onAllNodesWithText("材料").onLast().performClick()
        compose.onNodeWithText(item.name).performScrollTo()
        compose.onNodeWithText("查看内容").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("下一页").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下一页").performClick()
        compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("TAIL_AUDIT"))
        compose.onNodeWithText("TAIL_AUDIT").performScrollTo().assertIsDisplayed()
        capture("long-material-tail")
        compose.onNodeWithText("上一页").performClick()
        compose.onNodeWithText("TAIL_AUDIT").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
    }
    @Test fun fourEntrancesAndCurrentTeachingSelectorWork() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("引导").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("引导").performClick()
        compose.onNodeWithText("○ 完整讲解").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("完整讲解").fetchSemanticsNodes().isNotEmpty() }
        capture("teaching-mode")
        compose.onNodeWithText("完整讲解").performClick()
        compose.onNodeWithText("○ 引导").performClick()
        compose.onAllNodesWithText("材料").onLast().performClick()
        compose.onNodeWithText("文件与讲义材料").assertIsDisplayed()
        compose.onAllNodesWithText("记忆").onLast().performClick()
        compose.onNodeWithText("的学习记忆", substring = true).assertExists()
        compose.onAllNodesWithText("成果").onLast().performClick()
        compose.onNodeWithText("真实文件成果").assertIsDisplayed()
        compose.onAllNodesWithText("聊天").onLast().performClick()
        compose.onNodeWithText("引导").assertIsDisplayed()
    }
    @Test fun corruptedArtifactOpenReportsFailure() = fixture(true) { store, item ->
        store.file(item).appendText("tampered")
        navigate(Screen.StudyArtifactPreview(item.id, ""))
        compose.onNodeWithText("打开").performScrollTo().performClick()
        capture("corrupt-open")
        compose.onNodeWithText("已请求系统打开外部查看器").assertDoesNotExist()
        compose.onNodeWithText("打开失败：", substring = true).assertExists()
    }
    @Test fun artifactPreviewClearsContentAfterLearnerSwitch() = fixture(true) { _, item ->
        navigate(Screen.StudyArtifactPreview(item.id, ""))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("AUDIT_UNIQUE_REAL_CONTENT", substring=true).fetchSemanticsNodes().isNotEmpty() }
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val other = runBlocking { settings.createLearnerProfile("QA预览另一档案") }
        runBlocking {
            settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == other } }
            koin.get<ChatService>().switchLearnerProfile(other)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("成果预览").fetchSemanticsNodes().isNotEmpty() }
        capture("artifact-owner-switch")
        compose.onNodeWithText("AUDIT_UNIQUE_REAL_CONTENT", substring=true).assertDoesNotExist()
        compose.onNodeWithText("打开").assertIsNotEnabled()
    }
    @Test fun currentMemorySourceEditDeleteAndIsolationWork() {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val memories = koin.get<LearningMemoryStore>()
        val repository = koin.get<ConversationRepository>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val owner = runBlocking { settings.createLearnerProfile("QA审查记忆") }
        val conversation = Conversation(id = Uuid.random(), assistantId = original.assistantId, profileId = owner,
            title = "QA审查来源", messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("请记住我喜欢先看反例")))))
        try {
            val memoryId = runBlocking {
                settings.settingsFlow.first { it.learnerProfiles.any { profile -> profile.id == owner } }
                repository.insertConversation(conversation)
                val id = memories.saveExplicit(owner, conversation.id.toString(), conversation.currentMessages.first().id.toString(),
                    conversation.currentMessages.first().toText(), MemoryType.PREFERENCE, "教学", "顺序", "喜欢先看反例")
                koin.get<ChatService>().switchLearnerProfile(owner)
                id
            }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("记忆").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("记忆").onLast().performClick()
            compose.onNodeWithText("喜欢先看反例").performScrollTo().performClick()
            compose.onNodeWithText("回看会话").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("请记住我喜欢先看反例", substring = true).fetchSemanticsNodes().isNotEmpty() }
            navigate(Screen.StudyMemoryDetail(memoryId, conversation.id.toString()))
            compose.onNodeWithText("更正记忆").performScrollTo().performClick()
            compose.onNodeWithText("更正后的事实描述").performTextReplacement("喜欢先看定义")
            compose.onNodeWithText("保存更正").performClick()
            compose.waitUntil(10_000) { runBlocking { memories.read(owner).items.single { it.id == memoryId }.content == "喜欢先看定义" } }
            capture("memory-edited")
            compose.onNodeWithText("删除记忆").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                try { compose.onNodeWithText("取消").assertIsEnabled(); true } catch (_: AssertionError) { false }
            }
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("确认删除该记忆？").fetchSemanticsNodes().isEmpty() }
            assertEquals("ACTIVE", runBlocking { memories.read(owner).items.single { it.id == memoryId }.status })
            compose.onNodeWithText("删除记忆").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                try { compose.onNodeWithText("确认删除").assertIsEnabled(); true } catch (_: AssertionError) { false }
            }
            compose.onNodeWithText("确认删除").performClick()
            compose.waitUntil(10_000) { runBlocking { memories.read(owner).items.none { it.id == memoryId } } }
            val after = runBlocking { memories.read(owner) }
            assertTrue(after.suppressedFacts.isNotEmpty())
            assertTrue(runBlocking { memories.read(original.activeProfileId) }.items.none { it.id == memoryId })
        } finally {
            runBlocking {
                repository.getConversationById(conversation.id, owner)?.let { repository.deleteConversation(it) }
                restoreMemoryProfileFixture(original, settings)
            }
        }
    }
}
