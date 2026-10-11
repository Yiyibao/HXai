package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.*

/** Streams every result field in order, including metadata that can itself exceed the budget. */
internal object DocumentBlockPagination {
    const val RESPONSE_LIMIT = 24_000
    private data class Position(val block: Int, val field: String, val offset: Int)

    fun page(document: ParsedDocument, sourceId: String, taskId: String, cursor: String, count: Int): String {
        require(count in 1..20) { "count 必须为 1–20" }
        fun encode(position: Position) = buildJsonObject {
            put("version", 1); put("task_id", taskId); put("source_sha256", document.sourceSha256)
            put("result_revision", document.resultRevision); put("block_index", position.block)
            put("field", position.field); put("char_offset", position.offset)
        }.toString()
        var position: Position? = if (cursor.isEmpty()) Position(-1, "metadata", 0) else {
            val value = Json.parseToJsonElement(cursor).jsonObject
            require(value.keys == setOf("version", "task_id", "source_sha256", "result_revision", "block_index", "field", "char_offset")) { "继续游标格式无效" }
            require(value.getValue("version").jsonPrimitive.int == 1 &&
                value.getValue("task_id").jsonPrimitive.content == taskId &&
                value.getValue("source_sha256").jsonPrimitive.content == document.sourceSha256 &&
                value.getValue("result_revision").jsonPrimitive.int == document.resultRevision) { "继续游标不属于当前任务或结果版本" }
            Position(value.getValue("block_index").jsonPrimitive.int, value.getValue("field").jsonPrimitive.content,
                value.getValue("char_offset").jsonPrimitive.int)
        }
        val metadata = mutableListOf<JsonElement>()
        val blocks = mutableListOf<JsonElement>()
        fun response(next: String?) = buildJsonObject {
            put("task_id", taskId); put("source_id", sourceId); put("source_sha256", document.sourceSha256)
            put("result_revision", document.resultRevision); put("total_blocks", document.blocks.size)
            put("offset_unit", "UTF-16")
            put("untrusted_metadata", JsonArray(metadata)); put("untrusted_blocks", JsonArray(blocks))
            next?.let { put("next_cursor", it) }
        }
        // Reserve the largest possible cursor before filling either array, including JSON escaping.
        var remaining = RESPONSE_LIMIT - response(encode(Position(Int.MAX_VALUE, "metadata", Int.MAX_VALUE))).toString().length
        require(remaining > 0) { "任务标识超出响应预算" }
        val firstBlock = maxOf(0, position!!.block)
        val blockLimit = minOf(document.blocks.size, firstBlock + count)
        while (position != null) {
            val current = position
            require(current.block == -1 || current.block in document.blocks.indices) { "继续游标块位置无效" }
            if (current.block >= blockLimit) break
            val content = fieldContent(document, current)
            require(current.offset in 0..content.length && !splitsSurrogate(content, current.offset)) { "继续游标字符位置无效" }
            val target = if (current.block == -1) metadata else blocks
            fun fragment(end: Int) = buildJsonObject {
                if (current.block >= 0) put("block_index", current.block)
                put("field", current.field); put("char_offset", current.offset); put("total_chars", content.length)
                put("content", content.substring(current.offset, end))
            }
            val overhead = fragment(current.offset).toString().length + if (target.isEmpty()) 0 else 1
            val end = prefixEnd(content, current.offset, remaining - overhead)
            if (remaining < overhead || (end == current.offset && end < content.length)) break
            val delivered = fragment(end)
            remaining -= delivered.toString().length + if (target.isEmpty()) 0 else 1
            target += delivered
            position = if (end < content.length) current.copy(offset = end) else next(document, current)
            if (end < content.length) break
        }
        check(metadata.isNotEmpty() || blocks.isNotEmpty()) { "响应预算不足以读取下一个字符" }
        return response(position?.let(::encode)).toString()
    }

    private fun fieldContent(document: ParsedDocument, position: Position): String {
        if (position.block == -1) {
            require(position.field == "metadata") { "继续游标字段无效" }
            return buildJsonObject {
                put("contract_version", document.contractVersion); put("tier", document.tier)
                put("parser_version", document.parserVersion); put("adapter_version", document.adapterVersion)
                put("complete", document.complete); put("total_pages", document.totalPages)
                put("requested_range", JsonArray(document.requestedRange.map(::JsonPrimitive)))
                put("processed_pages", JsonArray(document.processedPages.map(::JsonPrimitive)))
                put("blank_pages", JsonArray(document.blankPages.map(::JsonPrimitive)))
                put("failed_pages", JsonArray(document.failedPages.map(::JsonPrimitive)))
                put("warnings", JsonArray(document.warnings.map(::JsonPrimitive)))
            }.toString()
        }
        val block = document.blocks[position.block]
        return when (position.field) {
            "metadata" -> buildJsonObject {
                put("blockId", block.blockId); put("page", block.page); put("type", block.type)
                block.assetId?.let { put("assetId", it) }
            }.toString()
            "text" -> block.text
            "latex" -> requireNotNull(block.latex) { "继续游标字段无效" }
            else -> error("继续游标字段无效")
        }
    }

    private fun next(document: ParsedDocument, position: Position): Position? = when {
        position.block == -1 -> if (document.blocks.isEmpty()) null else Position(0, "metadata", 0)
        position.field == "metadata" -> Position(position.block, "text", 0)
        position.field == "text" && document.blocks[position.block].latex != null -> Position(position.block, "latex", 0)
        position.block + 1 < document.blocks.size -> Position(position.block + 1, "metadata", 0)
        else -> null
    }

    private fun splitsSurrogate(value: String, offset: Int) = offset > 0 && offset < value.length &&
        value[offset - 1].isHighSurrogate() && value[offset].isLowSurrogate()

    /** JSON string size without its quotes; stop before a Unicode code point would be split. */
    private fun prefixEnd(value: String, offset: Int, budget: Int): Int {
        var end = offset
        var remaining = budget
        while (end < value.length) {
            val character = value[end]
            val pair = character.isHighSurrogate() && end + 1 < value.length && value[end + 1].isLowSurrogate()
            val cost = when (character) {
                '"', '\\', '\b', '\t', '\n', '\u000C', '\r' -> 2
                in '\u0000'..'\u001F' -> 6
                else -> if (pair) 2 else 1
            }
            if (cost > remaining) break
            remaining -= cost
            end += if (pair) 2 else 1
        }
        return end
    }
}
