package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FolderDAO
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.model.Folder
import java.time.Instant
import kotlin.uuid.Uuid

class FolderRepository(
    private val folderDAO: FolderDAO,
    private val conversationDAO: ConversationDAO,
    private val settingsStore: SettingsStore,
    private val database: AppDatabase,
) {
    private val activeProfileId get() = settingsStore.settingsFlow.value.activeProfileId
    fun getFoldersOfAssistant(assistantId: Uuid): Flow<List<Folder>> {
        return settingsStore.settingsFlow.map { it.activeProfileId }.distinctUntilChanged()
            .flatMapLatest { profileId ->
                folderDAO.getFoldersOfAssistant(assistantId.toString(), profileId).onStart { emit(emptyList()) }
            }
            .map { list -> list.map { it.toFolder() } }
    }

    suspend fun getFolderById(id: Uuid): Folder? {
        return folderDAO.getFolderById(id.toString(), activeProfileId)?.toFolder()
    }

    suspend fun createFolder(assistantId: Uuid, name: String): Folder {
        val folder = Folder(
            profileId = activeProfileId,
            assistantId = assistantId,
            name = name,
            createAt = Instant.now(),
        )
        folderDAO.insert(folder.toEntity())
        return folder
    }

    suspend fun renameFolder(id: Uuid, name: String) {
        folderDAO.rename(id.toString(), name, activeProfileId)
    }

    /**
     * 删除文件夹，先把归属该文件夹的会话 folder_id 清空，再删除文件夹本身（不影响会话）。
     */
    suspend fun deleteFolder(id: Uuid) {
        val profileId = activeProfileId
        database.withTransaction {
            require(folderDAO.getFolderById(id.toString(), profileId) != null) { "Folder access denied" }
            conversationDAO.clearFolderForProfile(id.toString(), profileId)
            folderDAO.deleteById(id.toString(), profileId)
        }
    }
}

private fun FolderEntity.toFolder(): Folder = Folder(
    profileId = profileId,
    id = Uuid.parse(id),
    assistantId = Uuid.parse(assistantId),
    name = name,
    sortIndex = sortIndex,
    createAt = Instant.ofEpochMilli(createAt),
)

private fun Folder.toEntity(): FolderEntity = FolderEntity(
    profileId = profileId,
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)
