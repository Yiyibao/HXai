package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Detected heading sections in extracted text, never an authoritative textbook table of contents. */
internal object MaterialChapterIndex {
    const val CACHE_LIMIT = 2L * 1024 * 1024
    private const val MAX_HEADINGS = 512
    private val heading = Regex("(?:#{1,6}[ \\t]+\\S.*|第[0-9一二三四五六七八九十百千万零〇两]+[章篇部].*|(?i:chapter)[ \\t]+[0-9IVXLCDMivxlcdm]+(?:[ .:：—–-]+.*)?)")
    private data class Heading(val start: Int, val end: Int)
    data class Body(val start: Int, val end: Int)

    fun bodies(item: MaterialFile, content: MaterialContent): List<Body> {
        require(item.extension in setOf("pdf", "pptx", "docx", "txt", "md")) { "UNSUPPORTED_CHAPTER_FORMAT" }
        if (item.extension != "pdf" && item.extension != "pptx") return if (content.text.isEmpty()) emptyList() else listOf(Body(0, content.text.length))
        val kind = if (item.extension == "pdf") "pdf_physical_page" else "ppt_logical_slide"
        var previous = 0
        return content.textLocations.map { location ->
            require(location.kind == kind && location.number > 0 && location.start >= previous &&
                location.start < location.end && location.end <= content.text.length) { "INVALID_EXTRACTED_BODY_RANGE" }
            previous = location.end
            Body(location.start, location.end)
        }
    }

    suspend fun build(item: MaterialFile, content: MaterialContent): JsonObject {
        val ranges = bodies(item, content)
        val headings = ArrayList<Heading>()
        scan@ for (range in ranges) {
            var offset = range.start
            while (offset < range.end) {
                currentCoroutineContext().ensureActive()
                val newline = content.text.indexOf('\n', offset).let { if (it < 0) range.end else minOf(it, range.end) }
                var start = offset; var end = newline
                while (start < end && content.text[start].isWhitespace()) start++
                while (end > start && content.text[end - 1].isWhitespace()) end--
                if (end - start in 1..256 && heading.matches(content.text.substring(start, end))) {
                    headings += Heading(start, end)
                    if (headings.size > MAX_HEADINGS) break@scan
                }
                offset = newline + 1
            }
        }
        val retained = minOf(MAX_HEADINGS, headings.size)
        val snapshot = buildJsonObject {
            put("contractVersion", 1); put("scope", "detected_heading_sections_in_extracted_text")
            put("owner", item.profileId); put("material_id", item.id); put("version_id", item.id)
            put("parent_id", item.sourceId?.let(::JsonPrimitive) ?: JsonNull); put("source_sha256", item.sha256)
            put("extension", item.extension); put("extraction_contract", "material_reader_text_v2")
            put("heading_rules", "markdown_atx_chinese_chapter_english_chapter_v1")
            put("extracted_text_sha256", sha(content.text)); put("extracted_characters", content.text.length)
            put("coverage", content.coverage); put("document_completeness", "NOT_ASSERTED")
            content.totalPages?.let { put("total_pages", it) }; content.extractedPages?.let { put("extracted_pages", it) }
            put("pages_without_extractable_text", JsonArray(content.pagesWithoutExtractableText.map(::JsonPrimitive)))
            put("body_ranges", buildJsonArray { ranges.forEach { add(buildJsonObject { put("start", it.start); put("end", it.end) }) } })
            put("chapters_retained", retained); put("index_truncated", headings.size > MAX_HEADINGS)
            if (headings.size > MAX_HEADINGS) put("unindexed_tail_start", headings[MAX_HEADINGS].start)
            put("unindexed_prefix_end", headings.firstOrNull()?.start ?: content.text.length)
            put("chapters", buildJsonArray {
                for (index in 0 until retained) {
                    val found = headings[index]
                    add(buildJsonObject {
                        put("chapter_id", "heading-${found.start}"); put("title", content.text.substring(found.start, found.end))
                        put("start", found.start); put("end", headings.getOrNull(index + 1)?.start ?: content.text.length); put("unit", "utf16")
                        put("boundary_basis", if (index + 1 < headings.size) "NEXT_DETECTED_HEADING" else "EXTRACTED_TEXT_END")
                        put("structure_authority", "HEADING_CANDIDATE_ONLY")
                        put("heading_evidence", MaterialSourceReceipt.page(item, content, found.start, found.end - found.start))
                    })
                }
            })
            put("notice", "仅按真实提取正文的标题行生成候选区间；目录行、重复标题不合并，不证明语义章节或全文完整。PDF为物理页，PPT为含隐藏逻辑slide；不猜印刷页、不OCR、不授权限或数学结论。索引分页位置不证明前页已读。")
        }
        return JsonObject(snapshot + ("index_sha256" to JsonPrimitive(sha(snapshot.toString()))))
    }

    private fun sha(text: String) = MaterialSourceReceipt.hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /** Derived storage is never authority: callers must build from a fresh verified frozen source first. */
    @Synchronized fun persist(file: File, snapshot: JsonObject): String {
        val canonical = snapshot.toString()
        require(canonical.toByteArray(Charsets.UTF_8).size <= CACHE_LIMIT) { "CHAPTER_INDEX_TOO_LARGE" }
        val existed = file.exists()
        if (existed && file.length() <= CACHE_LIMIT && file.readText(Charsets.UTF_8) == canonical) return "REVALIDATED"
        me.rerere.rikkahub.data.sync.PendingRestore.writeDurably(file, canonical)
        check(file.length() <= CACHE_LIMIT && file.readText(Charsets.UTF_8) == canonical) { "CHAPTER_INDEX_READBACK_FAILED" }
        return if (existed) "REBUILT_FROM_VERIFIED_SOURCE" else "CREATED"
    }

    @Synchronized fun invalidate(file: File) {
        for (target in listOf(file, File(file.path + ".tmp"))) check(target.delete() || !target.exists()) { "CHAPTER_INDEX_INVALIDATION_FAILED" }
    }

    fun page(snapshot: JsonObject, offset: Int, limit: Int, storage: String): JsonObject {
        val chapters = snapshot.getValue("chapters").jsonArray
        require(offset in 0..chapters.size && limit in 1..20) { "INVALID_CHAPTER_INDEX_PAGE" }
        val end = minOf(chapters.size, offset + limit)
        return buildJsonObject {
            for ((key, value) in snapshot) if (key != "chapters") put(key, value)
            put("status", "SUCCEEDED"); put("source_verified", true); put("index_storage", storage)
            put("index_offset", offset); put("chapters", JsonArray(chapters.subList(offset, end)))
            put("has_more", end < chapters.size); if (end < chapters.size) put("next_index_offset", end)
        }
    }
}
