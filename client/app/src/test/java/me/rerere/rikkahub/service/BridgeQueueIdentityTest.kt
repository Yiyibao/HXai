package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class BridgeQueueIdentityTest {
    @Test fun suppliedIdentitySurvivesQueuePersistenceAndRecovery() {
        val id = Uuid.random()
        val queue = MessageQueue()
        queue.enqueue(listOf(UIMessagePart.Text("hello")), id = id)
        val restored = MessageQueue().apply { restore(queue.persisted().recovered()) }
        assertTrue(restored.state.value.paused)
        restored.resume()
        assertEquals(id, restored.takeNext()?.id)
    }
}
