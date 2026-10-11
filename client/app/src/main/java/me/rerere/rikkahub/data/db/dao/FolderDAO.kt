package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.FolderEntity

@Dao
interface FolderDAO {
    @Query("SELECT * FROM conversation_folder WHERE assistant_id = :assistantId AND profile_id = :profileId ORDER BY sort_index ASC, create_at ASC")
    fun getFoldersOfAssistant(assistantId: String, profileId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM conversation_folder WHERE id = :id AND profile_id = :profileId")
    suspend fun getFolderById(id: String, profileId: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE conversation_folder SET name = :name WHERE id = :id AND profile_id = :profileId")
    suspend fun rename(id: String, name: String, profileId: String)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM conversation_folder WHERE id = :id AND profile_id = :profileId")
    suspend fun deleteById(id: String, profileId: String)
}
