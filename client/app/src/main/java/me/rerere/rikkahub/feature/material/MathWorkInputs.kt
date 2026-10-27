package me.rerere.rikkahub.feature.material

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.sync.PendingRestore
import me.rerere.rikkahub.feature.math.MathVerification
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** User-confirmed ordered transcriptions. Not model-writable, not included in backups. */
internal class MathWorkInputs(private val context: Context, private val files: MaterialFiles) {
    private val candidates by lazy { MathWorkCandidates(files, DocumentServiceRepository(context, files)) }
    fun parseResults(owner: String, id: String, sha256: String, offset: Int) = candidates.list(owner, id, sha256, offset)
    fun parseCandidate(owner: String, id: String, sha256: String, number: Int, taskId: String) =
        MathWorkCandidates.response(candidates.read(owner, id, sha256, number, taskId))
    private fun directory(owner: String) = File(context.noBackupFilesDir, "math-work-inputs/${ownerKey(owner)}").apply { mkdirs() }
    private fun file(owner: String, id: String): File {
        require(UUID.fromString(id).toString() == id)
        // /data/data is an Android-managed alias of /data/user/0; constrain the canonical parent.
        val root = directory(owner).canonicalFile
        return File(root, "$id.json").also { check(it.canonicalFile.parentFile == root) }
    }
    private fun parse(raw: String): JsonObject {
        require(raw.length <= 65536)
        return me.rerere.rikkahub.feature.math.ProfessionalMath.request(raw)
    }
    private fun checkPage(owner: String, page: JsonObject) {
        val fields = setOf("id", "sha256", "number", "transcription", "ambiguities")
        require(page.keys == fields || page.keys == fields + "candidate")
        if ("candidate" in page) {
            val ref = page.getValue("candidate").jsonObject
            require(ref.keys == setOf("task_id", "result_revision", "candidate_sha256"))
            require(ref.getValue("task_id").jsonPrimitive.let { it.isString && UUID.fromString(it.content).toString() == it.content })
            require(ref.getValue("result_revision").jsonPrimitive.let { !it.isString && it.int > 0 })
            require(ref.getValue("candidate_sha256").jsonPrimitive.let { it.isString && it.content.matches(Regex("[a-f0-9]{64}")) })
        }
        fun text(key: String) = page.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        val item = files.get(owner, text("id")); check(item.sha256 == text("sha256")) { "手写来源版本已改变" }
        val original = files.verifiedFile(owner, item.id)
        val number = page.getValue("number").jsonPrimitive.also { require(!it.isString) }.int
        require(text("transcription").length in 1..4000)
        val ambiguities = page.getValue("ambiguities").jsonArray
        require(ambiguities.size <= 20 && ambiguities.all { it is JsonPrimitive && it.isString && it.content.length in 1..200 })
        when (item.extension) {
            "png", "jpg", "jpeg", "webp" -> require(number == 1)
            "pdf" -> ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf -> require(number in 1..pdf.pageCount) }
            }
            else -> error("手写批改只接入真实图片或PDF物理页；不猜Word分页")
        }
    }
    suspend fun create(owner: String, raw: String, confirmed: Boolean, stillAllowed: () -> Unit): JsonObject {
        val input = parse(raw)
        require(input.keys == setOf("title", "pages"))
        val title = input.getValue("title").jsonPrimitive.also { require(it.isString && it.content.length in 1..100) }.content
        val pages = input.getValue("pages").jsonArray
        require(pages.size in 1..12)
        val identities = HashSet<String>()
        val candidateEvidence = ArrayList<JsonElement>(pages.size)
        for (value in pages) {
            currentCoroutineContext().ensureActive(); stillAllowed()
            val page = value.jsonObject; checkPage(owner, page)
            check(identities.add("${page.getValue("id")}:${page.getValue("number")}")) { "同一源物理页重复，请明确页序" }
            if (confirmed) require(page.getValue("ambiguities").jsonArray.isEmpty()) { "仍有关键符号歧义，不能确认完整输入" }
            val snapshot = if ("candidate" in page) {
                candidates.read(owner, page.getValue("id").jsonPrimitive.content, page.getValue("sha256").jsonPrimitive.content,
                    page.getValue("number").jsonPrimitive.int, page.getValue("candidate").jsonObject.getValue("task_id").jsonPrimitive.content)
                    .also { MathWorkCandidates.validate(page, it) }
            } else null
            candidateEvidence += snapshot ?: JsonNull
        }
        val version = if (pages.any { "candidate" in it.jsonObject }) 2 else 1
        require(candidateEvidence.toString().length <= 65536) { "整份候选证据过长，未删减原文或覆盖旧草稿" }
        val key = "math-work-v$version:$owner:$confirmed:$input"
        val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        val record = buildJsonObject {
            put("version", version); put("id", id); put("owner", owner); put("title", title)
            put("state", if (confirmed) "CONFIRMED" else "WAITING_INPUT"); put("pages", pages)
            put("transcription_origin", if (version == 1) {
                if (confirmed) "USER_CONFIRMED_NOT_MACHINE_OCR" else "USER_DRAFT_NOT_MACHINE_OCR"
            } else if (confirmed) "USER_CONFIRMED_WITH_PARSE_PROVENANCE" else "PARSE_CANDIDATE_NOT_CONFIRMED")
            if (version == 2) put("candidate_evidence", JsonArray(candidateEvidence))
        }
        stillAllowed()
        val target = file(owner, id)
        files.withSourceMutationLock {
            pages.forEach { checkPage(owner, it.jsonObject) }
            if (version == 2) pages.forEachIndexed { index, element ->
                val page = element.jsonObject
                if ("candidate" in page) check(candidates.read(owner, page.getValue("id").jsonPrimitive.content,
                    page.getValue("sha256").jsonPrimitive.content, page.getValue("number").jsonPrimitive.int,
                    page.getValue("candidate").jsonObject.getValue("task_id").jsonPrimitive.content) == candidateEvidence[index]) { "保存期间解析候选改变" }
            }
            stillAllowed()
            synchronized(lock) {
                if (target.exists()) check(Json.parseToJsonElement(target.readText()) == record) { "既有确认记录不同或已撤销，未覆盖" }
                else PendingRestore.writeDurably(target, record.toString())
            }
        }
        pages.forEach { checkPage(owner, it.jsonObject) }; stillAllowed()
        return read(owner, id)
    }
    fun read(owner: String, id: String): JsonObject {
        val record = synchronized(lock) {
            val source = file(owner, id); check(source.length() in 1..MAX_RECORD_BYTES)
            Json.parseToJsonElement(source.readText()).jsonObject
        }
        val version = record["version"]?.jsonPrimitive?.int
        check(version in setOf(1, 2) && record["owner"] == JsonPrimitive(owner) && record["id"] == JsonPrimitive(id))
        check(record["state"]?.jsonPrimitive?.content in setOf("CONFIRMED", "WAITING_INPUT")) { "来源已删除或确认记录已撤销" }
        val pages = record.getValue("pages").jsonArray
        pages.forEach { checkPage(owner, it.jsonObject) }
        if (version == 2) {
            val evidence = record.getValue("candidate_evidence").jsonArray
            check(evidence.size == pages.size)
            pages.forEachIndexed { index, page ->
                if ("candidate" in page.jsonObject) MathWorkCandidates.validate(page.jsonObject, evidence[index].jsonObject)
                else check(evidence[index] == JsonNull)
            }
        } else check(pages.none { "candidate" in it.jsonObject })
        return buildJsonObject {
            put("contractVersion", version); put("status", if (record["state"] == JsonPrimitive("CONFIRMED")) "SOURCE_READY" else "WAITING_INPUT")
            put("work_id", id); put("title", record.getValue("title")); put("pages", pages)
            put("page_order", "TRUSTED_USER_ORDER"); put("transcription_origin", record.getValue("transcription_origin"))
            if (version == 2) put("candidate_evidence", record.getValue("candidate_evidence"))
            put("mathematics_status", "NOT_EVALUATED")
            put("notice", if (version == 1) "页序、物理页与用户转录绑定固定真实源。用户确认不是机器OCR、教材逐字引文或数学证明；任一源删除/改变即拒绝旧确认。"
                else "页序、物理页、用户修订与固定真实源及原解析候选快照绑定。候选不是真值，确认不是识别准确率或数学证明；任一原源删除/改变即拒绝旧确认。快照独立保留，缓存清理不丢失已确认转录。")
        }
    }
    fun list(owner: String, offset: Int, limit: Int): JsonObject {
        require(offset in 0..100000 && limit in 1..50)
        val paths = directory(owner).listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        val selected = paths.drop(offset).take(limit)
        return buildJsonObject {
            put("items", JsonArray(selected.mapNotNull { path ->
                runCatching { read(owner, path.nameWithoutExtension) }.getOrNull()?.let { record ->
                    buildJsonObject { put("work_id", record.getValue("work_id")); put("title", record.getValue("title"))
                        put("status", record.getValue("status")); put("page_count", record.getValue("pages").jsonArray.size) }
                }
            }))
            put("next_offset", offset + selected.size); put("has_more", offset + selected.size < paths.size)
        }
    }
    fun readForTool(owner: String, args: JsonObject): JsonObject {
        require(args.keys == setOf("work_id"))
        val id = args.getValue("work_id").jsonPrimitive.also { require(it.isString) }.content
        return buildJsonObject {
            put("status", "SUCCEEDED"); put("work_id", id); put("source_evidence", read(owner, id))
            put("mathematics_status", "NOT_EVALUATED")
            put("notice", "只读回当前来源与用户确认转录，不授予权限、不确认OCR、不证明数学。source_evidence.status为WAITING_INPUT时必须先澄清。")
        }
    }
    suspend fun review(owner: String, args: JsonObject): JsonObject {
        require(args.keys == setOf("work_id", "review"))
        val id = args.getValue("work_id").jsonPrimitive.content
        val evidence = read(owner, id)
        if (evidence["status"] != JsonPrimitive("SOURCE_READY")) return evidence
        val request = args.getValue("review").jsonPrimitive.also { require(it.isString) }.content
        val reviewInput = parse(request)
        val pages = evidence.getValue("pages").jsonArray
        val steps = reviewInput.getValue("steps").jsonArray
        val expected = pages.flatMapIndexed { pageIndex, page ->
            page.jsonObject.getValue("transcription").jsonPrimitive.content.lineSequence().withIndex()
                .filter { it.value.isNotBlank() }.map { "${pageIndex + 1}:${it.index + 1}" to it.value }.toList()
        }
        require(steps.size == expected.size) { "必须按已确认页序覆盖全部非空转录行；未知行用check:null，不能漏前缀" }
        steps.forEachIndexed { index, step ->
            val row = step.jsonObject
            check(row.getValue("locator").jsonPrimitive.content == expected[index].first &&
                row.getValue("original").jsonPrimitive.content == expected[index].second) { "步骤页序/行号/原文与确认转录不一致" }
        }
        val result = MathVerification.verify(buildJsonObject { put("operation", "professional_review"); put("request", request) })
        currentCoroutineContext().ensureActive()
        check(read(owner, id) == evidence) { "核验期间来源或确认改变" }
        return buildJsonObject {
            put("status", "SUCCEEDED"); put("work_id", id); put("source_evidence", evidence)
            put("review", result); put("mathematics_status", result.getValue("status"))
            put("notice", "SUCCEEDED只表示来源读回与本次检查执行完成，不表示学生答案正确。首错仅对已映射数学声明的完整已核前缀；未知转录/步骤映射不自动认证，不按标准解法字串评分。")
        }
    }
    companion object {
        private const val MAX_RECORD_BYTES = 8L * 65536 + 4096
        private val lock = Any()
        private fun ownerKey(owner: String) = MaterialSourceReceipt.hex(MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8)))
        fun invalidateSource(context: Context, owner: String, sourceId: String) = synchronized(lock) {
            val root = File(context.noBackupFilesDir, "math-work-inputs/${ownerKey(owner)}")
            for (path in root.listFiles().orEmpty()) {
                if (!path.name.matches(Regex("[a-f0-9-]{36}\\.json")) || path.length() > MAX_RECORD_BYTES) continue
                val value = runCatching { Json.parseToJsonElement(path.readText()).jsonObject }.getOrNull() ?: continue
                if ((value["pages"] as? JsonArray)?.any { it.jsonObject["id"] == JsonPrimitive(sourceId) } == true) {
                    PendingRestore.writeDurably(path, buildJsonObject {
                        put("version", 1); put("id", value.getValue("id")); put("owner", owner); put("state", "REVOKED")
                    }.toString())
                }
            }
        }
    }
}

internal fun mathWorkReadTool(files: MaterialFiles, owner: String) = Tool(
    name = "read_math_work",
    description = "按真实work_id独立读回多页数学原稿的用户页序、物理页、固定源id/hash、完整转录和待确认歧义。开始批改先调用；用户转录不是OCR或教材原文。只读本owner记录，来源变化/删除拒绝旧回执；模型不能创建或确认转录。",
    parameters = { InputSchema.Obj(buildJsonObject { put("work_id", buildJsonObject { put("type", "string"); put("maxLength", 36) }) }, listOf("work_id")) },
    execute = { input -> listOf(UIMessagePart.Text(files.mathWorkInputs.readForTool(owner, input.jsonObject).toString())) },
)

internal fun mathWorkReviewTool(files: MaterialFiles, owner: String) = Tool(
    name = "review_math_work",
    description = "批改可信用户已确认页序/转录的多页数学作业。先read_math_work读完整原稿；work_id来自真实前端确认。review为professional_review的JSON对象字符串，必须按用户页序覆盖每个非空原文行，locator为用户页序号:原转录行号，original逐字一致；未知行check:null。不能由模型创建/确认转录、漏前缀或猜work_id。先处理WAITING_INPUT；SUCCEEDED只表示执行/来源读回，数学看mathematics_status与review。",
    parameters = { InputSchema.Obj(buildJsonObject { for (name in listOf("work_id", "review")) put(name, buildJsonObject { put("type", "string"); put("maxLength", if (name == "work_id") 36 else 65536) }) }, listOf("work_id", "review")) },
    execute = { input -> listOf(UIMessagePart.Text(files.mathWorkInputs.review(owner, input.jsonObject).toString())) },
)
