package me.rerere.rikkahub.data.ai.tools

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.feature.agent.AgentExecution
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
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

internal suspend fun verifyEnhancedImages(result: EnhancedSearchResult, service: WebImageService, materials: MaterialFiles, profileId: String): EnhancedSearchResult {
    val verified = service.verify(result.images.map {
        SearchImage(it.url, it.sourceUrl, it.sourceTitle, it.author, it.publishedDate, it.alt)
    })
    val byUrl = verified.associateBy { it.image.url }
    val images = result.images.mapNotNull { image -> byUrl[image.url]?.let {
        val asset = persistWebImage(it, materials, profileId)
        image.copy(url = materials.verifiedFile(profileId, asset.id).toUri().toString(), remoteUrl = image.url,
            materialId = asset.id, sha256 = asset.sha256,
            width = it.width, height = it.height, contentType = it.contentType, verifiedAt = it.verifiedAt)
    } }
    return result.copy(
        images = images,
        imageStatus = if (verified.isEmpty()) "unavailable" else "verified",
        imageWarning = listOfNotNull(result.imageWarning,
            "部分图片未通过下载和解码验证，已过滤。".takeIf { verified.size < result.images.size })
            .takeIf { it.isNotEmpty() }?.joinToString(" "),
    )
}

fun createWebImageTools(materials: MaterialFiles, profileId: String, service: WebImageService = webImageService): Set<Tool> = setOf(
    webImageRetrievalTool("search_images", "query", "检索公网图片，下载、解码后保存到当前档案。返回已校验的本地图片 url、materialId、来源、尺寸和时间。用 display_image 或 Markdown 展示本地 url，引用 sourceUrl；校验不代表内容相关或版权授权。", service, materials, profileId),
    webImageRetrievalTool("extract_web_images", "url", "从公网网页提取图片（包含懒加载和相对地址），将通过下载和解码校验的原始字节保存到当前档案。用 display_image 或 Markdown 展示返回的本地 url，并引用 sourceUrl。", service, materials, profileId),
)

private fun webImageRetrievalTool(name: String, field: String, description: String, service: WebImageService, materials: MaterialFiles, profileId: String) = Tool(
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
            val result = verifyEnhancedImages(enhanceSearchResult(raw, buildJsonObject { put("query", value) }), service, materials, profileId)
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToJsonElement(result).toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            listOf(UIMessagePart.Text("图片获取失败：未获得可验证的图片，请更换关键词或来源网页。"))
        }
    },
)

internal suspend fun persistWebImage(image: me.rerere.search.VerifiedWebImage, materials: MaterialFiles, profileId: String, checkAllowed: () -> Unit = {}): MaterialFile {
    val execution = currentCoroutineContext()[AgentExecution]
    fun checkOwner() {
        checkAllowed()
        execution?.check()
        check(execution == null || execution.lease.task.owner == profileId) { "图片不属于当前任务档案" }
    }
    checkOwner()
    val extension = when (image.contentType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> error("不支持的图片类型")
    }
    val item = MaterialFile(java.util.UUID.randomUUID().toString(), profileId, "网络图片", extension,
        requestKey = image.image.url)
    val saved = materials.runTask(item) { target ->
        checkOwner()
        target.outputStream().use { it.write(image.bytes) }
        checkOwner()
        "已下载并解码；来源地址保留在 requestKey"
    }
    checkOwner()
    materials.verifiedFile(profileId, saved.id)
    return saved
}
