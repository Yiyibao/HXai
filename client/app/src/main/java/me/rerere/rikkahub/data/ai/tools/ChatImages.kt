package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.search.SearchImage
import java.io.File
import java.net.URI
import kotlin.uuid.Uuid

/** Native and Flutter render the same owner-verified file, never a second network response. */
class ChatImages(
    private val context: Context,
    private val materials: MaterialFiles,
    private val conversations: ConversationRepository,
) {
    private val lock = Mutex()

    suspend fun resolve(owner: String, source: String, conversationId: String? = null,
                        checkAllowed: () -> Unit): File = withContext(Dispatchers.IO) {
        lock.withLock {
            checkAllowed()
            val uri = URI(source)
            val file = if (uri.scheme == "file") {
                require(uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
                val candidate = source.toUri().toFile().canonicalFile
                val material = materials.list(owner).firstOrNull { materials.file(it).canonicalFile == candidate }
                if (material != null) materials.verifiedFile(owner, material.id) else {
                    // Legacy uploads require an explicit reference in this owner's current conversation.
                    require(candidate.parentFile == context.filesDir.resolve("upload").canonicalFile && conversationId != null) {
                        "图片不属于当前档案"
                    }
                    val conversation = requireNotNull(conversations.getConversationById(Uuid.parse(conversationId), owner))
                    require(conversation.currentMessages.any { source in it.parts.localFileUrls() }) { "图片未绑定到当前会话" }
                    val imported = materials.import(owner, candidate.toUri(), candidate.name)
                    materials.verifiedFile(owner, imported.id)
                }
            } else {
                require(me.rerere.search.validWebImageUrl(source) != null) { "图片地址无效或不支持" }
                val cached = materials.list(owner).firstOrNull { it.requestKey == source && it.state == "SUCCEEDED" }
                if (cached != null) materials.verifiedFile(owner, cached.id) else {
                    val image = webImageService.verify(listOf(SearchImage(source))).singleOrNull()
                        ?: error("图片下载或解码校验失败")
                    checkAllowed()
                    val saved = persistWebImage(image, materials, owner, checkAllowed)
                    materials.verifiedFile(owner, saved.id)
                }
            }
            checkAllowed()
            file
        }
    }
}
