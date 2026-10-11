package me.rerere.rikkahub.feature.material

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/** Restrictions come from human messages, never from attached file contents. */
object DocumentDispatchPolicy {
    fun allowsRemote(messages: List<UIMessage>): Boolean {
        val users = messages.filter { it.role == MessageRole.USER }.map { it.toText().trim() }
        if (users.lastOrNull().isNullOrBlank()) return false
        for (text in users.asReversed()) {
            if (Regex("仅本地|只在本地|不要上传|不上传|禁止上传|不发.{0,8}服务器|local[ -]?only|do not upload|don't upload", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
            if (Regex("允许上传|可以上传|使用远端|allow upload", RegexOption.IGNORE_CASE).containsMatchIn(text)) return true
        }
        return true
    }
}
