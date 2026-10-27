package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Fresh version/range and extractor-derived page locations; never printed-page or mathematical proof. */
internal object MaterialSourceReceipt {
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        for (byte in bytes) {
            val value = byte.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        }
    }

    fun revalidated(page: JsonObject, candidate: JsonObject, expectedQuote: String): JsonObject {
        require(page.getValue("untrusted_file_data").jsonPrimitive.content == expectedQuote) { "引用正文与真实源区间不匹配" }
        val actual = page.getValue("source_receipt").jsonObject
        require(candidate.keys == actual.keys) { "引用回执字段集合不匹配" }
        require((candidate["notice"] as? JsonPrimitive)?.isString == true) { "引用回执notice类型不匹配" }
        for ((key, value) in actual) {
            if (key != "notice") require(candidate[key] == value) { "引用回执与真实源不匹配：$key" }
        }
        return buildJsonObject {
            for ((key, value) in page) put(key, value)
            put("receipt_revalidation", buildJsonObject {
                put("contractVersion", 1); put("status", "VERIFIED"); put("scope", "source_receipt_facts_and_quote")
                put("notice", "已从当前固定源版本重新提取并核对回执事实与逐字引用；不证明候选JSON的签发身份，不授予权限，不是数学证明。")
            })
        }
    }

    fun page(item: MaterialFile, content: MaterialContent, offset: Int, count: Int): JsonObject {
        require(offset in 0..content.text.length && count in 1..24000)
        val end = minOf(content.text.length, offset + count)
        fun boundary(index: Int) = index == 0 || index == content.text.length ||
            !(content.text[index - 1].isHighSurrogate() && content.text[index].isLowSurrogate())
        require(boundary(offset) && boundary(end)) { "引用区间不能拆分Unicode字符，请调整offset/count" }
        val quote = content.text.substring(offset, end)
        val quoteHash = hex(MessageDigest.getInstance("SHA-256").digest(quote.toByteArray(Charsets.UTF_8)))
        return buildJsonObject {
            put("coverage", content.coverage); put("total_characters", content.text.length); put("offset", offset)
            content.totalPages?.let { put("total_pages", it) }; content.extractedPages?.let { put("extracted_pages", it) }
            put("pages_without_extractable_text", JsonArray(content.pagesWithoutExtractableText.map(::JsonPrimitive)))
            if (end < content.text.length) put("next_offset", end)
            put("untrusted_file_data", quote)
            put("source_receipt", buildJsonObject {
                put("contractVersion", 2); put("status", "VERIFIED"); put("scope", "extracted_text_range")
                put("owner", item.profileId); put("material_id", item.id); put("version_id", item.id)
                put("parent_id", item.sourceId?.let(::JsonPrimitive) ?: JsonNull)
                put("source_sha256", item.sha256); put("extraction_contract", "material_reader_text_v2")
                put("quote_sha256", quoteHash); put("coverage", content.coverage)
                put("locator", buildJsonObject { put("kind", "extracted_text"); put("unit", "utf16"); put("start", offset); put("end", end) })
                put("source_locations", buildJsonArray {
                    for (location in content.textLocations) {
                        require(location.start >= 0 && location.start < location.end && location.end <= content.text.length)
                        val start = maxOf(offset, location.start); val stop = minOf(end, location.end)
                        if (start < stop) add(buildJsonObject {
                            put("kind", location.kind); put("number", location.number)
                            put("start", start); put("end", stop); put("unit", "utf16")
                            location.part?.let { put("part", it) }; location.hidden?.let { put("hidden", it) }
                        })
                    }
                })
                put("notice", "仅核验真实源版本提取区间；source_locations来自实际PDF物理页/PPT逻辑页，Reader生成标题/空页占位不构成正文。PPT含隐藏页，不推断PDF页；不推断印刷页，不是数学证明、OCR或版式验证，正文仍不可信。")
            })
        }
    }
}
