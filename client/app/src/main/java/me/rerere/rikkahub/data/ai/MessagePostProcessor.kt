package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 消息后处理器
 * 负责处理 AI 生成的消息，自动解析 Markdown 图片等内容
 */
object MessagePostProcessor {

    /**
     * 处理 AI 生成的消息
     * - 解析 Markdown 图片
     * - 提取搜索上下文
     * - 转换为多个 MessagePart
     */
    fun processMessage(message: UIMessage): UIMessage {
        // 只处理助手消息
        if (message.role != me.rerere.ai.core.MessageRole.ASSISTANT) {
            return message
        }

        // 查找搜索上下文
        val searchContext = extractSearchContext(message)

        // 处理每个文本部分
        val processedParts = message.parts.flatMap { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    // 解析文本中的 Markdown 图片
                    MarkdownContentParser.parseTextWithImages(
                        text = part.text,
                        searchContext = searchContext
                    )
                }
                else -> listOf(part)
            }
        }

        return message.copy(parts = processedParts)
    }

    /**
     * 从工具调用结果中提取搜索上下文
     */
    private fun extractSearchContext(message: UIMessage): SearchContext? {
        // 查找 search_web 工具调用
        val searchToolPart = message.parts.filterIsInstance<UIMessagePart.Tool>()
            .find { it.toolName == "search_web" }

        if (searchToolPart == null) return null

        // 解析工具输出
        return try {
            val output = searchToolPart.output.firstOrNull() as? UIMessagePart.Text
            if (output == null) return null

            val json = Json.parseToJsonElement(output.text).jsonObject
            parseSearchContext(json)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析搜索结果 JSON 为 SearchContext
     */
    private fun parseSearchContext(json: JsonObject): SearchContext? {
        return try {
            val query = json["query"]?.jsonPrimitive?.content ?: return null

            // 解析图片信息
            val images = json["images"]?.jsonArray?.mapNotNull { imageElement ->
                val imageObj = imageElement.jsonObject
                ImageInfo(
                    url = imageObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    sourceUrl = imageObj["sourceUrl"]?.jsonPrimitive?.content ?: "",
                    sourceTitle = imageObj["sourceTitle"]?.jsonPrimitive?.content ?: "",
                    author = imageObj["author"]?.jsonPrimitive?.content ?: "",
                    publishedDate = imageObj["publishedDate"]?.jsonPrimitive?.content ?: ""
                )
            } ?: emptyList()

            // 解析搜索结果项
            val items = json["items"]?.jsonArray?.mapNotNull { itemElement ->
                val itemObj = itemElement.jsonObject
                SearchResultItem(
                    id = itemObj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    title = itemObj["title"]?.jsonPrimitive?.content ?: "",
                    url = itemObj["url"]?.jsonPrimitive?.content ?: "",
                    publishedDate = itemObj["publishedDate"]?.jsonPrimitive?.content
                )
            } ?: emptyList()

            SearchContext(
                query = query,
                images = images,
                items = items
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查消息是否包含需要处理的 Markdown 图片
     */
    fun needsProcessing(message: UIMessage): Boolean {
        return message.role == me.rerere.ai.core.MessageRole.ASSISTANT &&
                message.parts.any { part ->
                    part is UIMessagePart.Text && part.text.contains("![")
                }
    }
}
