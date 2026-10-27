package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Location numbers come only from the frozen source's extractor, never text that resembles a page label. */
internal object MaterialSourceLocationRead {
    class Rejected(val code: String) : IllegalArgumentException(code)
    val fields = setOf("id", "expected_sha256", "kind", "number", "offset", "count")

    fun validate(kind: String, number: Int, offset: Int, count: Int) {
        if (kind != "pdf_physical_page" && kind != "ppt_logical_slide") throw Rejected("UNSUPPORTED_LOCATION_KIND")
        if (number < 1 || offset !in 0..MaterialReader.LIMIT || count !in 1..24000) throw Rejected("INVALID_LOCATION_RANGE")
    }

    fun page(item: MaterialFile, content: MaterialContent, kind: String, number: Int, offset: Int, count: Int): JsonObject {
        if ((kind == "pdf_physical_page" && item.extension != "pdf") ||
            (kind == "ppt_logical_slide" && item.extension != "pptx")) throw Rejected("LOCATION_FORMAT_MISMATCH")
        val location = content.textLocations.singleOrNull { it.kind == kind && it.number == number }
        if (location == null) return buildJsonObject {
            put("contractVersion", 1); put("status", "NOT_AVAILABLE"); put("scope", "source_location_extracted_body")
            put("reason", "NO_EXTRACTED_BODY_FOR_LOCATION"); put("source_verified", true)
            put("owner", item.profileId); put("material_id", item.id); put("version_id", item.id)
            put("source_sha256", item.sha256); put("parent_id", item.sourceId?.let(::JsonPrimitive) ?: JsonNull)
            put("location_kind", kind); put("location_number", number); put("coverage", content.coverage)
            content.totalPages?.let { put("total_pages", it) }; content.extractedPages?.let { put("extracted_pages", it) }
            put("pages_without_extractable_text", JsonArray(content.pagesWithoutExtractableText.map(::JsonPrimitive)))
            put("document_completeness", "NOT_ASSERTED")
            put("notice", "本次提取未提供该位置正文，可能无可提取字、超出提取范围或不存在；不推断原页为空，不签发正文引用，不自动OCR或外传。")
        }
        check(location.start >= 0 && location.start < location.end && location.end <= content.text.length)
        val length = location.end - location.start
        if (offset >= length) throw Rejected("LOCATION_OFFSET_OUT_OF_RANGE")
        val size = minOf(count, length - offset)
        val next = offset + size
        val page = MaterialSourceReceipt.page(item, content, location.start + offset, size)
        return buildJsonObject {
            for ((key, value) in page) if (key != "next_offset") put(key, value)
            put("contractVersion", 1); put("status", "SUCCEEDED"); put("scope", "source_location_extracted_body")
            put("source_verified", true); put("location_kind", kind); put("location_number", number)
            put("location_offset", offset); put("location_extracted_characters", length)
            put("has_more_in_location", next < length)
            if (next < length) put("next_location_offset", next)
            put("document_completeness", "NOT_ASSERTED")
            put("notice", "仅读取指定PDF物理页/PPT含隐藏页的逻辑slide已提取正文，不跨页且不含生成标题；offset/next_location_offset为页内UTF16，v2回执locator为全局UTF16，可独立重验。到末尾仅表示已提取正文末尾，不证明原页完整、印刷页、OCR、版式或数学结论。")
        }
    }
}

/** Registration and local.read authorization remain owned by the shared Agent host. */
fun materialSourceLocationTool(store: MaterialFiles, owner: String): Tool = Tool(
    name = "read_material_source_location",
    description = "从固定档案真实版本按PDF物理页或PPT含隐藏页的逻辑slide读取有界正文，保原件。六个严格字符串id/expected_sha256/kind/number/offset/count；kind仅pdf_physical_page或ppt_logical_slide，number从1起，offset为页内UTF16从0起，count1–24000且不拆Unicode字符。只读取实际提取位置，不猜印刷页/可见页/导出PDF页，不跨页。返回逐字正文和可重验v2来源回执；同页续读仅用next_location_offset。空页/未提取/不存在的位置NOT_AVAILABLE且无正文回执，不代表原页为空；不自动OCR/外传。不是版式验证/数学证明/整文覆盖。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        for (key in MaterialSourceLocationRead.fields) put(key, buildJsonObject { put("type", "string") })
    }, required = MaterialSourceLocationRead.fields.toList()) },
    execute = { args -> withContext(Dispatchers.IO) {
        try {
            val input = args.jsonObject
            require(input.keys == MaterialSourceLocationRead.fields)
            fun value(key: String) = input.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val page = store.readSourceLocation(owner, value("id"), value("expected_sha256"), value("kind"),
                value("number").toInt(), value("offset").toInt(), value("count").toInt())
            listOf(UIMessagePart.Text(page.toString()))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "FAILED"); put("reason", (e as? MaterialSourceLocationRead.Rejected)?.code ?: "SOURCE_LOCATION_READ_REJECTED")
                put("error", "材料版本、来源位置或字符范围无法核验；请核对原材料与页内范围。")
            }.toString()))
        }
    } },
)
