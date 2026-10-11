package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Kept after restoration so callbacks from an earlier incarnation stay invalid. */
@Entity
data class ConversationDeletionEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val revision: Long,
    val deleted: Boolean,
)
