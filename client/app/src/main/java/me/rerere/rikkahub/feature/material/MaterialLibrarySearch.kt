package me.rerere.rikkahub.feature.material

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Stateless catalog-bound pagination. A cursor is a requested position, never proof of prior coverage. */
internal object MaterialLibrarySearch {
    internal class Rejected(val code: String) : IllegalArgumentException(code)
    private val readable = setOf("txt", "md", "csv", "json", "docx", "xlsx", "pptx", "pdf")
    private val cursorFields = setOf("snapshot_sha256", "version_index", "search_offset")

    private fun catalog(store: MaterialFiles, owner: String) = store.list(owner).filter { it.state == "SUCCEEDED" }.sortedBy { it.id }

    /** Length-prefixed UTF16 code units preserve even malformed names without UTF8 replacement collisions. */
    private fun hashPart(hash: MessageDigest, text: String?) {
        val length = text?.length ?: -1
        val bytes = ByteArray(4 + if (text == null) 0 else length * 2)
        for (i in 0..3) bytes[i] = (length ushr (24 - i * 8)).toByte()
        if (text != null) for (i in text.indices) {
            bytes[4 + i * 2] = (text[i].code ushr 8).toByte()
            bytes[5 + i * 2] = text[i].code.toByte()
        }
        hash.update(bytes)
    }

    private fun snapshot(owner: String, query: String, items: List<MaterialFile>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hashPart(hash, "material_library_search_v1"); hashPart(hash, owner); hashPart(hash, query); hashPart(hash, items.size.toString())
        for (item in items) {
            hashPart(hash, item.id); hashPart(hash, item.profileId); hashPart(hash, item.name); hashPart(hash, item.extension)
            hashPart(hash, item.size.toString()); hashPart(hash, item.sha256); hashPart(hash, item.sourceId); hashPart(hash, item.state)
        }
        return MaterialSourceReceipt.hex(hash.digest())
    }

    private fun descriptor(item: MaterialFile, reason: String) = buildJsonObject {
        put("material_id", item.id); put("version_id", item.id); put("name", item.name); put("extension", item.extension)
        put("source_sha256", item.sha256); put("source_verified", false); put("reason", reason)
    }

    private fun encodeCursor(snapshot: String, index: Int, offset: Int) = buildJsonObject {
        put("snapshot_sha256", snapshot); put("version_index", index.toString()); put("search_offset", offset.toString())
    }.toString()

    suspend fun search(store: MaterialFiles, owner: String, query: String, cursor: String, limit: Int, sourceLimit: Int): JsonObject = withContext(Dispatchers.IO) {
        try { MaterialSourceSearch.validate(query, 0, limit) } catch (_: IllegalArgumentException) { throw Rejected("INVALID_LIBRARY_QUERY_OR_LIMIT") }
        if (sourceLimit !in 1..5) throw Rejected("INVALID_LIBRARY_SOURCE_LIMIT")
        if (cursor.length > 2048) throw Rejected("INVALID_LIBRARY_CURSOR")
        currentCoroutineContext().ensureActive()
        val items = catalog(store, owner)
        val digest = snapshot(owner, query, items)
        var index = 0
        var offset = 0
        if (cursor.isNotEmpty()) {
            val data = try { Json.parseToJsonElement(cursor).jsonObject } catch (_: IllegalArgumentException) { throw Rejected("INVALID_LIBRARY_CURSOR") }
            if (data.keys != cursorFields || data.values.any { it !is JsonPrimitive || !it.isString }) throw Rejected("INVALID_LIBRARY_CURSOR")
            if (data.getValue("snapshot_sha256").jsonPrimitive.content != digest) throw Rejected("STALE_LIBRARY_CURSOR")
            index = data.getValue("version_index").jsonPrimitive.content.toIntOrNull() ?: throw Rejected("INVALID_LIBRARY_CURSOR")
            offset = data.getValue("search_offset").jsonPrimitive.content.toIntOrNull() ?: throw Rejected("INVALID_LIBRARY_CURSOR")
            if (index !in 0..items.size || offset !in 0..MaterialReader.LIMIT || (index == items.size && offset != 0)) throw Rejected("INVALID_LIBRARY_CURSOR")
        }
        val startIndex = index
        val startOffset = offset
        val hits = ArrayList<JsonElement>(limit)
        val scanned = ArrayList<JsonElement>(sourceLimit)
        val skipped = ArrayList<JsonElement>()
        val errors = ArrayList<JsonElement>()
        val verified = ArrayList<MaterialFile>(sourceLimit)
        var visited = 0
        while (index < items.size && visited < sourceLimit && hits.size < limit) {
            currentCoroutineContext().ensureActive()
            val item = items[index]
            visited++
            if (item.extension !in readable) {
                if (offset != 0) throw Rejected("INVALID_LIBRARY_CURSOR")
                skipped.add(descriptor(item, "FORMAT_NOT_TEXT_READABLE"))
            } else {
                val result = try {
                    store.searchSourceText(owner, item.id, item.sha256, query, offset, limit - hits.size)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    errors.add(descriptor(item, "SOURCE_UNAVAILABLE_OR_UNVERIFIED")); null
                }
                if (result != null) {
                    hits.addAll(result.getValue("hits").jsonArray)
                    scanned.add(buildJsonObject {
                        for ((key, value) in result) if (key != "hits" && key != "notice") put(key, value)
                        put("name", item.name); put("source_verified", true)
                    })
                    verified.add(item)
                    if (result.getValue("has_more").jsonPrimitive.boolean) {
                        offset = result.getValue("next_search_offset").jsonPrimitive.int
                        break
                    }
                }
            }
            index++; offset = 0
        }
        // A previously read source must not change while another version is being searched.
        for (item in verified) {
            currentCoroutineContext().ensureActive()
            try { store.verifiedFile(owner, item.id) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw Rejected("SOURCE_CHANGED_DURING_LIBRARY_SEARCH") }
        }
        if (snapshot(owner, query, catalog(store, owner)) != digest) throw Rejected("LIBRARY_CATALOG_CHANGED")
        val more = index < items.size
        buildJsonObject {
            put("contractVersion", 1); put("status", if (errors.isEmpty()) "SUCCEEDED" else "FAILED")
            if (errors.isNotEmpty()) put("error", "部分材料版本未能核验，本页检索未完成；仅保留已核验的局部命中。")
            put("scope", "owner_library_extracted_text_page"); put("match_mode", "literal_case_sensitive")
            put("owner", owner); put("query", query); put("catalog_snapshot_sha256", digest); put("catalog_versions", items.size)
            put("start_version_index", startIndex); put("start_search_offset", startOffset); put("visited_versions", visited)
            put("scanned_sources", JsonArray(scanned)); put("skipped_sources", JsonArray(skipped)); put("source_errors", JsonArray(errors))
            put("hits", JsonArray(hits)); put("has_more", more); put("exhausted_catalog", !more)
            if (more) put("next_cursor", encodeCursor(digest, index, offset))
            put("library_completeness", "NOT_ASSERTED"); put("document_completeness", "NOT_ASSERTED")
            put("notice", "仅检索本页固定档案目录版本的已提取正文，不是持久全库索引/语义检索/整文覆盖。无命中或目录游标到末尾不证明原文不存在该内容或前页已读。游标仅请求分页位置，不是权限或覆盖证明；跨档案/查询/版本集合不可复用。跳过格式未经正文核验，不自动OCR或外传；hits沿用v2逐字来源回执，引用前可再次独立核对，不是数学证明。")
        }
    }
}

/** The trusted host supplies owner; Sol registers this under existing local.read policy. */
fun materialLibrarySearchTool(store: MaterialFiles, owner: String): Tool = Tool(
    name = "search_material_library",
    description = "在固定档案长期材料库跨版本检索真实已提取正文，字面区分大小写，支持重叠命中和版本集合绑定分页；不是持久索引或完整原文结论。query/cursor/limit/source_limit均字符串，首cursor为空；query1–256完整UTF16，limit1–20总命中，source_limit1–5本页版本数。续接仅用返回next_cursor并保持query/原owner，材料增删或版本变化须重启。保原件，逐源冻结/hash/回读；一份可读源错不能因其他命中标本页成功。跳过图片/旧二进制格式不自动OCR/外传；v2来源回执非数学/权限/任务完成证明。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        for (key in listOf("query", "cursor", "limit", "source_limit")) put(key, buildJsonObject { put("type", "string") })
    }, required = listOf("query", "cursor", "limit", "source_limit")) },
    execute = { args -> withContext(Dispatchers.IO) {
        try {
            val input = args.jsonObject
            require(input.size == 4 && input.keys.all { it == "query" || it == "cursor" || it == "limit" || it == "source_limit" })
            fun value(key: String) = input.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val result = store.searchLibraryText(owner, value("query"), value("cursor"), value("limit").toInt(), value("source_limit").toInt())
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val code = (e as? MaterialLibrarySearch.Rejected)?.code ?: "LIBRARY_SEARCH_REJECTED"
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "FAILED"); put("error", "资料库版本、分页或输入无法核验；请检查材料并重新开始检索。")
                put("reason", code)
            }.toString()))
        }
    } },
)
