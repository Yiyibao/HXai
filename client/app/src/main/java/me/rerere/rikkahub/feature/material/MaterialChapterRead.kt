package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*

/** A chunk of a freshly reconstructed heading section; generated page labels are not source body. */
internal object MaterialChapterRead {
    private val chapterIdPattern = Regex("heading-[0-9]{1,7}")
    fun validate(indexSha256: String, chapterId: String, offset: Int, count: Int) {
        require(indexSha256.length == 64 && indexSha256.all { it in '0'..'9' || it in 'a'..'f' }) { "INVALID_CHAPTER_INDEX_HASH" }
        require(chapterIdPattern.matches(chapterId) && offset in 0..MaterialReader.LIMIT && count in 1..24000) { "INVALID_CHAPTER_RANGE" }
    }

    fun page(item: MaterialFile, content: MaterialContent, snapshot: JsonObject,
        expectedIndexSha256: String, chapterId: String, offset: Int, count: Int): JsonObject {
        validate(expectedIndexSha256, chapterId, offset, count)
        require(snapshot.getValue("index_sha256").jsonPrimitive.content == expectedIndexSha256) { "STALE_OR_WRONG_CHAPTER_INDEX" }
        val chapter = snapshot.getValue("chapters").jsonArray.singleOrNull { it.jsonObject.getValue("chapter_id").jsonPrimitive.content == chapterId }?.jsonObject
            ?: error("CHAPTER_NOT_IN_CURRENT_INDEX")
        val start = chapter.getValue("start").jsonPrimitive.int
        val end = chapter.getValue("end").jsonPrimitive.int
        require(offset <= end - start) { "CHAPTER_OFFSET_OUT_OF_RANGE" }
        val bodies = MaterialChapterIndex.bodies(item, content)
        val requested = start + offset
        val body = bodies.firstOrNull { it.end > requested && it.start < end }
        fun JsonObjectBuilder.identity() {
            put("contractVersion", 1); put("scope", "detected_chapter_extracted_body_chunk")
            put("owner", item.profileId); put("material_id", item.id); put("version_id", item.id); put("source_sha256", item.sha256)
            put("index_sha256", expectedIndexSha256); put("chapter_id", chapterId); put("chapter_title", chapter.getValue("title"))
            put("chapter_start", start); put("chapter_end", end); put("unit", "utf16")
            put("chapter_span_characters", end - start); put("requested_chapter_offset", offset)
            put("boundary_basis", chapter.getValue("boundary_basis")); put("chapter_completeness", "NOT_ASSERTED")
            put("document_completeness", "NOT_ASSERTED"); put("source_verified", true)
        }
        if (requested == end || body == null) return buildJsonObject {
            identity(); put("status", "NOT_AVAILABLE"); put("reason", "NO_REMAINING_EXTRACTED_CHAPTER_BODY")
            put("coverage", content.coverage); put("has_more_in_chapter", false)
            put("notice", "该候选区间本次提取没有剩余正文；不推断原章节为空或完整，不签发正文回执。")
        }
        val actualStart = maxOf(requested, body.start)
        val actualEnd = minOf(end, body.end, actualStart + count)
        val page = MaterialSourceReceipt.page(item, content, actualStart, actualEnd - actualStart)
        val more = actualEnd < end && bodies.any { it.end > actualEnd && it.start < end }
        return buildJsonObject {
            for ((key, value) in page) if (key != "next_offset") put(key, value)
            identity(); put("status", "SUCCEEDED"); put("chapter_offset", actualStart - start)
            put("has_more_in_chapter", more); if (more) put("next_chapter_offset", actualEnd - start)
            put("notice", "仅读取标题候选区间的真实已提取正文，单次不跨PDF物理页/PPT逻辑slide；跳过生成页标与无正文间隙，实际chapter_offset可能大于请求offset。span是提取坐标跨度，不是原章节字数。到末尾不证明原文完整、印刷页、OCR、结构权威或数学结论；v2回执仍可按全局locator独立重验。")
        }
    }
}
