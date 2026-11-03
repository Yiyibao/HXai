package me.rerere.rikkahub.feature.material

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Keep remote task/UNKNOWN journals. Remove only derived assets whose trusted task names this source. */
internal object MaterialSourceCaches {
    fun invalidate(context: Context, owner: String, sourceId: String) {
        val directory = File(context.noBackupFilesDir, "document-service/$owner")
        directory.listFiles().orEmpty().filter { it.name.endsWith(".task.json") }.forEach { metadata ->
            val task = Json.decodeFromString<DocumentTask>(metadata.readText())
            require(runCatching { UUID.fromString(task.id) }.isSuccess && metadata.name == "${task.id}.task.json")
            if (task.sourceId == sourceId) {
                val result = File(directory, "${task.id}.result")
                check(!result.exists() || result.deleteRecursively()) { "解析缓存清理失败；已删除来源仍不可读取" }
            }
        }
    }
}
