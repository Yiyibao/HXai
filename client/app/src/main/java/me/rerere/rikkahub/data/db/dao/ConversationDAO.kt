package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.repository.LightConversationEntity

@Dao
interface ConversationDAO {
    @Query("SELECT id, title, update_at AS updateAt, is_pinned AS isPinned, study_mode AS studyMode FROM conversationentity WHERE profile_id = :profileId AND folder_id = :folderId AND is_archived = 0 ORDER BY is_pinned DESC, update_at DESC, id")
    suspend fun projectSummaries(profileId: String, folderId: String): List<ProjectConversationSummary>

    @Query("SELECT * FROM ConversationDeletionEntity WHERE id = :id")
    suspend fun deletion(id: String): me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun recordDeletion(value: me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity)

    @Query("UPDATE ConversationEntity SET title = :title WHERE id = :id AND profile_id = :profileId")
    suspend fun updateTitle(id: String, profileId: String, title: String): Int

    @Query("UPDATE ConversationEntity SET suggestions = :suggestions WHERE id = :id AND profile_id = :profileId")
    suspend fun updateSuggestions(id: String, profileId: String, suggestions: String): Int

    @Query("UPDATE ConversationEntity SET queue_state = :queue WHERE id = :id AND profile_id = :profileId")
    suspend fun updateQueue(id: String, profileId: String, queue: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM ConversationEntity WHERE instr(queue_state, :quotedUrl) > 0)")
    suspend fun hasQueuedFileReference(quotedUrl: String): Boolean
    @Query("UPDATE ConversationEntity SET folder_id = '' WHERE folder_id = :folderId AND profile_id = :profileId")
    suspend fun clearFolderForProfile(folderId: String, profileId: String)
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE profile_id = :profileId AND assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String, profileId: String = "default-learner"): Flow<List<ConversationEntity>>

    @Query("SELECT id, profile_id as profileId, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE profile_id = :profileId AND assistant_id = :assistantId AND is_archived = 0 ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String, profileId: String = "default-learner"): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, profile_id as profileId, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE profile_id = :profileId AND assistant_id = :assistantId AND folder_id = '' AND is_archived = 0 ORDER BY is_pinned DESC, update_at DESC")
    fun getUnfiledConversationsOfAssistantPaging(assistantId: String, profileId: String = "default-learner"): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, profile_id as profileId, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE profile_id = :profileId AND folder_id = :folderId AND is_archived = 0 ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfFolderPaging(folderId: String, profileId: String = "default-learner"): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE profile_id = :profileId AND assistant_id = :assistantId AND is_archived = :archived ORDER BY is_pinned DESC, update_at DESC, id LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int, profileId: String = "default-learner", archived: Boolean = false): List<ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE profile_id = :profileId AND is_archived = 0 AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String, profileId: String = "default-learner"): Flow<List<ConversationEntity>>

    @Query("SELECT id, profile_id as profileId, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE profile_id = :profileId AND is_archived = 0 AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String, profileId: String = "default-learner"): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE profile_id = :profileId AND assistant_id = :assistantId AND is_archived = 0 AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String, profileId: String = "default-learner"): Flow<List<ConversationEntity>>

    @Query("SELECT id, profile_id as profileId, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE profile_id = :profileId AND assistant_id = :assistantId AND is_archived = 0 AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String, profileId: String = "default-learner"): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversationentity WHERE profile_id = :profileId AND is_pinned = 1 AND is_archived = 0 ORDER BY update_at DESC")
    fun getPinnedConversations(profileId: String = "default-learner"): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id AND profile_id = :profileId")
    suspend fun updatePinStatus(id: String, isPinned: Boolean, profileId: String)

    @Query("UPDATE conversationentity SET is_archived = :archived WHERE id = :id AND profile_id = :profileId")
    suspend fun updateArchiveStatus(id: String, archived: Boolean, profileId: String)

    @Query("UPDATE conversationentity SET assistant_id = :assistantId, folder_id = '' WHERE id = :id AND profile_id = :profileId")
    suspend fun updateAssistantId(id: String, assistantId: String, profileId: String)

    @Query("UPDATE conversationentity SET folder_id = :folderId WHERE id = :id AND profile_id = :profileId")
    suspend fun updateFolderId(id: String, folderId: String, profileId: String)

    @Query("UPDATE conversationentity SET folder_id = '' WHERE folder_id = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT COUNT(*) FROM conversationentity WHERE profile_id = :profileId")
    suspend fun countAll(profileId: String = "default-learner"): Int

    @Query("SELECT assistant_id AS assistantId, COUNT(*) AS count FROM conversationentity WHERE profile_id = :profileId GROUP BY assistant_id")
    suspend fun countByAssistant(profileId: String = "default-learner"): List<AssistantConversationCount>

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)

data class AssistantConversationCount(val assistantId: String, val count: Int)

data class ProjectConversationSummary(val id: String, val title: String, val updateAt: Long, val isPinned: Boolean, val studyMode: String)
