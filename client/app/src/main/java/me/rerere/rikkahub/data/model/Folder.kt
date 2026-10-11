package me.rerere.rikkahub.data.model

import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 会话文件夹（助手内分组）。
 */
data class Folder(
    val profileId: String = me.rerere.rikkahub.data.datastore.DEFAULT_PROFILE_ID,
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val name: String,
    val sortIndex: Int = 0,
    val createAt: Instant = Instant.now(),
)
