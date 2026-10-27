package me.rerere.rikkahub.feature.material

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One foreground SAF grant, one bounded copy. No stored tree grant and no background scan. */
class MaterialDirectoryImport(private val context: Context, private val files: MaterialFiles) {
    suspend fun import(owner: String, tree: Uri, checkAccess: () -> Unit): Map<String, Any?> = withContext(Dispatchers.IO) {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "请选择系统授权目录" }
        val imported = mutableListOf<String>()
        val failures = mutableListOf<Map<String, String>>()
        val visited = hashSetOf<String>()
        var entries = 0
        var bytes = 0L
        var complete = true
        suspend fun walk(documentId: String, folder: String, depth: Int) {
            currentCoroutineContext().ensureActive(); checkAccess()
            check(depth <= 12 && visited.add(documentId)) { "目录过深或存在循环引用；剩余范围未导入" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive(); checkAccess()
                    check(++entries <= 500) { "目录超过500个条目；剩余范围未导入" }
                    val id = cursor.getString(0); val name = cursor.getString(1) ?: error("目录条目缺少名称")
                    require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "目录条目名称无效" }
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val path = if (folder.isEmpty()) name else "$folder/$name"
                        MaterialLibraryMetadata.validate(path, emptyList())
                        walk(id, path, depth + 1)
                    } else {
                        check(cursor.isNull(3) || cursor.getLong(3) <= 30L * 1024 * 1024) { "目录含超限文件；剩余范围未导入" }
                        check(bytes < 300L * 1024 * 1024) { "单次目录复制超过300 MiB；剩余范围未导入" }
                        try {
                            val item = files.import(owner, DocumentsContract.buildDocumentUriUsingTree(tree, id), name, folder, checkAccess,
                                minOf(30L * 1024 * 1024, 300L * 1024 * 1024 - bytes))
                            bytes += item.size
                            imported += item.id
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            checkAccess()
                            failures += mapOf("name" to name.take(160), "error" to (error.message?.take(160) ?: "导入失败"))
                        }
                    }
                }
            } ?: error("无法枚举授权目录；未读取其他目录")
        }
        try { walk(DocumentsContract.getTreeDocumentId(tree), "", 0) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            checkAccess(); complete = false
            failures += mapOf("name" to "所选目录", "error" to (error.message?.take(160) ?: "枚举中断"))
        }
        checkAccess()
        mapOf("contractVersion" to 1, "importedIds" to imported, "failures" to failures, "enumerationComplete" to complete,
            "copiedBytes" to bytes, "notice" to "仅复制本次系统所选目录；成功复制不等于全文已读。失败/未遍历范围未导入，已保存原件保留。未保存目录访问权。")
    }
}
