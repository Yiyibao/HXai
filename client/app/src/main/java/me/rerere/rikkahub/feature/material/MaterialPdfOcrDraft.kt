package me.rerere.rikkahub.feature.material

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Editable recognition candidates plus real source-page images, never a lossless PDF conversion. */
object MaterialPdfOcrDraft {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val TEXT_LIMIT = 60000
    private data class ResultInput(val id: String, val digest: String, val metadata: JsonObject)
    private data class PageInput(val resultId: String, val blocks: List<ParsedBlock>)
    private data class Raster(val width: Int, val height: Int, val sha256: String)
    private fun hash(value: String) = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(value)
    private fun documentHash(value: ParsedDocument) = hash(Json.encodeToString(value))
    private fun children(root: Element) = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        currentCoroutineContext()[me.rerere.rikkahub.feature.agent.AgentToolDispatch]?.check()
    }
    private fun checkSource(store: MaterialFiles, owner: String, source: MaterialFile): File {
        val current = store.get(owner, source.id)
        check(current.profileId == owner && current.extension == "pdf" && current.sha256 == source.sha256 &&
            current.size == source.size && current.sourceId == source.sourceId) { "PDF源版本或谱系已改变" }
        return store.verifiedFile(owner, source.id)
    }
    private fun raster(bitmap: Bitmap): Raster {
        val bytes = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(bytes)
        return Raster(bitmap.width, bitmap.height, MaterialSourceReceipt.hex(MessageDigest.getInstance("SHA-256").digest(bytes.array())))
    }

    suspend fun edit(store: MaterialFiles, repository: DocumentServiceRepository, owner: String,
        source: MaterialFile, spec: JsonObject, title: String): MaterialFile {
        require(source.extension == "pdf" && spec.keys == setOf("task_ids", "start_page", "end_page"))
        fun integer(key: String) = spec.getValue(key).jsonPrimitive.also { require(!it.isString) }.int
        val start = integer("start_page"); val end = integer("end_page")
        require(start in 1..200 && end in start..200)
        val ids = spec.getValue("task_ids").jsonArray.map { it.jsonPrimitive.also { value -> require(value.isString) }.content }
        require(ids.size in 1..40 && ids.distinct().size == ids.size)
        val orderedIds = ids.sorted()
        val original = checkSource(store, owner, source)
        val frozen = File(original.parentFile, "${UUID.randomUUID()}.part")
        try {
            original.inputStream().use { input -> frozen.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) {
                    checkActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= source.size && total <= 30L * 1024 * 1024)
                    output.write(buffer, 0, count)
                }
                check(total == source.size)
            } }
            check(MaterialFiles.digest(frozen) == source.sha256) { "PDF源摘要改变" }
            val pdf = com.artifex.mupdf.fitz.Document.openDocument(frozen.absolutePath)
            val totalPages: Int
            try {
                require(pdf is com.artifex.mupdf.fitz.PDFDocument && !pdf.needsPassword())
                totalPages = pdf.countPages()
                require(totalPages in 1..200 && end <= totalPages)
            } finally { pdf.destroy() }
            val pageInputs = HashMap<Int, PageInput>()
            val inputs = ArrayList<ResultInput>()
            val warnings = LinkedHashSet<String>()
            var selectedCharacters = 0L
            for (id in orderedIds) {
                checkActive()
                val task = repository.tasks(owner).singleOrNull { it.id == id } ?: error("解析任务不属于当前档案")
                require(task.sourceId == source.id && task.sourceSha256 == source.sha256 && task.downloaded &&
                    !task.cancelRequested && task.state != "CANCELLING" && task.state != "CANCELLED") { "需要此源版本已下载、校验且未取消的解析结果；此操作不会上传或重试识别" }
                val document = repository.read(owner, id)
                require(document.totalPages == totalPages)
                val pages = document.processedPages.filter { it in start..end }
                require(pages.isNotEmpty()) { "解析任务没有覆盖所选源页" }
                val selected = document.blocks.filter { it.page in start..end }
                selectedCharacters += selected.sumOf { it.text.length.toLong() + if (it.latex != null && it.latex != it.text) it.latex.length else 0 }
                require(selectedCharacters <= TEXT_LIMIT) { "识别候选内容过长，请明确选择较小物理页范围" }
                warnings.addAll(document.warnings)
                require(warnings.sumOf { it.length.toLong() } <= TEXT_LIMIT)
                for (page in pages) {
                    require(page !in pageInputs) { "同一源页对应多个解析版本，请明确选用一个结果" }
                    pageInputs[page] = PageInput(id, selected.filter { it.page == page })
                }
                val digest = documentHash(document)
                inputs += ResultInput(id, digest, buildJsonObject {
                    put("task_id", id); put("canonical_document_sha256", digest); put("result_revision", document.resultRevision)
                    put("parser_version", document.parserVersion); put("adapter_version", document.adapterVersion)
                    put("requested_range", JsonArray(document.requestedRange.map(::JsonPrimitive)))
                    put("processed_pages", JsonArray(document.processedPages.map(::JsonPrimitive)))
                    put("failed_pages", JsonArray(document.failedPages.map(::JsonPrimitive)))
                    put("warnings", JsonArray(document.warnings.map(::JsonPrimitive)))
                })
            }
            val selectedPages = (start..end).toList()
            require(pageInputs.keys == selectedPages.toSet()) { "所选源页尚未全部识别；不会把缺页当空白或交付部分文件冒充完成" }
            val lines = ArrayList<String>(); var textLength = 0
            fun line(text: String) {
                for (part in text.lineSequence()) {
                    textLength += part.length + 1
                    require(textLength <= TEXT_LIMIT) { "识别候选及出处超过Word内容限额，请明确选择较小物理页范围" }
                    lines += part
                }
            }
            line("OCR 可编辑候选稿（非原版保真、非已核验数学）")
            line("源材料版本 ${source.id}；SHA256 ${source.sha256}")
            line("源PDF共${totalPages}物理页；本稿仅包含物理页${start}–${end}。Word自身分页不是原PDF页码。")
            line("文字/表格为识别候选；公式LaTeX保留为可编辑文字而非已核验OMML。所有符号需对照原页，存在歧义应先向用户确认。原页参考图不是可编辑公式/图形。")
            warnings.forEach { line("解析警告：$it") }
            line("表格仅为可编辑候选文字，未还原Word表格/合并单元格；请核对对应原页参考图。")
            val imageParagraphs = ArrayList<Int>()
            val pagesWithoutText = ArrayList<Int>()
            val symbols = ArrayList<String>()
            for (page in selectedPages) {
                val input = pageInputs.getValue(page)
                line("源PDF物理页 $page；解析结果 ${input.resultId}")
                if (input.blocks.none { it.text.isNotBlank() || !it.latex.isNullOrBlank() }) {
                    pagesWithoutText += page
                    line("本页没有可编辑识别文字，可能为空白、图像或未识别；不能据此认定原页为空。请核对下方原页参考图。")
                }
                for (block in input.blocks) {
                    line("来源块 ${block.blockId}；类型 ${block.type}；源PDF物理页 $page")
                    if (block.type == "equation" || block.latex != null) {
                        symbols += block.blockId
                        line("公式/符号候选，未核对原页、未进行数学证明：")
                    }
                    line(block.text)
                    if (block.latex != null && block.latex != block.text) line("LaTeX候选：${block.latex}")
                    if (block.assetId != null || block.type == "image" || block.type == "image_body" || block.type == "chart") line("图像/图形保留在本页原图参考中；未转换为可编辑图形。")
                }
                line("以下参考图对应源PDF物理页 $page（最长边1536px；完整原件仍保留）：")
                imageParagraphs += lines.lastIndex
            }
            val body = lines.joinToString("\n")
            val resultMetadata = JsonArray(inputs.map { it.metadata })
            val key = hash("pdf-ocr-draft-v1:${source.id}:${source.sha256}:$start:$end:$resultMetadata:$title")
            val existing = store.list(owner).firstOrNull { it.output && it.state == "SUCCEEDED" && it.requestKey == key }
            val existingFile = existing?.let {
                check(it.sourceId == source.id && it.extension == "docx")
                store.verifiedFile(owner, it.id)
            }
            val pictures = if (existing == null) ArrayList<ByteArray>() else null
            val expectedRasters = ArrayList<Raster>(); var pictureBytes = 0L
            ParcelFileDescriptor.open(frozen, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount == totalPages)
                for (page in selectedPages) {
                    checkActive()
                    renderer.openPage(page - 1).use { originalPage ->
                        val bitmap = renderPdfPageBitmap(originalPage)
                        try {
                            expectedRasters += raster(bitmap)
                            if (pictures != null) {
                                val encoded = ByteArrayOutputStream()
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
                                pictureBytes += encoded.size(); require(pictureBytes <= 16L * 1024 * 1024) { "原页参考图超过16MiB，请选择较小物理页范围" }
                                pictures += encoded.toByteArray()
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            } }
            suspend fun recheckInputs() {
                checkActive(); checkSource(store, owner, source)
                for (input in inputs) {
                    checkActive()
                    val task = repository.tasks(owner).singleOrNull { it.id == input.id } ?: error("解析任务已移除")
                    check(task.sourceId == source.id && task.sourceSha256 == source.sha256 && task.downloaded &&
                        !task.cancelRequested && task.state != "CANCELLING" && task.state != "CANCELLED") { "解析结果已取消，未发布或复用新候选稿" }
                    check(documentHash(repository.read(owner, input.id)) == input.digest) { "解析结果版本已改变" }
                }
            }
            fun receipt(file: File): JsonObject {
                val images = verify(file, lines, imageParagraphs, selectedPages, expectedRasters)
                return buildJsonObject {
                    put("contractVersion", 1); put("scope", "pdf_ocr_editable_draft")
                    put("source_id", source.id); put("source_sha256", source.sha256); put("source_total_pages", totalPages)
                    put("source_page_basis", "pdf_physical_page_1_based"); put("selected_source_pages", JsonArray(selectedPages.map(::JsonPrimitive)))
                    put("entire_source_selected", start == 1 && end == totalPages)
                    put("recognition_correctness", "UNVERIFIED"); put("editable_content_coverage", "OCR_BLOCKS_ONLY")
                    put("math_verification", "NOT_PERFORMED"); put("layout", "NOT_VERIFIED")
                    put("pages_without_editable_text", JsonArray(pagesWithoutText.map(::JsonPrimitive)))
                    put("symbols_need_review", JsonArray(symbols.map(::JsonPrimitive)))
                    put("parse_results", resultMetadata); put("reference_images", images)
                    put("table_representation", "EDITABLE_TEXT_NOT_WORD_TABLES")
                    put("formula_representation", "UNVERIFIED_EDITABLE_LATEX_TEXT_NOT_OMML")
                    put("notice", "已核对所选页的识别候选、出处和真实原页参考图；不代表OCR或公式正确，不是原PDF保真重建。歧义须向用户确认；未选页未转换。")
                }
            }
            if (existing != null) {
                check(Json.parseToJsonElement(existing.detail) == receipt(checkNotNull(existingFile))) { "扫描候选稿回执不匹配" }
                recheckInputs()
                return existing
            }
            recheckInputs()
            return store.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "$title.docx", "docx", output = true, sourceId = source.id, requestKey = key)) { target ->
                MaterialWriter.write(target, "docx", body, emptyList(), emptyList(), images = checkNotNull(pictures), imageParagraphs = imageParagraphs)
                require(target.length() <= 30L * 1024 * 1024)
                val proof = receipt(target)
                recheckInputs()
                proof.toString()
            }
        } finally { frozen.delete() }
    }

    private fun verify(file: File, lines: List<String>, imageParagraphs: List<Int>, pages: List<Int>, rasters: List<Raster>): JsonArray {
        val parts = OfficePackage.read(file)
        val root = SafeOfficeXml.parse(parts.getValue("word/document.xml"))
        val body = root.getElementsByTagNameNS(W, "body").item(0) as Element
        val relations = SafeOfficeXml.parse(parts.getValue("word/_rels/document.xml.rels"))
        val relationships = children(relations).associateBy { it.getAttribute("Id") }
        var textIndex = 0; var imageIndex = 0
        val images = buildJsonArray {
            for (paragraph in children(body)) {
                if (paragraph.namespaceURI == W && paragraph.localName == "sectPr") continue
                check(paragraph.namespaceURI == W && paragraph.localName == "p")
                val drawings = paragraph.getElementsByTagNameNS(W, "drawing")
                val texts = paragraph.getElementsByTagNameNS(W, "t")
                if (drawings.length == 0) {
                    check(textIndex < lines.size)
                    val text = buildString { for (index in 0 until texts.length) append(texts.item(index).textContent) }
                    check(text == lines[textIndex++]) { "扫描候选稿文字回读不匹配" }
                } else {
                    check(drawings.length == 1 && texts.length == 0 && imageIndex < pages.size && textIndex - 1 == imageParagraphs[imageIndex])
                    val blips = paragraph.getElementsByTagNameNS(A, "blip")
                    check(blips.length == 1)
                    val id = (blips.item(0) as Element).getAttributeNS(R, "embed")
                    val relation = relationships.getValue(id)
                    val target = "media/image${imageIndex + 1}.png"
                    check(id == "img${imageIndex + 1}" && relation.getAttribute("Type") == "$R/image" &&
                        relation.getAttribute("Target") == target && relation.getAttribute("TargetMode") in setOf("", "Internal"))
                    val bytes = parts.getValue("word/$target")
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法解码原页参考图")
                    val actual = try { raster(bitmap) } finally { bitmap.recycle() }
                    check(actual == rasters[imageIndex]) { "原页参考图与真实PDF物理页不一致" }
                    add(buildJsonObject {
                        put("source_physical_page", pages[imageIndex]); put("image_part", "word/$target")
                        put("png_sha256", MaterialSourceReceipt.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                        put("width", actual.width); put("height", actual.height); put("pixel_sha256", actual.sha256)
                    })
                    imageIndex++
                }
            }
        }
        check(textIndex == lines.size && imageIndex == pages.size) { "扫描候选稿文字或原页图片不完整" }
        return images
    }
}
