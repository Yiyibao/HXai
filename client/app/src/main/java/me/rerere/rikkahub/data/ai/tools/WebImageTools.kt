package me.rerere.rikkahub.data.ai.tools

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.search.SearchImage
import me.rerere.search.WebImageService

internal val webImageService by lazy {
    WebImageService(decode = { bytes ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2048) inSampleSize *= 2
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let {
                it.recycle()
                bounds.outWidth to bounds.outHeight
            }
        }
    })
}

internal suspend fun verifyEnhancedImages(result: EnhancedSearchResult, service: WebImageService): EnhancedSearchResult {
    val verified = service.verify(result.images.map {
        SearchImage(it.url, it.sourceUrl, it.sourceTitle, it.author, it.publishedDate, it.alt)
    })
    val byUrl = verified.associateBy { it.image.url }
    return result.copy(
        images = result.images.mapNotNull { image -> byUrl[image.url]?.let {
            image.copy(width = it.width, height = it.height, contentType = it.contentType, verifiedAt = it.verifiedAt)
        } },
        imageStatus = if (verified.isEmpty()) "unavailable" else "verified",
        imageWarning = listOfNotNull(result.imageWarning,
            "部分图片未通过下载和解码验证，已过滤。".takeIf { verified.size < result.images.size })
            .takeIf { it.isNotEmpty() }?.joinToString(" "),
    )
}

fun createWebImageTools(service: WebImageService = webImageService): Set<Tool> = setOf(
    webImageRetrievalTool("search_images", "query", "检索图片并下载、解码验证。返回可加载的图片直链、来源网页、尺寸和验证时间。按相关性选图后用 display_image 或 Markdown 嵌入聊天，并引用 sourceUrl。不要编造来源；验证不代表内容相关或版权授权。", service),
    webImageRetrievalTool("extract_web_images", "url", "从指定网页提取图片（包含懒加载和相对地址），下载、解码验证后返回图片直链及该网页来源。用 display_image 或 Markdown 嵌入聊天，并引用 sourceUrl。", service),
)

private fun webImageRetrievalTool(name: String, field: String, description: String, service: WebImageService) = Tool(
    name = name,
    description = description,
    parameters = {
        InputSchema.Obj(buildJsonObject {
            put(field, buildJsonObject { put("type", "string"); put("maxLength", if (field == "query") 1000 else 16000) })
        }, listOf(field))
    },
    execute = { params ->
        try {
            val value = params.jsonObject.getValue(field).jsonPrimitive.also { require(it.isString) }.content
            val candidates = if (field == "query") service.search(value) else service.extract(value)
            val raw = me.rerere.search.SearchResult(items = emptyList(), imageDetails = candidates)
            val result = verifyEnhancedImages(enhanceSearchResult(raw, buildJsonObject { put("query", value) }), service)
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToJsonElement(result).toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            listOf(UIMessagePart.Text("图片获取失败：未获得可验证的图片，请更换关键词或来源网页。"))
        }
    },
)
