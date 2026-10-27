package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal object MaterialSourceSearch {
    fun validate(query: String, offset: Int, limit: Int) {
        require(query.length in 1..256) { "检索字面串必须为1–256个UTF16单位" }
        require(offset in 0..MaterialReader.LIMIT && limit in 1..20) { "检索offset/limit超出允许范围" }
        var i = 0
        while (i < query.length) {
            val ch = query[i]
            if (ch.isHighSurrogate()) {
                require(i + 1 < query.length && query[i + 1].isLowSurrogate()) { "检索串必须为完整Unicode字符" }
                i += 2
            } else {
                require(!ch.isLowSurrogate()) { "检索串必须为完整Unicode字符" }; i++
            }
        }
    }

    data class Matches(val hits: List<JsonElement>, val next: Int?)

    /** Both bounds are extraction coordinates; matching never bridges physical body ranges. */
    suspend fun find(item: MaterialFile, content: MaterialContent, query: String, offset: Int, end: Int, limit: Int): Matches {
        validate(query, offset, limit)
        val text = content.text
        require(offset <= end && end <= text.length) { "检索区间超出实际提取文本" }
        fun boundary(index: Int) = index == 0 || index == text.length ||
            !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
        require(boundary(offset) && boundary(end)) { "检索区间不能拆分Unicode字符" }
        val prefix = IntArray(query.length)
        var matched = 0
        for (i in 1 until query.length) {
            while (matched > 0 && query[i] != query[matched]) matched = prefix[matched - 1]
            if (query[i] == query[matched]) matched++
            prefix[i] = matched
        }
        val hits = mutableListOf<JsonElement>()
        var next: Int? = null
        suspend fun scan(start: Int, end: Int): Boolean {
            currentCoroutineContext().ensureActive()
            var length = 0
            for (i in start until end) {
                if (i and 4095 == 0) currentCoroutineContext().ensureActive()
                while (length > 0 && text[i] != query[length]) length = prefix[length - 1]
                if (text[i] == query[length]) length++
                if (length == query.length) {
                    val found = i - query.length + 1
                    if (hits.size == limit) { next = found; return false }
                    hits += MaterialSourceReceipt.page(item, content, found, query.length)
                    length = prefix[length - 1]
                }
            }
            return true
        }
        if (item.extension == "pdf" || item.extension == "pptx") {
            for (location in content.textLocations) {
                if (location.end > offset && location.start < end &&
                    !scan(maxOf(offset, location.start), minOf(end, location.end))) break
            }
        } else scan(offset, end)
        return Matches(hits, next)
    }

    suspend fun search(item: MaterialFile, content: MaterialContent, query: String, offset: Int, limit: Int): JsonObject {
        val (hits, next) = find(item, content, query, offset, content.text.length, limit)
        return buildJsonObject {
            put("contractVersion", 1); put("status", "SUCCEEDED")
            put("scope", "extracted_body_suffix"); put("match_mode", "literal_case_sensitive")
            put("owner", item.profileId); put("material_id", item.id); put("version_id", item.id)
            put("parent_id", item.sourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("source_sha256", item.sha256); put("extraction_contract", "material_reader_text_v2")
            put("coverage", content.coverage); put("extracted_characters", content.text.length); put("offset", offset)
            content.totalPages?.let { put("total_pages", it) }; content.extractedPages?.let { put("extracted_pages", it) }
            put("pages_without_extractable_text", JsonArray(content.pagesWithoutExtractableText.map(::JsonPrimitive)))
            put("document_completeness", "NOT_ASSERTED"); put("has_more", next != null)
            next?.let { put("next_search_offset", it) }
            put("hits", JsonArray(hits))
            put("notice", "仅搜索本次已提取正文suffix，未命中不能推断整份原文无此内容。PDF/PPT生成标题与空页提示不搜索；其他格式为现提取文本。hits为逐字引用页证据，继续检索只用next_search_offset，不用hit.next_offset；不是数学证明、OCR或印刷页定位。")
        }
    }
}

/** Sol registers this constructor under the existing trusted local.read policy. */
fun materialSourceSearchTool(store: MaterialFiles, owner: String): Tool = Tool(
    name = "search_material_text",
    description = "在固定档案的真实材料版本正文中精确检索字面串，区分大小写、支持重叠命中，不是正则。id/expected_sha256/query/offset/limit均为字符串；摘要来自所选不可变版本，query为1–256个完整UTF16单位，offset为提取文本起点，limit为1–20命中。实际冻结源并核对SHA，不用cache。每hit返回逐字引用及v2 source_receipt，PDF物理页/PPT逻辑页来自真实正文结构，排除生成页标题/空页提示。继续仅用next_search_offset，保留版本/摘要/query。覆盖受Reader文件/1M字符/PDF200页上限及扫描缺文限制；无命中只表示已提取正文suffix没有匹配，不是完整文档结论。无授权/模型/数学证明含义。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        listOf("id", "expected_sha256", "query", "offset", "limit").forEach { put(it, buildJsonObject { put("type", "string") }) }
    }, required = listOf("id", "expected_sha256", "query", "offset", "limit")) },
    execute = { args -> withContext(Dispatchers.IO) {
        try {
            fun value(key: String) = args.jsonObject.getValue(key).jsonPrimitive.also { require(it.isString) { "${key}必须为字符串" } }.content
            listOf(UIMessagePart.Text(store.searchSourceText(owner, value("id"), value("expected_sha256"), value("query"), value("offset").toInt(), value("limit").toInt()).toString()))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject { put("status", "FAILED"); put("error", e.message?.take(160) ?: "材料正文检索失败") }.toString()))
        }
    } },
)
