package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.feature.studychat.OneShotFeedback
import me.rerere.rikkahub.feature.studychat.OneShotFeedbackEvent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run seed and verify in separate instrumentation processes, with force-stop between them. */
@RunWith(AndroidJUnit4::class)
class FrontendFeedbackProcessTest {
    @Test fun durableFeedbackDoesNotReplayAfterProcessRestart() {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("feedbackPhase")
        assumeTrue(phase in setOf("seed", "verify"))
        val fixture = requireNotNull(arguments.getString("feedbackFixture"))
        require(Regex("[a-f0-9-]{36}").matches(fixture))
        val event = OneShotFeedbackEvent("qa-$fixture", eventKey = "memory-$fixture", version = 1,
            eventType = OneShotFeedbackEvent.EventType.MEMORY_SAVED)
        if (phase == "seed") assertTrue(OneShotFeedback.tryConsume(event, event.profileId))
        else {
            assertTrue(OneShotFeedback.isConsumed(event.eventKey, event.profileId, event.version))
            assertFalse(OneShotFeedback.tryConsume(event, event.profileId))
        }
    }
}
