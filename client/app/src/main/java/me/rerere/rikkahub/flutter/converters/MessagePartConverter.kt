package me.rerere.rikkahub.flutter.converters

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.flutter.generated.MessagePartDto

/**
 * UIMessagePart 转换器 - 支持智能交互界面和图片元数据
 */
fun UIMessagePart.toDto(): MessagePartDto {
    return when (this) {
        is UIMessagePart.Text -> MessagePartDto(
            type = "text",
            text = text
        )
        is UIMessagePart.Image -> MessagePartDto(
            type = "image",
            url = url
        )
        is UIMessagePart.Video -> MessagePartDto(
            type = "video",
            url = url
        )
        is UIMessagePart.Audio -> MessagePartDto(
            type = "audio",
            url = url
        )
        is UIMessagePart.Document -> MessagePartDto(
            type = "document",
            url = url,
            fileName = fileName,
            mime = mime
        )
        is UIMessagePart.Reasoning -> MessagePartDto(
            type = "reasoning",
            reasoning = reasoning
        )
        is UIMessagePart.Tool -> MessagePartDto(
            type = "tool",
            toolCallId = toolCallId,
            toolName = toolName,
            toolInput = input,
            toolOutput = output.joinToString("\n") { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    else -> part.toString()
                }
            },
            approvalState = approvalState.toString(),
            executionState = executionState.toString()
        )
        is UIMessagePart.ServerTool -> MessagePartDto(
            type = "server_tool",
            toolCallId = toolCallId,
            toolName = toolName,
            toolInput = input?.toString(),
            toolOutput = output?.toString()
        )
        is UIMessagePart.InteractiveSurfaceRef -> MessagePartDto(
            type = "interactive_surface",
            surfaceId = surfaceId,
            surfaceTitle = title,
            surfaceDefinition = definitionJson,
            surfaceRevision = 0 // 初始版本
        )
        else -> MessagePartDto(
            type = "unknown",
            text = this.toString()
        )
    }
}

/**
 * 将 JsonObject 转换为 Map<String, Any>
 */
private fun JsonObject.toMap(): Map<String, Any?> {
    return this.mapValues { (_, value) ->
        when {
            value is kotlinx.serialization.json.JsonPrimitive && value.isString -> value.content
            value is kotlinx.serialization.json.JsonPrimitive && !value.isString -> {
                value.content.toIntOrNull() ?: value.content.toDoubleOrNull() ?: value.content
            }
            else -> value.toString()
        }
    }
}
