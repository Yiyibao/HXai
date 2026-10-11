package me.rerere.rikkahub.feature.interactive

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolExecutionState
import me.rerere.rikkahub.data.model.Conversation

/** Appends a new validated version; never mutates or erases the source artifact. */
fun surfaceRevisionTool(resolve: suspend () -> Conversation) = Tool(
    name = "revise_interactive_surface",
    description = "根据用户修改要求生成已有交互成果的新版本，旧版保留。message_id/tool_call_id/expected_revision须来自用户提供的当前成果引用。definition为完整新版JSON字符串，与render_interactive_ui相同。不得猜测原始版本。",
    systemPrompt = { _, _ -> "用户要求修改已有交互成果并提供成果引用时，必须调用 revise_interactive_surface，读取引用对应的原定义并按要求生成完整新版；不能只在正文回复 JSON、工具参数或代码围栏。成功后新版由应用渲染，简短说明改变了什么和旧版保留。工具失败要说明真实原因，不能声称已修改。引用缺失时请用户从目标成果的修改入口发起，不猜测引用。" },
    parameters = { InputSchema.Obj(buildJsonObject {
        listOf("message_id", "tool_call_id", "expected_revision", "definition").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
    }, listOf("message_id", "tool_call_id", "expected_revision", "definition")) },
    execute = { args ->
        try {
            val input = args.jsonObject
            val conversation = resolve()
            val message = conversation.currentMessages.single { it.id.toString() == input.getValue("message_id").jsonPrimitive.content }
            val tool = message.getTools().single { it.toolCallId == input.getValue("tool_call_id").jsonPrimitive.content && InteractiveSurface.isSurfaceTool(it.toolName) && it.executionState == ToolExecutionState.COMPLETED }
            InteractiveSurface.clientOutput(tool)
            require(InteractiveSurface.hash(InteractiveSurface.definition(tool)) == input.getValue("expected_revision").jsonPrimitive.content) { "原成果版本已改变" }
            val raw = input.getValue("definition").jsonPrimitive.content
            val definition = InteractiveSurface.validate(raw)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("kind", "interactive_surface"); put("revision", InteractiveSurface.hash(raw)); put("definition", definition)
                put("parent", buildJsonObject { put("messageId", message.id.toString()); put("toolCallId", tool.toolCallId); put("revision", input.getValue("expected_revision")) })
            }.toString()))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { listOf(UIMessagePart.Text("""{"status":"FAILED","error":"原成果引用、版本或新定义无效；旧成果保留，请核对后再修改。"}""")) }
    },
)
