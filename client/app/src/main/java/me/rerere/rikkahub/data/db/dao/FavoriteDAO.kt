package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.FavoriteEntity

@Dao
interface FavoriteDAO {
    @Query("SELECT EXISTS(SELECT 1 FROM message_node n JOIN ConversationEntity c ON c.id = n.conversation_id WHERE 'node:' || c.id || ':' || n.id = :refKey AND c.profile_id = :profileId)")
    suspend fun canAccessNode(refKey: String, profileId: String): Boolean

    @Query("SELECT * FROM favorites WHERE EXISTS(SELECT 1 FROM message_node n JOIN ConversationEntity c ON c.id = n.conversation_id WHERE favorites.ref_key = 'node:' || c.id || ':' || n.id AND c.profile_id = :profileId) ORDER BY created_at DESC")
    fun listForProfile(profileId: String): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE ref_key = :refKey AND EXISTS(SELECT 1 FROM message_node n JOIN ConversationEntity c ON c.id = n.conversation_id WHERE favorites.ref_key = 'node:' || c.id || ':' || n.id AND c.profile_id = :profileId) LIMIT 1")
    suspend fun getRefForProfile(refKey: String, profileId: String): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FavoriteEntity?

    @Transaction
    suspend fun upsertForProfile(favorite: FavoriteEntity, profileId: String) {
        require(favorite.type == "node" && canAccessNode(favorite.refKey, profileId)) { "Favorite access denied" }
        val existing = getById(favorite.id)
        require(existing == null || existing.refKey == favorite.refKey) { "Favorite identity mismatch" }
        upsert(favorite)
    }

    @Query("DELETE FROM favorites WHERE ref_key = :refKey AND EXISTS(SELECT 1 FROM message_node n JOIN ConversationEntity c ON c.id = n.conversation_id WHERE favorites.ref_key = 'node:' || c.id || ':' || n.id AND c.profile_id = :profileId)")
    suspend fun deleteRefForProfile(refKey: String, profileId: String): Int

    @Query("DELETE FROM favorites WHERE id = :id AND EXISTS(SELECT 1 FROM message_node n JOIN ConversationEntity c ON c.id = n.conversation_id WHERE favorites.ref_key = 'node:' || c.id || ':' || n.id AND c.profile_id = :profileId)")
    suspend fun deleteIdForProfile(id: String, profileId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites ORDER BY created_at DESC")
    fun listAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE type = :type ORDER BY created_at DESC")
    fun listByType(type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT ref_key FROM favorites WHERE type = :type")
    suspend fun getRefKeysByType(type: String): List<String>

    @Query("SELECT substr(ref_key, length('node:' || :conversationId || ':') + 1) FROM favorites WHERE ref_key LIKE 'node:' || :conversationId || ':%'")
    suspend fun getFavoriteNodeIdsOfConversation(conversationId: String): List<String>

    @Query("SELECT * FROM favorites WHERE ref_key = :refKey LIMIT 1")
    suspend fun getByRefKey(refKey: String): FavoriteEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE ref_key = :refKey)")
    suspend fun existsByRefKey(refKey: String): Boolean

    @Query("DELETE FROM favorites WHERE ref_key = :refKey")
    suspend fun deleteByRefKey(refKey: String): Int

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
