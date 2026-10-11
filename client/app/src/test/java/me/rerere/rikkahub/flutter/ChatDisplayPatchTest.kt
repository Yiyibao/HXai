package me.rerere.rikkahub.flutter

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatDisplayPatchTest {
    @Test fun cumulativeTextAndTerminalTransition() {
        val message = UIMessage.assistant("a").copy(generationState = "RUNNING")
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(message))))
        val after = before.copy(messageNodes = listOf(before.messageNodes[0].copy(messages = listOf(message.copy(parts = listOf(UIMessagePart.Text("abc")))))))
        assertEquals("abc", ((ChatDisplayPatch.between(before, after)!!.single()["parts"] as List<*>).single() as Map<*, *>)["text"])
        val done = after.copy(messageNodes = listOf(after.messageNodes[0].copy(messages = listOf(after.currentMessages[0].copy(generationState = "COMPLETED")))))
        assertNull(ChatDisplayPatch.between(after, done))
        assertTrue(ChatDisplayPatch.between(after, after)!!.isEmpty())
    }
    @Test fun branchAndIdentityChangesRequireSnapshot() {
        val before = Conversation(assistantId = Uuid.random(), messageNodes = listOf(MessageNode(messages = listOf(UIMessage.assistant("a")))))
        assertNull(ChatDisplayPatch.between(before, before.copy(messageNodes = emptyList())))
        assertNull(ChatDisplayPatch.between(before, before.copy(title = "changed")))
    }
}
