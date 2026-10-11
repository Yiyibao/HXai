package me.rerere.rikkahub.flutter

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation

/** Cumulative display replacements only. Business transitions always request a snapshot. */
object ChatDisplayPatch {
    fun between(before: Conversation?, after: Conversation): List<Map<String, Any?>>? {
        if (before == null || before.id != after.id || before.title != after.title ||
            before.lifecycleRevision != after.lifecycleRevision || before.studyMode != after.studyMode ||
            before.chatSuggestions != after.chatSuggestions || before.queue != after.queue ||
            before.messageNodes.size != after.messageNodes.size) return null
        val changes = mutableListOf<Map<String, Any?>>()
        for ((index, node) in after.messageNodes.withIndex()) {
            val old = before.messageNodes[index]
            if (old == node) continue
            if (old.id != node.id || old.selectIndex != node.selectIndex || old.messages.size != node.messages.size) return null
            for ((branch, message) in node.messages.withIndex()) {
                val previous = old.messages[branch]
                if (previous == message) continue
                if (branch != node.selectIndex || previous.id != message.id || previous.generationState != message.generationState ||
                    previous.parts.size != message.parts.size) return null
                val parts = mutableListOf<Map<String, Any?>>()
                for ((partIndex, part) in message.parts.withIndex()) {
                    val prior = previous.parts[partIndex]
                    if (prior == part) continue
                    val value = when {
                        part is UIMessagePart.Text && prior is UIMessagePart.Text && part.copy(text = prior.text) == prior && part.text.startsWith(prior.text) -> mapOf("text" to part.text)
                        part is UIMessagePart.Reasoning && prior is UIMessagePart.Reasoning && part.copy(reasoning = prior.reasoning) == prior && part.reasoning.startsWith(prior.reasoning) -> mapOf("reasoning" to part.reasoning)
                        // Tool input previews can stream; execution/approval/output cannot be coalesced.
                        part is UIMessagePart.Tool && prior is UIMessagePart.Tool && part.copy(input = prior.input) == prior && part.input.startsWith(prior.input) -> mapOf("toolInput" to part.input)
                        else -> return null
                    }
                    parts += mapOf("index" to partIndex) + value
                }
                changes += mapOf("nodeId" to node.id.toString(), "messageId" to message.id.toString(), "parts" to parts)
            }
        }
        return changes
    }
}
