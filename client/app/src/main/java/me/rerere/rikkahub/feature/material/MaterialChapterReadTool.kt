package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Registration and current-profile read authorization remain with the shared Agent host. */
fun materialChapterReadTool(store: MaterialFiles, owner: String): Tool = Tool(
    name = "read_material_chapter",
    description = "按真实章节索引哈希和标题候选ID读取固定源版本的已提取正文；六个严格字符串id/expected_sha256/index_sha256/chapter_id/offset/count。index_sha256与chapter_id取自index_material_chapters，offset是候选区间UTF16跨度内相对坐标，count1–24000；单次不跨实际PDF物理页/PPT含隐藏逻辑slide，排除生成页标/空页占位、不进入下一候选章节。按next_chapter_offset续读，跳过间隙时实际chapter_offset可大于请求。每次从冻结原件重建索引，不信任缓存，逐字v2回执可重验；到末尾不是整章节/教材完整、OCR、印刷页、数学或授权证明。保原件，不外传，不另选模型。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        for (key in listOf("id", "expected_sha256", "index_sha256", "chapter_id", "offset", "count")) put(key, buildJsonObject { put("type", "string") })
    }, required = listOf("id", "expected_sha256", "index_sha256", "chapter_id", "offset", "count")) },
    execute = { args -> withContext(Dispatchers.IO) {
        try {
            val input = args.jsonObject
            require(input.keys == setOf("id", "expected_sha256", "index_sha256", "chapter_id", "offset", "count"))
            fun value(key: String) = input.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val page = store.readChapter(owner, value("id"), value("expected_sha256"), value("index_sha256"),
                value("chapter_id"), value("offset").toInt(), value("count").toInt())
            listOf(UIMessagePart.Text(page.toString()))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "FAILED"); put("reason", "CHAPTER_READ_REJECTED")
                put("error", "当前源版本、索引哈希、候选章节或Unicode范围无法核验；请重新读取真实索引，没有签发正文回执。")
            }.toString()))
        }
    } },
)
