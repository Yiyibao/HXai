package me.rerere.rikkahub.data.ai.tools

import android.graphics.BitmapFactory
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun displayImageTool(
    materials: me.rerere.rikkahub.feature.material.MaterialFiles,
    profileId: String,
    service: me.rerere.search.WebImageService = webImageService,
) = Tool(
    name = "display_image",
    description = "展示当前档案已校验的本地图片，或将真实公网 http/https 图片下载、解码并保存后展示。优先使用检索返回的本地 url，避免再次联网。sourceUrl/sourceTitle 用于来源，caption 用于简短说明；不会访问私网或任意本地路径。",
    parameters = {
        InputSchema.Obj(buildJsonObject {
            put("url", buildJsonObject { put("type", "string"); put("maxLength", 16000) })
            put("caption", buildJsonObject { put("type", "string"); put("maxLength", 2000) })
            put("sourceUrl", buildJsonObject { put("type", "string"); put("maxLength", 16000) })
            put("sourceTitle", buildJsonObject { put("type", "string"); put("maxLength", 300) })
        }, listOf("url"))
    },
    execute = { args ->
        try {
            val url = args.jsonObject.getValue("url").jsonPrimitive.also { require(it.isString) }.content
            require(url.length in 1..16000)
            val caption = args.jsonObject["caption"]?.jsonPrimitive?.also {
                require(it.isString && it.content.length <= 2000)
            }?.content
            val sourceUrl = args.jsonObject["sourceUrl"]?.jsonPrimitive?.also { require(it.isString) }?.content
            require(sourceUrl == null || me.rerere.search.validWebImageUrl(sourceUrl) != null)
            val sourceTitle = args.jsonObject["sourceTitle"]?.jsonPrimitive?.also {
                require(it.isString && it.content.length <= 300)
            }?.content
            val uri = java.net.URI(url)
            val asset = if (uri.scheme == "file") {
                require(uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
                val file = url.toUri().toFile().canonicalFile
                val item = materials.get(profileId, file.nameWithoutExtension)
                require(item.extension in setOf("png", "jpg", "jpeg", "webp", "gif") &&
                    materials.verifiedFile(profileId, item.id).canonicalFile == file) { "图片不属于当前档案" }
                item
            } else {
                require(me.rerere.search.validWebImageUrl(url) != null)
                val verified = service.verify(listOf(me.rerere.search.SearchImage(url))).singleOrNull()
                    ?: error("图片下载或解码未通过")
                persistWebImage(verified, materials, profileId)
            }
            val file = materials.verifiedFile(profileId, asset.id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片解码失败" }
            buildList {
                add(UIMessagePart.Image(file.toUri().toString()).apply {
                    metadata = buildJsonObject {
                        caption?.let { put("caption", it) }
                        sourceUrl?.let { put("sourceUrl", it) }
                        sourceTitle?.let { put("sourceTitle", it) }
                        put("width", bounds.outWidth)
                        put("height", bounds.outHeight)
                        put("materialId", asset.id)
                        put("profileId", asset.profileId)
                        put("sha256", asset.sha256)
                        put("remoteUrl", asset.requestKey)
                        put("verifiedAt", java.time.Instant.now().toString())
                    }
                })
                sourceUrl?.let {
                    // A text part preserves a clickable source in native, Flutter and web clients.
                    val label = (sourceTitle ?: "图片来源").replace(Regex("[\\[\\]\\r\\n]"), " ")
                    val target = it.replace("<", "%3C").replace(">", "%3E")
                    add(UIMessagePart.Text("来源：[$label](<$target>)"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            listOf(UIMessagePart.Text("图片展示失败：请提供有效的http/https图片地址和简短说明。"))
        }
    },
)
