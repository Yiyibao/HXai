package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** The shared host owns registration and current-profile local.read authorization. */
fun materialChapterIndexTool(store: MaterialFiles, owner: String): Tool = Tool(
    name = "index_material_chapters",
    description = "从当前档案固定PDF/PPTX/DOCX/TXT/MD真实版本生成并持久保存标题候选区间与提取覆盖索引，不猜语义章节/印刷页。四个严格字符串id/expected_sha256/offset/limit，offset为索引序号从0起，limit1–20；只检测独立行Markdown井号标题、第N章/篇/部、Chapter N，最多512项，截断/无标题/缺字页明确标记。每标题带可重验v2来源回执；重复标题保留独立位置。复用索引前重新冻结/提取/核对真实源，不信任缓存正文；原件保留、不OCR/外传。index_sha256绑定完整索引，不证明整文完整、权限、数学结论或前页已读。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        for (key in listOf("id", "expected_sha256", "offset", "limit")) put(key, buildJsonObject { put("type", "string") })
    }, required = listOf("id", "expected_sha256", "offset", "limit")) },
    execute = { args -> withContext(Dispatchers.IO) {
        try {
            val input = args.jsonObject
            require(input.keys == setOf("id", "expected_sha256", "offset", "limit"))
            fun value(key: String) = input.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val page = store.chapterIndex(owner, value("id"), value("expected_sha256"), value("offset").toInt(), value("limit").toInt())
            listOf(UIMessagePart.Text(page.toString()))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "FAILED"); put("reason", "CHAPTER_INDEX_REJECTED")
                put("error", "材料版本、标题索引或分页无法核验；没有签发章节或完整教材结论。")
            }.toString()))
        }
    } },
)
