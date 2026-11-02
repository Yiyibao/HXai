package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

/** Shared native/hosted instruction prefix. Export never evaluates model instructions as authority. */
internal fun buildGenerationPrefix(
    assistant: me.rerere.rikkahub.data.model.Assistant,
    studyMode: me.rerere.rikkahub.feature.studychat.StudyMode,
    conversationSystemPrompt: String?,
    memories: List<AssistantMemory>,
    learningMemoryContext: String,
    model: me.rerere.ai.provider.Model,
    tools: List<me.rerere.ai.core.Tool>,
): String = buildString {
    val effectiveSystemPrompt =
        if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
            conversationSystemPrompt
        } else {
            me.rerere.rikkahub.feature.studychat.StudyTutorDefaults.effectivePrompt(assistant.systemPrompt, studyMode)
        }
    if (effectiveSystemPrompt.isNotBlank()) append(effectiveSystemPrompt)
    appendLine()
    if (studyMode != me.rerere.rikkahub.feature.studychat.StudyMode.CHAT) {
        appendLine(studyMode.prompt)
        appendLine(me.rerere.rikkahub.feature.studychat.StudyLearningLoop.prompt)
    }
    if (assistant.enableMemory) {
        appendLine()
        append(buildMemoryPrompt(memories))
    }
    if (learningMemoryContext.isNotEmpty()) { appendLine(); appendLine(learningMemoryContext) }
    appendLine("长期记忆保存状态仅以应用记忆页面为准。没有实际保存工具成功结果时，不得声称已记住或已保存。")
    appendLine(me.rerere.rikkahub.feature.material.MaterialDispatchGuidance.prompt(
        if (me.rerere.ai.provider.ModelAbility.TOOL in model.abilities) tools.map { it.name }.toSet() else emptySet(),
        me.rerere.ai.provider.Modality.IMAGE in model.inputModalities))
}
