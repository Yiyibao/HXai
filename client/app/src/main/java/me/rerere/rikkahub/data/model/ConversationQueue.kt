package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

@Serializable
data class PendingInput(
    val id: Uuid,
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
)

@Serializable
data class ConversationQueue(
    val messages: List<PendingInput> = emptyList(),
    val paused: Boolean = false,
) {
    fun recovered() = if (messages.isNotEmpty()) copy(paused = true) else this
}
