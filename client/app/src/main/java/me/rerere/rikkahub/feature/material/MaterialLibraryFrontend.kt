package me.rerere.rikkahub.feature.material

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Transport adapter; authority comes only from the host closure, never JSON. */
class MaterialLibraryFrontend(private val context: Context, private val files: MaterialFiles) {
    suspend fun handle(method: String, owner: String, args: Map<*, *>, checkAccess: () -> Unit,
        pickDirectory: suspend () -> Uri?): Any? {
        checkAccess()
        fun string(key: String) = args[key] as? String ?: error("缺少$key")
        fun number(key: String, default: Int) = (args[key] as? Number)?.toInt() ?: default
        fun current(): MaterialFile = files.get(owner, string("id")).also {
            check(it.sha256 == string("sha256")) { "材料版本已改变，请重新选择" }
        }
        val result: Any? = when (method) {
            "library.snapshot" -> mapOf("contractVersion" to 1,
                "files" to files.list(owner).map(::summary), "trash" to files.trash(owner).map(::summary))
            "library.importDirectory" -> {
                val tree = withContext(Dispatchers.Main) { pickDirectory() }
                checkAccess()
                if (tree == null) mapOf("cancelled" to true) else MaterialDirectoryImport(context, files).import(owner, tree, checkAccess)
            }
            "library.organize" -> {
                current(); checkAccess()
                files.organize(owner, string("id"), string("sha256"), string("folder"),
                    (args["tags"] as? List<*>)?.map { it as? String ?: error("标签必须是文字") } ?: emptyList()); null
            }
            "library.delete" -> { current(); checkAccess(); files.delete(owner, string("id"), string("sha256")); null }
            "library.restore" -> summary(files.restore(owner, string("id"), checkAccess))
            "library.purge" -> { checkAccess(); files.purge(owner, string("id")); null }
            "library.imagePath" -> {
                val item = current()
                require(item.extension in setOf("png", "jpg", "jpeg", "webp"))
                files.verifiedFile(owner, item.id).absolutePath
            }
            "library.versions" -> {
                val item = current()
                MaterialVersions.family(files.list(owner) + files.trash(owner), item.id).map(::summary)
            }
            "library.read" -> {
                val item = current()
                files.readLibraryTextPage(owner, item.id, item.sha256, number("offset", 0), number("count", 12000)).toString()
            }
            "library.chapters" -> {
                val item = current()
                files.chapterIndex(owner, item.id, item.sha256, number("offset", 0), 20).toString()
            }
            "library.search" -> files.searchLibraryText(owner, string("query"), args["cursor"] as? String ?: "", 20, 5).toString()
            "library.confirmSymbols" -> {
                val item = current(); checkAccess()
                files.confirmSymbol(owner, item.id, item.sha256, (args["transcription"] as? String)?.takeIf { it.isNotBlank() },
                    fullMathInput = true, stillAllowed = checkAccess); null
            }
            "library.mathEvidence" -> {
                val item = current()
                MaterialMathEvidence.source(files, owner, item.id, item.sha256, number("offset", 0), number("count", 4000)).toString()
            }
            else -> error("未接入的资料库操作")
        }
        checkAccess()
        return result
    }

    private fun summary(item: MaterialFile): Map<String, Any?> = mapOf(
        "id" to item.id, "name" to item.name, "extension" to item.extension, "sha256" to item.sha256,
        "size" to item.size, "createdAt" to item.createdAt, "folder" to item.folder, "tags" to item.tags,
        "output" to item.output, "state" to item.state, "detail" to item.detail, "sourceId" to item.sourceId,
        "processingState" to item.processingState, "deletedAt" to item.deletedAt,
        "recoverable" to (item.deletedAt != null && files.file(item).isFile),
        "symbolCheckPending" to item.symbolCheckPending, "symbolCorrection" to item.symbolCorrection,
        "symbolSourceSha256" to item.symbolSourceSha256, "mathInputConfirmed" to item.mathInputConfirmed)
}
