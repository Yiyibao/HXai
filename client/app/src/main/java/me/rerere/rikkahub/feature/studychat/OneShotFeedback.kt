package me.rerere.rikkahub.feature.studychat

import android.content.Context
import java.security.MessageDigest

/**
 * 一次性事件反馈与去重管理
 * 遵守交接文档第4节：通用动画合同与状态映射
 *
 * 保证真实成功事件只消费一次，重组、旋转、页面重进与进程恢复不重复播放庆祝。
 * 零候选、冲突、草稿保存或未提交不庆祝。
 */
data class OneShotFeedbackEvent(
    val profileId: String,
    val taskId: String? = null,
    val conversationId: String? = null,
    val artifactId: String? = null,
    val version: Long = 0L,
    val eventKey: String,
    val eventType: EventType,
) {
    enum class EventType {
        MEMORY_SAVED,
        FILE_REGISTERED,
        SYMBOL_CHECKED,
    }
}

object OneShotFeedback {
    private var ledger = FeedbackLedger(MemoryFeedbackStorage())

    /** Install before workers or pages can publish feedback. No event content is persisted. */
    @Synchronized
    fun install(context: Context) {
        val preferences = context.getSharedPreferences("study_feedback_consumed", Context.MODE_PRIVATE)
        ledger = FeedbackLedger(object : FeedbackStorage {
            override fun contains(key: String) = preferences.getBoolean(key, false)
            override fun record(key: String) = preferences.edit().putBoolean(key, true).commit()
            override fun clear() { check(preferences.edit().clear().commit()) }
        })
    }

    /**
     * 尝试消费事件。若该事件尚未被消费且匹配当前活跃学习者档案，返回 true 并将其标记为已消费。
     */
    fun tryConsume(
        event: OneShotFeedbackEvent,
        activeProfileId: String,
    ): Boolean {
        return ledger.tryConsume(event, activeProfileId)
    }

    /**
     * 检查事件是否已经消费过
     */
    fun isConsumed(eventKey: String, profileId: String, version: Long = 0L): Boolean {
        return ledger.isConsumed(eventKey, profileId, version)
    }

    /**
     * 清理（通常用于测试）
     */
    fun clear() {
        ledger.clear()
    }
}

interface FeedbackStorage {
    fun contains(key: String): Boolean
    /** False means the durable write failed; feedback must remain silent. */
    fun record(key: String): Boolean
    fun clear()
}

private class MemoryFeedbackStorage : FeedbackStorage {
    private val keys = mutableSetOf<String>()
    override fun contains(key: String) = key in keys
    override fun record(key: String): Boolean { keys.add(key); return true }
    override fun clear() { keys.clear() }
}

class FeedbackLedger(private val storage: FeedbackStorage) {
    private fun key(eventKey: String, profileId: String, version: Long): String {
        // Length-prefix each string to avoid delimiter collisions.
        val identity = "${profileId.length}:$profileId:${eventKey.length}:$eventKey:$version"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun tryConsume(event: OneShotFeedbackEvent, activeProfileId: String): Boolean {
        if (event.profileId != activeProfileId) return false
        val key = key(event.eventKey, event.profileId, event.version)
        return !storage.contains(key) && storage.record(key)
    }

    @Synchronized
    fun isConsumed(eventKey: String, profileId: String, version: Long): Boolean =
        storage.contains(key(eventKey, profileId, version))

    @Synchronized
    fun clear() = storage.clear()
}
