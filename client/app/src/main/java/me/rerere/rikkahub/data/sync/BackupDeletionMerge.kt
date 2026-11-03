package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.CredentialVault
import me.rerere.rikkahub.data.db.*
import me.rerere.rikkahub.feature.memory.*
import java.io.File

internal object BackupDeletionMerge {
    suspend fun merge(context: Context, json: Json, staging: File, liveDatabase: AppDatabase) {
        val restoredReference = File(staging, "payload/files/${PortableMemoryKey.FILE_NAME}")
        val restoredKey = CredentialVault(context).resolve(restoredReference.readText())
        val current = liveDatabase.learningMemoryDao().all()
        val key = PortableMemoryKey(context)
        require(current.isEmpty() || key.existing() == restoredKey) { "备份来自不同记忆保护域；为保留当前删除约束，请在空白安装中恢复" }
        val file = File(staging, "payload/database/${SQLiteConfiguration.DATABASE_NAME}")
        val room = AppDatabaseFactory.create(context, file.absolutePath)
        val fingerprints = MemoryFingerprints(context)
        val liveStore = LearningMemoryStore(liveDatabase, fingerprints)
        try {
            current.forEach { entity ->
                val live = liveStore.read(entity.profileId)
                val saved = room.learningMemoryDao().get(entity.profileId)?.let { json.decodeFromString<MemoryState>(it.payload) }
                    ?: MemoryState(fingerprintVersion = 1)
                val merged = MemoryRestore.merge(saved, live) { value -> fingerprints.of(entity.profileId, value) }
                room.learningMemoryDao().put(LearningMemoryStateEntity(entity.profileId, json.encodeToString(merged)))
            }
            // Cancel all archived work, including foreign profiles absent on the target installation.
            room.learningMemoryDao().all().forEach { entity ->
                val state = json.decodeFromString<MemoryState>(entity.payload)
                val clean = MemoryRestore.merge(state, MemoryState(fingerprintVersion = 1)) { it }
                room.learningMemoryDao().put(entity.copy(payload = json.encodeToString(clean)))
            }
            room.withTransaction {
                // Conversation deletion is independent of memory extraction and its key domain.
                val markers = liveDatabase.openHelper.readableDatabase.query("SELECT id, profileId, revision, deleted FROM ConversationDeletionEntity").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity(
                        cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) != 0)) }
                }
                for (marker in markers) {
                    val archived = room.conversationDao().getConversationById(marker.id)
                    val previous = room.conversationDao().deletion(marker.id)
                    require(archived == null || archived.profileId == marker.profileId) { "恢复聊天ID与当前删除记录的档案不一致" }
                    require(previous == null || previous.profileId == marker.profileId) { "恢复删除记录档案不一致" }
                    if (marker.deleted) {
                        room.conversationDao().deleteById(marker.id)
                        room.openHelper.writableDatabase.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(marker.id))
                        room.conversationDao().recordDeletion(marker.copy(revision = maxOf(marker.revision, previous?.revision ?: 0)))
                    } else if (previous == null || previous.revision < marker.revision) {
                        room.conversationDao().recordDeletion(marker)
                    }
                }
                // An archive never resumes queued work or an old lifecycle. The local task
                // sidecar is not replaced by backup and cannot publish into a restored incarnation.
                for (id in room.conversationDao().getAllIds()) {
                    val archived = requireNotNull(room.conversationDao().getConversationById(id))
                    val live = liveDatabase.conversationDao().getConversationById(id)
                    require(live == null || live.profileId == archived.profileId) { "恢复聊天ID与当前档案不一致" }
                    val previous = room.conversationDao().deletion(id)
                    if (previous?.deleted == true) { room.conversationDao().deleteById(id); continue }
                    val next = Math.addExact(maxOf(archived.lifecycleRevision, live?.lifecycleRevision ?: 0,
                        previous?.revision ?: 0), 1)
                    val queue = json.decodeFromString<me.rerere.rikkahub.data.model.ConversationQueue>(archived.queueState).recovered()
                    room.conversationDao().update(archived.copy(lifecycleRevision = next, queueState = json.encodeToString(queue)))
                    room.conversationDao().recordDeletion(me.rerere.rikkahub.data.db.entity.ConversationDeletionEntity(id, archived.profileId, next, false))
                    // Provenance from a different incarnation cannot authenticate new metadata.
                }
            }
            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
        } finally { room.close(); DatabaseBackup.removeSidecars(file) }
    }
}
