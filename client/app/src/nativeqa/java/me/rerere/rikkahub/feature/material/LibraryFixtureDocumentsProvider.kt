package me.rerere.rikkahub.feature.material

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Exists only in the isolated nativeqa APK. Never compiled into ordinary debug/release. */
class LibraryFixtureDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true
    private fun file(id: String): File {
        val root = File(requireNotNull(context).filesDir, "library-saf-fixture").canonicalFile
        val file = File(root, id).canonicalFile
        require(file.path.startsWith(root.path + File.separator))
        return file
    }
    private fun document(cursor: MatrixCursor, id: String) {
        val file = file(id)
        val values: Map<String, Any?> = mapOf(Document.COLUMN_DOCUMENT_ID to id, Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.COLUMN_SIZE to file.length(), Document.COLUMN_FLAGS to if (file.isDirectory) Document.FLAG_DIR_PREFERS_GRID else 0)
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).also {
        val values: Map<String, Any?> = mapOf(Root.COLUMN_ROOT_ID to "fixture", Root.COLUMN_DOCUMENT_ID to "selected",
            Root.COLUMN_TITLE to "B04 synthetic directory", Root.COLUMN_FLAGS to Root.FLAG_SUPPORTS_IS_CHILD)
        it.addRow(it.columnNames.map { key -> values[key] }.toTypedArray())
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).also { document(it, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: columns).also { cursor ->
            file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { child -> document(cursor, "$parentDocumentId/${child.name}") }
        }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).path.startsWith(file(parentDocumentId).path + File.separator)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r")
        signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    companion object {
        private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
    }
}
