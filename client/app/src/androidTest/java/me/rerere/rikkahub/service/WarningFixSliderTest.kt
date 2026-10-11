package me.rerere.rikkahub.service

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.SemanticsProperties
import me.rerere.rikkahub.ui.components.ui.ControlledSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WarningFixSliderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun externalValuesRangeChangesGesturesAndDisabledStateRemainControlled() {
        val value = mutableFloatStateOf(0.2f)
        val range = mutableStateOf(0f..1f)
        val enabled = mutableStateOf(true)
        var finished = 0
        compose.setContent {
            MaterialTheme {
                ControlledSlider(
                    value = value.floatValue,
                    onValueChange = { value.floatValue = it },
                    valueRange = range.value,
                    enabled = enabled.value,
                    steps = 3,
                    onValueChangeFinished = { finished++ },
                    modifier = Modifier.testTag("controlled-slider"),
                )
            }
        }
        val slider = compose.onNodeWithTag("controlled-slider")
        compose.runOnIdle { value.floatValue = 0.75f }
        assertEquals(0.75f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        slider.performTouchInput { swipe(centerLeft, centerRight) }
        compose.runOnIdle {
            assertTrue(value.floatValue > 0.75f)
            assertTrue(finished > 0)
            range.value = 0f..10f
            value.floatValue = 5f
        }
        assertEquals(5f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        compose.runOnIdle { enabled.value = false }
        slider.assertIsNotEnabled()
        val finishedBefore = finished
        slider.performTouchInput { swipe(centerRight, centerLeft) }
        compose.runOnIdle {
            assertEquals(5f, value.floatValue, 0.001f)
            assertEquals(finishedBefore, finished)
        }
    }
}
