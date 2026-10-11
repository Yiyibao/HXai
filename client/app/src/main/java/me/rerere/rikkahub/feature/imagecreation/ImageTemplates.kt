package me.rerere.rikkahub.feature.imagecreation

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.data.sync.PendingRestore

@Serializable
data class ImageTemplate(val id: String, val profileId: String, val name: String,
    val prompt: String, val size: String, val quality: String)

/** Personal recipes deliberately keep no reference file or paid request identity. */
class ImageTemplates(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun directory(owner: String): File {
        require(MaterialFiles.validProfile(owner))
        return root.resolve(owner).apply { mkdirs() }
    }
    @Synchronized fun list(owner: String): List<ImageTemplate> = directory(owner).listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { file ->
            runCatching { json.decodeFromString<ImageTemplate>(file.readText()) }.getOrNull()
                ?.takeIf { it.profileId == owner && file.name == "${it.id}.json" }
        }.sortedBy { it.name }
    @Synchronized fun save(owner: String, id: String?, name: String, prompt: String, size: String, quality: String): ImageTemplate {
        require(name.isNotBlank() && name.length <= 80)
        require(prompt.contains("{主题}")) { "模板必须包含可替换的 {主题}" }
        ImageCreationProtocol.body("template", prompt, size, quality, emptyList())
        val key = id?.also { UUID.fromString(it); require(list(owner).any { row -> row.id == it }) } ?: UUID.randomUUID().toString()
        val item = ImageTemplate(key, owner, name.trim(), prompt.trim(), size, quality)
        PendingRestore.writeDurably(directory(owner).resolve("$key.json"), json.encodeToString(item))
        return item
    }
    @Synchronized fun delete(owner: String, id: String) {
        UUID.fromString(id)
        require(list(owner).any { it.id == id })
        check(directory(owner).resolve("$id.json").delete())
    }
    fun toMap(item: ImageTemplate): Map<String, String> = mapOf("id" to item.id, "name" to item.name,
        "prompt" to item.prompt, "size" to item.size, "quality" to item.quality)
}
