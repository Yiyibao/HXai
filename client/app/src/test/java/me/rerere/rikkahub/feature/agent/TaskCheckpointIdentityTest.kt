package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test

class TaskCheckpointIdentityTest {
    private fun task(conversation: Conversation, request: String) = AgentTask("task", conversation.profileId,
        conversation.id.toString(), request, "synthetic", "provider", "model", "HIGH", "version", "INTERRUPTED", 1, 0)
    private fun input() = Conversation.ofId(kotlin.uuid.Uuid.random()).copy(profileId = "owner", messageNodes = listOf(
        MessageNode(messages = listOf(UIMessage.user("same content"), UIMessage.user("same content")))))
    private fun output(input: Conversation, marker: String?) = input.copy(messageNodes = input.messageNodes +
        MessageNode(messages = listOf(UIMessage.assistant("synthetic checkpoint").copy(generationTaskId = marker))))

    @Test fun boundInputRoundTripUsesIdentityAndRejectsSameTextOtherBranch() {
        val source = input(); val request = TaskCheckpointIdentity.newRequest(source.messageNodes)
        val accepted = task(source, request); val checkpoint = output(source, accepted.id)
        TaskCheckpointIdentity.requireCheckpoint(accepted, checkpoint)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val reopened = json.decodeFromString<Conversation>(json.encodeToString(checkpoint))
        TaskCheckpointIdentity.requireCheckpoint(accepted, reopened)
        val changed = reopened.copy(messageNodes = reopened.messageNodes.mapIndexed { index, node ->
            if (index == 0) node.copy(selectIndex = 1) else node })
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(accepted, changed) }.isFailure)
        assertEquals("HIGH", accepted.reasoning)
    }
    @Test fun wrongMarkerOwnerConversationAndMalformedRequestReject() {
        val source = input(); val accepted = task(source, TaskCheckpointIdentity.newRequest(source.messageNodes))
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(accepted, output(source, "foreign")) }.isFailure)
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(accepted, output(source, null)) }.isFailure)
        val checkpoint = output(source, accepted.id)
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(accepted.copy(owner = "foreign"), checkpoint) }.isFailure)
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(accepted.copy(conversationId = "foreign"), checkpoint) }.isFailure)
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(accepted.copy(requestId = "regen1:malformed"), checkpoint) }.isFailure)
    }
    @Test fun legacyDirectInputRemainsValidAndAmbiguousLegacyRandomIdCannotBeInferred() {
        val source = input(); val direct = task(source, source.currentMessages.last().id.toString())
        TaskCheckpointIdentity.requireCheckpoint(direct, output(source, null))
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(task(source, kotlin.uuid.Uuid.random().toString()), output(source, null)) }.isFailure)
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(direct, output(source, "another-task")) }.isFailure)
    }
    @Test fun transformedContentsKeepStableIdentityButLaterUserChangesGoal() {
        val source = input(); val accepted = task(source, TaskCheckpointIdentity.newRequest(source.messageNodes))
        val transformed = source.copy(messageNodes = source.messageNodes.map { n -> n.copy(messages = n.messages.map {
            it.copy(parts = UIMessage.user("trusted preprocessed contents").parts)
        }) })
        TaskCheckpointIdentity.requireCheckpoint(accepted, output(transformed, accepted.id))
        val later = output(source, accepted.id).copy(messageNodes = output(source, accepted.id).messageNodes +
            MessageNode(messages = listOf(UIMessage.user("same content"))))
        assertTrue(runCatching { TaskCheckpointIdentity.requireCheckpoint(accepted, later) }.isFailure)
        val second = TaskCheckpointIdentity.newRequest(source.messageNodes)
        assertNotEquals(accepted.requestId, second)
    }
}
