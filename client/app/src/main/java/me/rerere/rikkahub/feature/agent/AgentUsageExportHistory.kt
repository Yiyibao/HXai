package me.rerere.rikkahub.feature.agent

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.feature.material.MaterialFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** A saved expected result is an intent, never a verified receipt or permission. */
@Serializable
internal data class AgentUsageExportIntent(
    val schemaVersion: Int = 1, val owner: String, val taskId: String?, val from: Long?, val until: Long?,
    val groupBy: String, val requestId: String, val artifactId: String, val sha256: String,
    val size: Long, val createdAt: Long,
)

/** Immutable, local-only request projections. Missing old projections are not reconstructed by guessing. */
internal class AgentUsageExportHistory(context: Context) {
    private val root = context.noBackupFilesDir.resolve("agent-usage-export-intents/v1")
    private val json = Json { ignoreUnknownKeys = false }
    private fun directory(owner: String, task: String?, from: Long?, until: Long?, group: String): File {
        validateScope(owner, task, from, until, group)
        val key = AgentTaskStore.hash(Json.encodeToString(listOf(owner, task, from?.toString(), until?.toString(), group)))
        val folder = root.resolve("$owner/$key")
        var node: File? = folder
        while (node != null && node != root.parentFile) {
            require(!Files.isSymbolicLink(node.toPath())) { "导出意图路径不可为链接" }
            node = node.parentFile
        }
        return folder
    }
    private fun validate(item: AgentUsageExportIntent) {
        validateScope(item.owner, item.taskId, item.from, item.until, item.groupBy)
        require(item.schemaVersion == 1 && UUID.fromString(item.requestId).toString() == item.requestId)
        require(item.artifactId == AgentUsageExport.artifactId(item.owner, item.requestId))
        require(item.sha256.matches(Regex("[a-f0-9]{64}")) && item.size in 1..AgentUsageSnapshot.MAX_BYTES.toLong() && item.createdAt >= 0)
    }
    private fun read(file: File): AgentUsageExportIntent {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..4096) { "导出意图损坏；原记录保留" }
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 4096)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= 4096)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return json.decodeFromString<AgentUsageExportIntent>(text).also { validate(it);require(file.name == "${it.requestId}.json") }
    }
    @Synchronized fun record(item: AgentUsageExportIntent) {
        validate(item)
        val dir = directory(item.owner,item.taskId,item.from,item.until,item.groupBy)
        check(dir.mkdirs() || dir.isDirectory)
        val file = dir.resolve("${item.requestId}.json")
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) { require(read(file) == item) { "原导出意图已改变" };return }
        val temp = dir.resolve("${item.requestId}.tmp")
        // Unknown remnants are preserved. A fresh request uses another immutable path.
        require(!Files.exists(temp.toPath(), NOFOLLOW_LINKS)) { "导出意图写入结果未知；请创建新请求" }
        val bytes = json.encodeToString(item).toByteArray(Charsets.UTF_8);require(bytes.size <= 4096)
        java.io.FileOutputStream(temp).use { it.write(bytes);it.fd.sync() }
        require(temp.readBytes().contentEquals(bytes))
        Files.move(temp.toPath(),file.toPath(),ATOMIC_MOVE)
        require(read(file) == item)
    }
    @Synchronized fun last(owner: String, task: String?, from: Long?, until: Long?, group: String): AgentUsageExportIntent? {
        val dir = directory(owner,task,from,until,group)
        if (!dir.exists()) return null
        require(dir.isDirectory)
        return dir.listFiles()?.filter { it.name.endsWith(".json") }?.map { file ->
            read(file).also { require(it.owner == owner && it.taskId == task && it.from == from && it.until == until && it.groupBy == group) }
        }?.maxWithOrNull(compareBy<AgentUsageExportIntent> { it.createdAt }.thenBy { it.requestId })
    }
    @Synchronized fun get(owner: String, task: String?, from: Long?, until: Long?, group: String, request: String): AgentUsageExportIntent {
        require(UUID.fromString(request).toString() == request)
        return read(directory(owner,task,from,until,group).resolve("$request.json")).also {
            require(it.owner == owner && it.taskId == task && it.from == from && it.until == until && it.groupBy == group && it.requestId == request)
        }
    }
    companion object {
        fun validateScope(owner: String,task: String?,from: Long?,until: Long?,group: String) {
            require(MaterialFiles.validProfile(owner) && (task == null || UUID.fromString(task).toString() == task))
            require(group in setOf("requests","provider","model","role"))
            require((from == null && until == null) || (from != null && until != null && from >= 0 && until > from))
        }
    }
}
