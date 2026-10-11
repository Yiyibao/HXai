package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.InteractiveSurface
import me.rerere.rikkahub.service.ChatService
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * 智能交互界面管理器
 *
 * 负责持久化界面状态、处理用户交互动作、触发对话续行
 */
class InteractiveSurfaceManager(
    private val context: Context,
    private val json: Json,
    private val chatService: ChatService,
) {
    // 内存缓存: surfaceId -> 当前状态
    private val stateCache = mutableMapOf<String, Map<String, Any?>>()

    /**
     * 获取界面状态
     * @return JSON格式的状态字符串
     */
    suspend fun getSurfaceState(
        profileId: String,
        conversationId: String,
        messageId: String,
        surfaceId: String,
        revision: String
    ): String = withContext(Dispatchers.IO) {
        try {
            // 优先从缓存读取
            val cached = stateCache[surfaceId]
            if (cached != null) {
                return@withContext json.encodeToString(cached)
            }

            // TODO: 从数据库加载持久化的状态
            // 当前先返回空,让Flutter使用默认值
            return@withContext ""
        } catch (e: Exception) {
            android.util.Log.e("SurfaceManager", "Failed to load surface state", e)
            return@withContext ""
        }
    }

    /**
     * 处理界面动作
     * @param action 动作ID,null表示仅保存状态
     * @return 是否成功
     */
    suspend fun handleSurfaceAction(
        profileId: String,
        conversationId: String,
        messageId: String,
        surfaceId: String,
        revision: String,
        stateJson: String,
        action: String?,
        requestId: String,
        contextEpoch: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 解析状态
            val state = json.parseToJsonElement(stateJson).let { element ->
                if (element is kotlinx.serialization.json.JsonObject) {
                    element.toMap()
                } else {
                    emptyMap()
                }
            }

            // 更新缓存
            stateCache[surfaceId] = state

            // TODO: 持久化到数据库

            // 如果有action,触发对话续行
            if (action != null && action == "continue") {
                continueConversationWithState(
                    profileId = profileId,
                    conversationId = conversationId,
                    surfaceId = surfaceId,
                    state = state,
                    requestId = requestId
                )
            }

            return@withContext true
        } catch (e: Exception) {
            android.util.Log.e("SurfaceManager", "Failed to handle surface action", e)
            return@withContext false
        }
    }

    /**
     * 用户点击"继续分析"后,将当前状态作为新消息发送给模型
     */
    private suspend fun continueConversationWithState(
        profileId: String,
        conversationId: String,
        surfaceId: String,
        state: Map<String, Any?>,
        requestId: String
    ) {
        try {
            // 构建状态摘要消息
            val stateDescription = buildString {
                appendLine("【用户操作了交互界面】")
                appendLine("界面ID: $surfaceId")
                appendLine("当前参数状态:")
                state.forEach { (key, value) ->
                    appendLine("  $key = $value")
                }
                appendLine()
                appendLine("用户希望基于以上参数继续分析。")
            }

            // 发送新消息
            val inputId = Uuid.parse(java.util.UUID.nameUUIDFromBytes(
                "surface:$profileId:$conversationId:$surfaceId:$requestId".toByteArray()
            ).toString())
            chatService.sendMessageCommitted(
                Uuid.parse(conversationId),
                listOf(UIMessagePart.Text(stateDescription)),
                inputId,
            ) { current -> require(current.profileId == profileId) }


        } catch (e: Exception) {
            android.util.Log.e("SurfaceManager", "Failed to continue conversation", e)
        }
    }

    /**
     * 清理已关闭会话的缓存
     */
    fun clearCache(conversationId: String? = null) {
        if (conversationId != null) {
            stateCache.keys.removeAll { it.startsWith("surface_") }
        } else {
            stateCache.clear()
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.toMap(): Map<String, Any?> {
    return this.mapValues { (_, value) ->
        when (value) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                value.content.toDoubleOrNull() ?: value.content
            }
            else -> value.toString()
        }
    }
}
