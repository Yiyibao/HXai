package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Markdown 内容解析器
 * 用于从 AI 生成的文本中提取图片和其他媒体内容
 */
object MarkdownContentParser {

    // Markdown 图片语法: ![alt text](url)
    private val imageRegex = """!\[([^\]]*)\]\(([^)]+)\)""".toRegex()

    // Markdown 引用语法: [citation,domain](id)
    private val citationRegex = """\[citation,([^\]]+)\]\(([^)]+)\)""".toRegex()

    /**
     * 解析包含 Markdown 图片的文本，转换为多个 MessagePart
     *
     * @param text AI 生成的原始文本（可能包含 Markdown 图片）
     * @param searchContext 搜索上下文（包含图片来源信息）
     * @return 分离后的消息部分列表（文本 + 图片）
     */
    fun parseTextWithImages(
        text: String,
        searchContext: SearchContext? = null
    ): List<UIMessagePart> {
        if (!text.contains("![")) {
            // 没有图片，直接返回文本
            return listOf(UIMessagePart.Text(text))
        }

        val parts = mutableListOf<UIMessagePart>()
        val imageMatches = imageRegex.findAll(text).toList()

        if (imageMatches.isEmpty()) {
            return listOf(UIMessagePart.Text(text))
        }

        var lastIndex = 0
        var imageOrder = 0

        imageMatches.forEach { match ->
            // 添加图片之前的文本
            if (match.range.first > lastIndex) {
                val textBefore = text.substring(lastIndex, match.range.first).trim()
                if (textBefore.isNotEmpty()) {
                    parts.add(UIMessagePart.Text(textBefore))
                }
            }

            // 提取图片信息
            val altText = match.groupValues[1]
            val imageUrl = match.groupValues[2]

            // 构建图片元数据
            val metadata = buildImageMetadata(
                url = imageUrl,
                alt = altText,
                order = imageOrder++,
                searchContext = searchContext
            )

            // 添加图片部分
            parts.add(UIMessagePart.Image(
                url = imageUrl,
                metadata = metadata
            ))

            lastIndex = match.range.last + 1
        }

        // 添加剩余文本
        if (lastIndex < text.length) {
            val textAfter = text.substring(lastIndex).trim()
            if (textAfter.isNotEmpty()) {
                parts.add(UIMessagePart.Text(textAfter))
            }
        }

        return parts
    }

    /**
     * 构建图片元数据
     */
    private fun buildImageMetadata(
        url: String,
        alt: String,
        order: Int,
        searchContext: SearchContext?
    ): JsonObject {
        return buildJsonObject {
            put("caption", alt)
            put("order", order)

            if (searchContext != null) {
                // 从搜索上下文中查找图片来源
                val imageInfo = searchContext.findImageInfo(url)

                if (imageInfo != null) {
                    put("sourceUrl", imageInfo.sourceUrl)
                    put("sourceTitle", imageInfo.sourceTitle)
                    put("author", imageInfo.author)
                    put("sourceType", "web_search")
                } else {
                    // 默认标记为网络搜索来源
                    put("sourceType", "web_search")
                }
            } else {
                // 无搜索上下文，根据 URL 判断类型
                put("sourceType", inferSourceType(url))
            }
        }
    }

    /**
     * 从 URL 推断图片来源类型
     */
    private fun inferSourceType(url: String): String {
        return when {
            url.startsWith("file:") -> "user_upload"
            url.contains("generated") || url.contains("dalle") -> "ai_generated"
            else -> "web_search"
        }
    }

    /**
     * 提取文本中的引用信息
     */
    fun extractCitations(text: String): List<Citation> {
        return citationRegex.findAll(text).map { match ->
            Citation(
                domain = match.groupValues[1],
                id = match.groupValues[2]
            )
        }.toList()
    }

    /**
     * 移除文本中的 Markdown 图片标记（保留纯文本）
     */
    fun removeMarkdownImages(text: String): String {
        return text.replace(imageRegex, "")
    }
}

/**
 * 搜索上下文，包含图片来源信息
 */
data class SearchContext(
    val query: String,
    val images: List<ImageInfo>,
    val items: List<SearchResultItem>
) {
    /**
     * 根据图片 URL 查找来源信息
     */
    fun findImageInfo(url: String): ImageInfo? {
        return images.find { it.url == url }
    }
}

/**
 * 图片信息
 */
data class ImageInfo(
    val url: String,
    val sourceUrl: String,
    val sourceTitle: String,
    val author: String = "",
    val publishedDate: String = ""
)

/**
 * 搜索结果项
 */
data class SearchResultItem(
    val id: String,
    val title: String,
    val url: String,
    val publishedDate: String? = null
)

/**
 * 引用信息
 */
data class Citation(
    val domain: String,
    val id: String
)
