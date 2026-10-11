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

@RunWith(AndroidJUnit4::class)
class LearningMemoryPageTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
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
    @Test fun viewSourceEditDeleteAndSwitchLearner() {
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
            compose.onNode(isToggleable()).performClick()
            compose.onNodeWithText("启用").performClick()
            runBlocking { kotlinx.coroutines.withTimeout(10000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner && p.memoryEnabled } } } }
            compose.onNode(isToggleable()).performClick()
            runBlocking { kotlinx.coroutines.withTimeout(10000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == owner && !p.memoryEnabled } } } }
            compose.onNodeWithText("喜欢先看反例").performScrollTo().performClick()
            compose.onNodeWithText("回看会话").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("请记住我喜欢先看反例", substring = true).fetchSemanticsNodes().isNotEmpty() }
            navigate(Screen.StudyMemoryDetail(memoryId, conversation.id.toString()))
            compose.onNodeWithText("更正记忆").performScrollTo().performClick()
            compose.onNodeWithText("更正后的事实描述").performTextReplacement("喜欢先看定义")
            compose.onNodeWithText("保存更正").performClick()
            compose.waitUntil(10_000) { runBlocking { memories.read(owner).items.single { it.id == memoryId }.content == "喜欢先看定义" } }
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
