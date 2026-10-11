package me.rerere.rikkahub.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.RouteActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()

    @Test fun fourEntrancesAndTeachingSelectorAreReachable() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("引导").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("引导").performClick()
        compose.onNodeWithText("教学方式").assertIsDisplayed()
        compose.onNodeWithText("○ 完整讲解").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("材料").performClick()
        compose.onNodeWithText("文件与讲义材料").assertIsDisplayed()
        compose.onAllNodesWithText("记忆").onLast().performClick()
        compose.onNodeWithText("的学习记忆", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("成果").onLast().performClick()
        compose.onNodeWithText("真实文件成果").assertIsDisplayed()
        compose.onNodeWithText("聊天").performClick()
        compose.onNodeWithText("引导").assertIsDisplayed()
    }
}
