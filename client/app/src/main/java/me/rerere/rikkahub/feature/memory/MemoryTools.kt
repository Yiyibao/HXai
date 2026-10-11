package me.rerere.rikkahub.feature.memory

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun explicitMemoryTool(store: LearningMemoryStore, profileId: String, conversationId: String, messageId: String, original: String) = Tool(
    name = "save_explicit_memory",
    description = "保存本轮用户明确要求记住的学习事实。content必须逐字来自用户原消息，不得推断。只有工具返回成功才可说已保存。不得保存密钥。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        listOf("type", "topic_key", "fact_key", "content").forEach { name -> put(name, buildJsonObject {
            put("type", "string")
            if (name == "type") put("enum", JsonArray(MemoryType.entries.map { JsonPrimitive(it.name) }))
        }) }
    }, required = listOf("type", "topic_key", "fact_key", "content")) },
    execute = { input ->
        val data = input.jsonObject
        require(data.keys == setOf("type", "topic_key", "fact_key", "content"))
        val content = data.getValue("content").jsonPrimitive.content
        require(!MemoryValidation.sensitive(content))
        val id = store.saveExplicit(profileId, conversationId, messageId, original,
            MemoryType.valueOf(data.getValue("type").jsonPrimitive.content), data.getValue("topic_key").jsonPrimitive.content,
            data.getValue("fact_key").jsonPrimitive.content, content)
        listOf(UIMessagePart.Text(buildJsonObject { put("saved", true); put("memory_id", id) }.toString()))
    },
)
