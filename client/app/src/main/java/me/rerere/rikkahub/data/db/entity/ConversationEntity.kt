package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    @ColumnInfo("profile_id", defaultValue = "default-learner")
    val profileId: String = me.rerere.rikkahub.data.datastore.DEFAULT_PROFILE_ID,
    @ColumnInfo("queue_state", defaultValue = "'{}'")
    val queueState: String = "{}",
    @ColumnInfo("study_mode", defaultValue = "'GUIDED'")
    val studyMode: String = "GUIDED",
    @ColumnInfo("lifecycle_revision", defaultValue = "0")
    val lifecycleRevision: Long = 0,
    @ColumnInfo("is_archived", defaultValue = "0")
    val isArchived: Boolean = false,
)
