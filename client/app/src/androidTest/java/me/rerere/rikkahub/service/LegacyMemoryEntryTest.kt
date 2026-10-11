package me.rerere.rikkahub.service

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyMemoryEntryTest {
    @get:Rule val compose = createComposeRule()
    @Test fun oldEntryDoesNotLoadSharedMemoryOrInstantiateAssistant() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
                    AssistantMemoryPage("untrusted-old-link")
                }
            }
        }
        compose.onNodeWithText("学习记忆按当前学习者保存，不随助手共享。").assertIsDisplayed()
        compose.onNodeWithText("请回到聊天页，打开底部“记忆”，查看来源、编辑或删除记录。").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add").assertDoesNotExist()
    }
}
