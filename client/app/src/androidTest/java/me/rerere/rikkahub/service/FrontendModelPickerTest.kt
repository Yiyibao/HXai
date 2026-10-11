package me.rerere.rikkahub.service

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.feature.studychat.ui.StudyModelPickerSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrontendModelPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun waitsForDurableSaveAndKeepsPickerOnFailure() {
        val previous = Model(modelId = "previous", displayName = "原模型")
        val next = Model(modelId = "next", displayName = "待选模型")
        val provider = ProviderSetting.OpenAI(name = "QA提供商", models = listOf(previous, next))
        val visible = mutableStateOf(true)
        val selected = mutableStateOf(previous.id)
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        compose.setContent {
            MaterialTheme {
                StudyModelPickerSheet(
                    visible = visible.value,
                    onDismissRequest = { visible.value = false },
                    providers = listOf(provider), currentModelId = selected.value,
                    onSelectModel = { model ->
                        attempts++
                        if (attempts == 1) {
                            gate.await()
                            throw java.io.IOException("QA write failure")
                        }
                        selected.value = model.id
                    },
                    onManageProviders = {}
                )
            }
        }
        compose.onNodeWithText("待选模型").performClick()
        compose.onNodeWithText("正在保存模型选择…").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(visible.value)
            assertEquals(previous.id, selected.value)
            assertEquals(1, attempts)
            gate.complete(Unit)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("模型选择保存失败，原选择已保留，请重试。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("模型选择保存失败，原选择已保留，请重试。").assertIsDisplayed()
        compose.onNodeWithText("待选模型").performClick()
        compose.waitUntil(10_000) { !visible.value }
        compose.runOnIdle { assertEquals(next.id, selected.value); assertEquals(2, attempts) }
    }
}
