package me.rerere.rikkahub.feature.material

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/** Operation lookup is not authorization; the host registry owns risk/capability. */
fun remoteOfficeOperation(toolName: String): String? = when (toolName) {
    "convert_material_to_pdf" -> "office_pdf"
    "recalculate_material_xlsx" -> XLSX_RECALCULATE
    else -> null
}

/** Remote version-write tool; the shared factory also enforces the trusted EXTERNAL contract. */
fun xlsxRecalculationTool(materials: MaterialFiles, owner: String, documents: DocumentServiceRepository, allowRemote: Boolean): Tool = Tool(
    name = "recalculate_material_xlsx",
    description = "把当前档案已检查的真实XLSX交给已授权Office服务实际重算，保存可编辑新版本，原件保留。id/expected_sha256绑定源版本，title为新文件名不含扩展名。提交后返回task_id只是排队；不要重复提交或轮询，原任务自动续接下载。保存缓存/结果类型独立核对，不保证微软Office复杂排版一致；未就绪或失败不能声称重算成功。",
    parameters = { InputSchema.Obj(buildJsonObject {
        for (key in listOf("id", "expected_sha256", "title")) put(key, buildJsonObject { put("type", "string") })
    }, listOf("id", "expected_sha256", "title")) },
    needsApproval = { true },
    execute = { args -> withContext(Dispatchers.IO) {
        try {
            require(allowRemote && documents.enabled(owner)) { "当前聊天不允许上传或档案未启用文档服务；没有上传原件" }
            fun string(key: String) = args.jsonObject.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val source = materials.get(owner, string("id"))
            materials.verifiedFile(owner, source.id)
            require(source.extension == "xlsx" && source.sha256 == string("expected_sha256")) { "源格式/摘要不匹配，请重新检查" }
            val title = string("title").map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }.joinToString("").take(80).ifBlank { "重算结果" }
            val office = documents.officeConversions
            val task = try { office.submit(owner, source.id, source.sha256, title, operation = XLSX_RECALCULATE) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                office.tasks(owner).firstOrNull { it.operation == XLSX_RECALCULATE && it.sourceId == source.id &&
                    it.sourceSha256 == source.sha256 && it.title == title && it.state == "SUBMITTING" && !it.cancelRequested } ?: throw e
            }
            if (task.downloaded) {
                val artifact = office.artifact(owner, task.id)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("operation", XLSX_RECALCULATE); put("task_id", task.id); put("source_id", source.id)
                    put("state", "SUCCEEDED"); put("downloaded", true); put("detail", artifact.detail)
                    put("calculation", office.calculationEvidence(owner, task.id))
                }.toString()), UIMessagePart.Document(materials.verifiedFile(owner, artifact.id).toUri().toString(), artifact.name, XLSX_MEDIA_TYPE))
            } else listOf(UIMessagePart.Text(buildJsonObject {
                put("operation", XLSX_RECALCULATE); put("task_id", task.id); put("source_id", source.id)
                put("state", task.state); put("downloaded", false)
                put("notice", "重算请求已持久记录，排队/未知回执不是成功。原任务续接真实结果后才交付新版；不要重传或生成静态替代文件。")
            }.toString()))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { listOf(UIMessagePart.Text(buildJsonObject {
            put("status", "FAILED"); put("error", e.message?.take(160) ?: "重算未完成")
            put("notice", "原文件保留，没有交付已核验重算文件。")
        }.toString())) }
    } },
)
