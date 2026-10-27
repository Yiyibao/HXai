package me.rerere.rikkahub.data.ai

import java.security.MessageDigest
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Pro TOOL-06 projection. Durable results and UI JSON are never modified. */
object ToolTextProjection {
    const val MAX_CHARS = 32 * 1024
    private const val PREVIEW_CHARS = 4 * 1024
    fun project(parts: List<String>): List<String> {
        val total = parts.sumOf { it.length.toLong() }
        if (total <= MAX_CHARS && parts.size <= 128) return parts
        val hash = MessageDigest.getInstance("SHA-256")
        val preview = StringBuilder()
        parts.forEach { text ->
            hash.update(text.length.toString().toByteArray(Charsets.US_ASCII))
            hash.update(0.toByte())
            hash.update(text.toByteArray(Charsets.UTF_8))
            if (preview.length < PREVIEW_CHARS) preview.append(text.take(PREVIEW_CHARS - preview.length))
        }
        if (preview.isNotEmpty() && Character.isHighSurrogate(preview.last())) preview.setLength(preview.length - 1)
        val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return listOf("[TOOL_OUTPUT_PREVIEW: chars=$total; parts=${parts.size}; sha256=$digest]\n" +
            "This is a bounded preview, NOT complete JSON or a verified summary. Full output remains in local chat storage. " +
            "Do not invent a shell or file-retrieval tool. Re-read a narrower source with an actually available tool when necessary.\n" + preview)
    }

    fun projectOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val text = output.filterIsInstance<UIMessagePart.Text>().map { it.text }
        val projected = project(text)
        if (projected === text) return output
        var inserted = false
        return buildList {
            output.forEach { part ->
                if (part !is UIMessagePart.Text) add(part)
                else if (!inserted) { add(UIMessagePart.Text(projected.single())); inserted=true }
            }
        }
    }

    fun messages(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        if (message.parts.none { it is UIMessagePart.Tool }) message else message.copy(parts=message.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(output=projectOutput(part.output)) else part
        })
    }
}
