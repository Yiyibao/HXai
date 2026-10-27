package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/** Trusted request identity, independent of message text or mutable settings. */
internal object TaskCheckpointIdentity {
    private const val PREFIX = "regen1:"
    private fun fingerprint(nodes: List<MessageNode>) = AgentTaskStore.hash(Json.encodeToString(
        nodes.map { "${it.id}:${it.currentMessage.id}:${it.currentMessage.role.name}" }))

    fun newRequest(input: List<MessageNode>): String {
        val source = requireNotNull(input.lastOrNull { it.currentMessage.role == MessageRole.USER })
        return "$PREFIX${source.currentMessage.id}:${input.size}:${fingerprint(input)}:${Uuid.random()}"
    }

    fun requireInput(conversation: Conversation, requestId: String) {
        require(requestId.startsWith(PREFIX))
        val fields = requestId.split(':')
        require(fields.size == 5 && fields[3].matches(Regex("[0-9a-f]{64}")))
        Uuid.parse(fields[1]); Uuid.parse(fields[4])
        val count = requireNotNull(fields[2].toIntOrNull())
        require(count in 1..conversation.messageNodes.size)
        val prefix = conversation.messageNodes.take(count)
        val source = requireNotNull(prefix.lastOrNull { it.currentMessage.role == MessageRole.USER })
        require(source.currentMessage.id.toString() == fields[1] && fingerprint(prefix) == fields[3]) {
            "Selected input branch no longer matches the accepted task"
        }
    }

    fun requireCheckpoint(task: AgentTask, conversation: Conversation) {
        require(task.owner == conversation.profileId && task.conversationId == conversation.id.toString())
        val last = requireNotNull(conversation.currentMessages.lastOrNull())
        val source = requireNotNull(conversation.currentMessages.lastOrNull { it.role == MessageRole.USER })
        if (task.requestId.startsWith(PREFIX)) {
            requireInput(conversation, task.requestId)
            require(task.requestId.split(':')[1] == source.id.toString() && last.generationTaskId == task.id) {
                "Checkpoint lacks the accepted regeneration task provenance"
            }
        } else {
            // Legacy direct-input tasks remain compatible. Random legacy IDs are
            // never inferred from identical text or a newer task's execution fence.
            require(source.id.toString() == task.requestId) { "Selected input no longer belongs to this task" }
            require(last.generationTaskId == null || last.generationTaskId == task.id)
        }
    }
}
