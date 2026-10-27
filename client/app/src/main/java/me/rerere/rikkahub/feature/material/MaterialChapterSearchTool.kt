package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** The trusted shared host supplies current-profile identity and dispatch authorization. */
fun materialChapterSearchTool(store: MaterialFiles, owner: String): Tool = Tool(
    name = "search_material_chapter",
    description = "在固定材料版本和真实索引的一个标题候选区间检索字面串。七个严格字符串id/expected_sha256/index_sha256/chapter_id/query/offset/limit；索引哈希/候选ID取自index_material_chapters，offset为章节跨度内UTF16相对位置，query为1–256完整UTF16单位，limit1–20。区分大小写、重叠命中，不跨真实PDF物理页/PPT逻辑slide间隙或下一候选，不搜索生成页标。每hit给逐字v2来源回执；继续只用next_chapter_search_offset并保持版本/索引/章节/query，不能用hit的全局offset续接。每次冻结原件重建索引，无命中仅为已提取正文suffix结论，不代表原章节完整、OCR、印刷页、数学证明或授权；不外传、不换模型。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        for (key in listOf("id", "expected_sha256", "index_sha256", "chapter_id", "query", "offset", "limit")) put(key, buildJsonObject { put("type", "string") })
    }, required = listOf("id", "expected_sha256", "index_sha256", "chapter_id", "query", "offset", "limit")) },
    execute = { args -> withContext(Dispatchers.IO) {
        try {
            val input = args.jsonObject
            require(input.keys == setOf("id", "expected_sha256", "index_sha256", "chapter_id", "query", "offset", "limit"))
            fun value(key: String) = input.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val result = store.searchChapter(owner, value("id"), value("expected_sha256"), value("index_sha256"),
                value("chapter_id"), value("query"), value("offset").toInt(), value("limit").toInt())
            listOf(UIMessagePart.Text(result.toString()))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "FAILED"); put("reason", "CHAPTER_SEARCH_REJECTED")
                put("error", "当前源版本、索引、候选章节、字面串或Unicode范围无法核验；请核对真实索引及输入，没有签发命中回执。")
            }.toString()))
        }
    } },
)
