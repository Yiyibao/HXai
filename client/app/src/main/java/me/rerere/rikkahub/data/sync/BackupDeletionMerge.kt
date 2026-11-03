package me.rerere.rikkahub.data.sync

import android.content.Context
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
            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
        } finally { room.close(); DatabaseBackup.removeSidecars(file) }
    }
}
