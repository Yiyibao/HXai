package me.rerere.rikkahub.feature.material

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Original-page assembly, not OCR or preservation of document-level interactive features. */
object MaterialPdfAssembly {
    private const val EDGE = 1024
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
    private data class Input(val item: MaterialFile, val file: File, val pages: List<Int>)

    suspend fun edit(store: MaterialFiles, owner: String, source: MaterialFile, spec: JsonObject, title: String): MaterialFile {
        require(source.extension == "pdf" && spec.keys == setOf("sources"))
        val entries = spec.getValue("sources").jsonArray
        require(entries.size in 1..20)
        val inputs = entries.map { entry ->
            val value = entry.jsonObject
            require(value.keys == setOf("id", "sha256", "pages"))
            val item = store.get(owner, value.string("id"))
            require(item.extension == "pdf" && item.sha256 == value.string("sha256")) { "PDF源版本摘要不匹配" }
            val pages = value.getValue("pages").jsonArray
            require(pages.size in 1..200)
            Input(item, store.verifiedFile(owner, item.id), pages.map { it.jsonPrimitive.also { p -> require(!p.isString) }.int })
        }
        require(inputs.first().item.id == source.id && inputs.first().item.sha256 == source.sha256)
        require(inputs.sumOf { it.pages.size } in 1..200 && inputs.sumOf { it.item.size } <= 60L * 1024 * 1024)
        val snapshots = mutableListOf<File>()
        try {
            val frozen = inputs.map { input ->
                // Private sibling files are recovered by MaterialFiles if the process exits during assembly.
                val snapshot = File(input.file.parentFile, "${UUID.randomUUID()}.part").also { snapshots += it }
                input.file.inputStream().use { from -> snapshot.outputStream().use { to ->
                    val buffer = ByteArray(8192); var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = from.read(buffer); if (size < 0) break
                        copied += size; check(copied <= input.item.size && copied <= 30L * 1024 * 1024) { "PDF源大小改变" }
                        to.write(buffer, 0, size)
                    }
                    check(copied == input.item.size) { "PDF源大小改变" }
                } }
                check(MaterialFiles.digest(snapshot) == input.item.sha256) { "PDF源摘要改变" }
                snapshot to input.pages
            }
            fun checkSources() = inputs.forEach { input ->
                store.verifiedFile(owner, input.item.id)
                val current = store.get(owner, input.item.id)
                check(current.sha256 == input.item.sha256 && current.size == input.item.size &&
                    current.extension == input.item.extension && current.sourceId == input.item.sourceId) { "PDF源版本或谱系改变" }
            }
            val key = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash("pdf-assembly-v1:${source.id}:${source.sha256}:$spec:$title")
            val existing = store.list(owner).firstOrNull { it.output && it.state == "SUCCEEDED" && it.requestKey == key }
            if (existing != null) {
                val evidence = verify(frozen, store.verifiedFile(owner, existing.id))
                check(Json.parseToJsonElement(existing.detail) == receipt(inputs, evidence)) { "PDF组页回执不匹配" }
                checkSources()
                return existing
            }
            return store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "$title.pdf", "pdf", output = true, sourceId = source.id, requestKey = key)) { target ->
                MaterialEditor.pdfPages(frozen, target)
                check(target.length() <= 30L * 1024 * 1024)
                // Compare an independently reopened output with each frozen physical source page.
                val evidence = verify(frozen, target)
                currentCoroutineContext().ensureActive()
                checkSources()
                receipt(inputs, evidence).toString()
            }
        } finally { snapshots.forEach { it.delete() } }
    }

    private fun receipt(inputs: List<Input>, evidence: JsonArray): JsonObject = buildJsonObject {
        put("contractVersion", 1); put("scope", "pdf_original_page_assembly")
        put("verification", "REOPENED_PAGE_GEOMETRY_TEXT_AND_RASTER_MATCH")
        put("raster_long_edge", EDGE); put("page_numbering", "pdf_physical_page_1_based")
        put("notice", "原件保留；原页组装非OCR/可编辑Word。逐页文字、几何和1024px渲染已回读比较；不保证文档级书签、链接、注释、表单、签名或任意缩放保真，不代表数学核验。")
        var index = 0
        put("pages", buildJsonArray { inputs.forEach { input -> input.pages.forEach { page ->
            add(buildJsonObject {
                put("output_physical_page", index + 1); put("source_id", input.item.id); put("source_sha256", input.item.sha256)
                put("source_physical_page", page); put("page_evidence", evidence[index++])
            })
        } } })
    }

    /** Also usable for a saved copy: page count alone is not acceptance evidence. */
    suspend fun verify(sources: List<Pair<File, List<Int>>>, output: File): JsonArray {
        require(sources.size in 1..20 && sources.all { it.second.isNotEmpty() } && sources.sumOf { it.second.size } in 1..200)
        val reopened = com.artifex.mupdf.fitz.Document.openDocument(output.absolutePath)
        try {
            check(!reopened.needsPassword() && reopened.countPages() == sources.sumOf { it.second.size }) { "PDF输出页数不匹配" }
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { rendered ->
                check(rendered.pageCount == reopened.countPages())
                var index = 0
                return buildJsonArray {
                    for ((file, pages) in sources) {
                        currentCoroutineContext().ensureActive()
                        val original = com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath)
                        try {
                            require(!original.needsPassword())
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { sourceFd -> PdfRenderer(sourceFd).use { sourceRender ->
                                for (number in pages) {
                                    currentCoroutineContext().ensureActive()
                                    require(number in 1..original.countPages())
                                    val expected = fingerprint(original, sourceRender, number - 1)
                                    val actual = fingerprint(reopened, rendered, index++)
                                    check(actual == expected) { "PDF输出物理页${index}与所选源页不一致" }
                                    add(actual)
                                }
                            } }
                        } finally { original.destroy() }
                    }
                }
            } }
        } finally { reopened.destroy() }
    }

    private fun fingerprint(document: com.artifex.mupdf.fitz.Document, renderer: PdfRenderer, index: Int): JsonObject {
        val page = document.loadPage(index)
        val textHash: String
        val bounds: JsonArray
        try {
            val box = page.bounds
            bounds = buildJsonArray { add(box.x0); add(box.y0); add(box.x1); add(box.y1) }
            val text = page.toStructuredText()
            try { textHash = MaterialSourceReceipt.hex(MessageDigest.getInstance("SHA-256").digest(text.asText().toByteArray(Charsets.UTF_8))) }
            finally { text.destroy() }
        } finally { page.destroy() }
        renderer.openPage(index).use { visual ->
            require(visual.width > 0 && visual.height > 0)
            val scale = EDGE.toDouble() / maxOf(visual.width, visual.height)
            val width = maxOf(1, (visual.width * scale).toInt()); val height = maxOf(1, (visual.height * scale).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                visual.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pixels = ByteBuffer.allocate(bitmap.byteCount); bitmap.copyPixelsToBuffer(pixels)
                return buildJsonObject {
                    put("bounds", bounds); put("text_sha256", textHash); put("raster_width", width); put("raster_height", height)
                    put("raster_sha256", MaterialSourceReceipt.hex(MessageDigest.getInstance("SHA-256").digest(pixels.array())))
                }
            } finally { bitmap.recycle() }
        }
    }
}
