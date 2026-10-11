package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

fun displayImageTool(
    service: me.rerere.search.WebImageService = webImageService,
) = Tool(
    name = "display_image",
    description = "下载并解码验证已有图片，通过后嵌入当前聊天。url须为用户或检索工具提供的真实http/https图片直链。请把检索返回的sourceUrl和sourceTitle一并传入以展示来源；caption可写简短说明。检索用search_images，网页提图用extract_web_images。",
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
            val uri = java.net.URI(url)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null)
            val caption = args.jsonObject["caption"]?.jsonPrimitive?.also {
                require(it.isString && it.content.length <= 2000)
            }?.content
            val sourceUrl = args.jsonObject["sourceUrl"]?.jsonPrimitive?.also { require(it.isString) }?.content
            require(sourceUrl == null || me.rerere.search.validWebImageUrl(sourceUrl) != null)
            val sourceTitle = args.jsonObject["sourceTitle"]?.jsonPrimitive?.also {
                require(it.isString && it.content.length <= 300)
            }?.content
            val verified = service.verify(listOf(me.rerere.search.SearchImage(url))).singleOrNull()
            if (verified == null) {
                listOf(UIMessagePart.Text("图片加载验证失败：下载或解码未通过，请从 search_images 或 extract_web_images 选择其他图片。"))
            } else buildList {
                add(UIMessagePart.Image(url).apply {
                    metadata = buildJsonObject {
                        caption?.let { put("caption", it) }
                        sourceUrl?.let { put("sourceUrl", it) }
                        sourceTitle?.let { put("sourceTitle", it) }
                        put("width", verified.width)
                        put("height", verified.height)
                        put("verifiedAt", verified.verifiedAt)
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
