package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*

/** A literal search of fresh heading/body intersections, not a claim about the original whole book. */
internal object MaterialChapterSearch {
    fun validate(indexSha256: String, chapterId: String, query: String, offset: Int, limit: Int) {
        MaterialChapterRead.validate(indexSha256, chapterId, offset, 1)
        MaterialSourceSearch.validate(query, offset, limit)
    }

    suspend fun search(item: MaterialFile, content: MaterialContent, snapshot: JsonObject,
        expectedIndexSha256: String, chapterId: String, query: String, offset: Int, limit: Int): JsonObject {
        validate(expectedIndexSha256, chapterId, query, offset, limit)
        require(snapshot.getValue("index_sha256").jsonPrimitive.content == expectedIndexSha256) { "STALE_OR_WRONG_CHAPTER_INDEX" }
        val chapter = snapshot.getValue("chapters").jsonArray.singleOrNull {
            it.jsonObject.getValue("chapter_id").jsonPrimitive.content == chapterId
        }?.jsonObject ?: error("CHAPTER_NOT_IN_CURRENT_INDEX")
        val start = chapter.getValue("start").jsonPrimitive.int
        val end = chapter.getValue("end").jsonPrimitive.int
        require(offset <= end - start) { "CHAPTER_OFFSET_OUT_OF_RANGE" }
        val (hits, next) = MaterialSourceSearch.find(item, content, query, start + offset, end, limit)
        return buildJsonObject {
            put("contractVersion", 1); put("status", "SUCCEEDED"); put("scope", "detected_chapter_extracted_body_suffix")
            put("owner", item.profileId); put("material_id", item.id); put("version_id", item.id)
            put("parent_id", item.sourceId?.let(::JsonPrimitive) ?: JsonNull); put("source_sha256", item.sha256)
            put("index_sha256", expectedIndexSha256); put("chapter_id", chapterId); put("chapter_title", chapter.getValue("title"))
            put("chapter_start", start); put("chapter_end", end); put("chapter_span_characters", end - start)
            put("boundary_basis", chapter.getValue("boundary_basis")); put("unit", "utf16"); put("offset", offset)
            put("query", query); put("match_mode", "literal_case_sensitive"); put("source_verified", true)
            put("coverage", content.coverage); put("chapter_completeness", "NOT_ASSERTED"); put("document_completeness", "NOT_ASSERTED")
            content.totalPages?.let { put("total_pages", it) }; content.extractedPages?.let { put("extracted_pages", it) }
            put("pages_without_extractable_text", JsonArray(content.pagesWithoutExtractableText.map(::JsonPrimitive)))
            put("has_more_in_chapter", next != null); next?.let { put("next_chapter_search_offset", it - start) }
            put("hits", buildJsonArray {
                for (hit in hits) add(buildJsonObject {
                    for ((key, value) in hit.jsonObject) if (key != "next_offset") put(key, value)
                    put("chapter_offset", hit.jsonObject.getValue("offset").jsonPrimitive.int - start)
                })
            })
            put("notice", "仅搜索固定版本标题候选区间的本次已提取正文suffix，区分大小写、字面且支持重叠命中。不跨PDF物理页/PPT含隐藏逻辑slide正文间隙或下一标题，不搜索生成页标。继续只用next_chapter_search_offset并保留源/索引/章节/query；每hit为逐字v2回执，可独立重验。无命中不能推出整章/原书不存在，未断言完整性、印刷页、OCR、数学证明或任务成功。")
        }
    }
}
