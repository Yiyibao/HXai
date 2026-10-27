package me.rerere.rikkahub.feature.material

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.PendingRestore
import java.io.File
import java.util.UUID

/** Logical organization only: folder names never become host paths. */
object MaterialLibraryMetadata {
    fun validate(folder: String, tags: List<String>) {
        require(folder.length <= 500 && (folder.isEmpty() || folder.split('/').let { parts ->
            parts.size <= 12 && parts.all { it.isNotBlank() && it == it.trim() && it !in setOf(".", "..") &&
                it.length <= 80 && it.none { c -> c.isISOControl() || c in "\\:" } }
        })) { "文件夹路径无效：最多12层，不允许空层、点路径或控制字符" }
        require(tags.size <= 20 && tags.all { it.isNotBlank() && it == it.trim() && it.length <= 40 && it.none(Char::isISOControl) }) { "标签最多20个，每个1–40字符" }
    }

    fun tombstone(item: MaterialFile): MaterialFile {
        require(item.deletedAt != null)
        return item.copy(libraryVersion = 2, name = "Deleted material", detail = "用户已删除；恢复不得复活旧ID",
            state = "DELETED", processingState = "CANCELLED", symbolCorrection = null, symbolSourceSha256 = null,
            symbolCheckPending = false, mathInputConfirmed = false, sourceReferences = emptyList(), folder = "", tags = emptyList(), requestKey = "")
    }

    fun includeInBackup(directory: File, relative: String): Boolean {
        val parts = relative.split('/')
        if (parts.size != 2) return false
        val id = parts[1].substringBefore('.')
        if (runCatching { UUID.fromString(id) }.isFailure) return false
        val modern = File(directory, "${parts[0]}/$id.meta.json")
        val metadata = if (modern.isFile) modern else File(directory, "${parts[0]}/$id.json")
        if (!metadata.isFile) return false
        val item = if (modern.isFile) Json.decodeFromString<MaterialFile>(metadata.readText())
            else runCatching { Json.decodeFromString<MaterialFile>(metadata.readText()) }.getOrNull() ?: return false
        require(item.profileId == parts[0] && item.id == id && item.libraryVersion in 1..2)
        return item.deletedAt == null || parts[1] == "$id.meta.json"
    }

    /** Merge deletion identity, not user content, before the existing atomic restore publication. */
    fun mergeDeleted(live: File, restored: File) {
        val deletions = linkedMapOf<Pair<String, String>, MaterialFile>()
        for (root in listOf(restored, live)) {
            root.listFiles().orEmpty().filter { it.isDirectory && MaterialFiles.validProfile(it.name) }.forEach { owner ->
                owner.listFiles().orEmpty().filter { it.name.endsWith(".meta.json") }.forEach { file ->
                    val item = Json.decodeFromString<MaterialFile>(file.readText())
                    require(item.libraryVersion in 1..2 && item.profileId == owner.name && file.name == "${item.id}.meta.json")
                    if (item.deletedAt != null) deletions[item.profileId to item.id] = tombstone(item)
                }
            }
        }
        for ((key, item) in deletions) {
            val directory = File(restored, key.first).apply { check(mkdirs() || isDirectory) }
            directory.listFiles().orEmpty().filter { it.name.startsWith("${item.id}.") && it.name != "${item.id}.meta.json" }
                .forEach { check(it.delete() || !it.exists()) { "删除约束无法应用；恢复未发布" } }
            PendingRestore.writeDurably(File(directory, "${item.id}.meta.json"), Json.encodeToString(item))
        }
    }
}
