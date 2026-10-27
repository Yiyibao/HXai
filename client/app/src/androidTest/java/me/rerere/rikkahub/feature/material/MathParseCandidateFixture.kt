package me.rerere.rikkahub.feature.material

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.net.toUri
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Deliberately incorrect synthetic parse, not a recognition-quality fixture. */
internal class MathParseCandidateFixture(val context: Context, val owner: String = UUID.randomUUID().toString()) {
    val files = MaterialFiles(context)
    val taskId = UUID.randomUUID().toString()
    val directory = File(context.noBackupFilesDir, "document-service/$owner")
    lateinit var source: MaterialFile
    suspend fun prepare(text: String = "x+x=3*x") {
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val pdfFile = File.createTempFile("math-candidate-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            for (i in 1..3) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, i).create())
                page.canvas.drawText(if (i == 1) "x+x=2*x" else "Page $i", 20f, 100f, Paint().apply { textSize = 24f })
                pdf.finishPage(page)
            }
            pdfFile.outputStream().use(pdf::writeTo)
            source = files.import(owner, pdfFile.toUri(), "QA数学解析候选三页.pdf")
        } finally { pdf.close(); pdfFile.delete() }
        directory.mkdirs()
        val document = ParsedDocument(1, source.sha256, 1, "QA_SYNTHETIC_NOT_OCR", "qa-v1", "basic", 3,
            listOf(1, 3), listOf(1, 3), listOf(3), listOf(2), false, listOf("第2页失败；候选不代表真实识别"),
            listOf(ParsedBlock("p1-b1", 1, "formula", text, text)))
        val result = File(directory, "$taskId.result").apply { mkdirs() }
        val documentFile = File(result, "document").apply { writeText(Json.encodeToString(document)) }
        val manifest = DocumentManifest(1, source.sha256, 1, document.parserVersion, document.adapterVersion, "basic", 3,
            document.requestedRange, document.processedPages, document.blankPages, document.failedPages, false, document.warnings,
            listOf(DocumentAsset("document", "document.json", "application/json", documentFile.length(), MaterialFiles.digest(documentFile))))
        File(result, "manifest.json").writeText(Json.encodeToString(manifest))
        val task = DocumentTask(taskId, source.id, source.sha256, 1, 3, "synthetic-cache-only", state = "PARTIAL", downloaded = true)
        File(directory, "$taskId.task.json").writeText(Json.encodeToString(task))
    }
    fun close() {
        directory.deleteRecursively()
        File(context.filesDir, "materials/$owner").deleteRecursively()
        val ownerKey = MaterialSourceReceipt.hex(java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()))
        File(context.noBackupFilesDir, "math-work-inputs/$ownerKey").deleteRecursively()
    }
}
