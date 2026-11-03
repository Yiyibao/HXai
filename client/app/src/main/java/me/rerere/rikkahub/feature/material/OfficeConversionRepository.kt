package me.rerere.rikkahub.feature.material

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.CredentialVault
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class OfficeConversionTask(
    val id: String, val sourceId: String, val sourceSha256: String, val extension: String,
    val title: String, val configurationKey: String, val rendererVersion: String,
    val outputId: String = UUID.randomUUID().toString(), val remoteId: String? = null,
    val state: String = "SUBMITTING", val errorCode: String? = null,
    val downloaded: Boolean = false, val cleanupPending: Boolean = false,
    val cancelRequested: Boolean = false,
    val operation: String = "office_pdf", val cancelConfirmed: Boolean = false,
)
@Serializable data class OfficeRenderOptions(val scope: String = "print_layout", val includeHiddenSlides: Boolean = false, val includeNotes: Boolean = false)
@Serializable data class OfficePdfManifest(
    val contractVersion: Int, val operation: String, override val sourceSha256: String, val sourceExtension: String,
    val renderer: String, override val rendererVersion: String, val renderOptions: OfficeRenderOptions,
    val pageCount: Int, override val warnings: List<String>, override val assets: List<DocumentAsset>,
): OfficeArtifactManifest

/** Shared authentication and durable intents; result types keep separate strict contracts. */
class OfficeConversionRepository(private val context: Context, private val materials: MaterialFiles, private val documents: DocumentServiceRepository) {
    private val json = Json { ignoreUnknownKeys = false }
    private val vault = CredentialVault(context)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addInterceptor(me.rerere.rikkahub.feature.diagnostics.DiagnosticsNetworkInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private fun directory(owner: String): File {
        require(MaterialFiles.validProfile(owner))
        return File(context.noBackupFilesDir, "document-service/$owner").apply { mkdirs() }
    }
    // AtomicFile readers can recover/delete a pending write; serialize metadata
    // reads with commits, independently of the coroutine/network mutex.
    @Synchronized
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); atomic.finishWrite(stream) } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun save(owner: String, task: OfficeConversionTask) {
        write(File(directory(owner), "${task.id}.office.json"), json.encodeToString(task)); documents.changes.value++
    }
    @Synchronized fun tasks(owner: String): List<OfficeConversionTask> = directory(owner).listFiles().orEmpty().filter { it.name.endsWith(".office.json") }.map {
        json.decodeFromString<OfficeConversionTask>(AtomicFile(it).readFully().toString(Charsets.UTF_8)).also { task ->
            require(UUID.fromString(task.id).toString() == task.id && it.name == "${task.id}.office.json" && UUID.fromString(task.outputId).toString() == task.outputId)
        }
    }
    fun task(owner: String, id: String) = tasks(owner).singleOrNull { it.id == id } ?: error("转换任务不属于当前档案")
    private fun config(owner: String) = documents.config(owner).also { check(it.enabled && it.credentialReference.isNotBlank()) { "请先启用当前档案的文档服务；原文件保留" } }
    private fun key(config: DocumentServiceConfig) = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(config.baseUrl + config.credentialReference)
    private fun taskConfig(owner: String, task: OfficeConversionTask) = config(owner).also { check(key(it) == task.configurationKey) { "服务配置已改变，不会向新服务发送旧任务" } }
    private fun request(config: DocumentServiceConfig, path: String) = Request.Builder().url((config.baseUrl + path).toHttpUrl()).header("Authorization", "Bearer " + vault.resolve(config.credentialReference))
    private suspend fun execute(request: Request): Response {
        val dispatch=kotlinx.coroutines.currentCoroutineContext()[me.rerere.rikkahub.feature.agent.AgentToolDispatch]
        dispatch?.check()
        return suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        dispatch?.check()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(java.io.IOException("转换服务连接失败；原件与提交记录保留，可重试")) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
    }
    private fun readJson(response: Response): JsonObject {
        check(response.isSuccessful) { "转换服务请求失败（HTTP ${response.code}）；原件保留" }
        val bytes = response.body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 65536); output.write(buffer, 0, n) }; output.toByteArray()
        }
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    suspend fun submit(owner: String, sourceId: String, expectedSha256: String, title: String, expectedTaskId: String? = null, operation: String = "office_pdf"): OfficeConversionTask = withContext(Dispatchers.IO) { mutex.withLock {
        require(operation == "office_pdf" || operation == XLSX_RECALCULATE)
        val source = materials.get(owner, sourceId); val file = materials.verifiedFile(owner, sourceId)
        require(source.sha256 == expectedSha256 && source.extension in setOf("docx", "xlsx", "pptx")) { "原件版本不匹配或格式不支持" }
        require(operation != XLSX_RECALCULATE || source.extension == "xlsx")
        require(title.length in 1..80 && title.none { it.isISOControl() || it in "\\/:*?\"<>|" })
        val config = config(owner)
        val previous = tasks(owner).firstOrNull {
            (expectedTaskId == null || it.id == expectedTaskId) &&
                it.sourceId == sourceId && it.sourceSha256 == expectedSha256 && it.operation == operation && it.title == title &&
                it.configurationKey == key(config) && !it.cancelRequested &&
                it.state !in setOf("FAILED", "CANCELLED", "EXPIRED")
        }
        require(expectedTaskId == null || previous != null) { "原转换配置已改变，未重复上传；请重新发起" }
        if (previous?.remoteId != null) return@withLock previous
        if (previous != null) return@withLock readOriginalRequest(owner,previous)
        val version = previous?.rendererVersion ?: run {
            val capability = documents.capabilities(owner)[if (operation == XLSX_RECALCULATE) "officeRecalculation" else "officePdf"]?.jsonObject ?: error("当前服务没有所需Office能力")
            check(capability["ready"]?.jsonPrimitive?.boolean == true) { "Office渲染器未就绪，原件保留" }
            capability.getValue("rendererVersion").jsonPrimitive.content
        }
        val task = previous ?: OfficeConversionTask(UUID.randomUUID().toString(), sourceId, expectedSha256, source.extension, title, key(config), version, operation = operation)
        save(owner, task)
        val endpoint = if (operation == XLSX_RECALCULATE) "/api/v1/recalculations" else "/api/v1/conversions"
        val url = (config.baseUrl + endpoint).toHttpUrl().newBuilder().addQueryParameter("extension", source.extension).build()
        val result = execute(request(config, endpoint).url(url).header("Idempotency-Key", task.id).header("X-Source-Sha256", expectedSha256).post(file.asRequestBody("application/octet-stream".toMediaType())).build()).use(::readJson)
        val remote = result.getValue("taskId").jsonPrimitive.content; require(UUID.fromString(remote).toString() == remote)
        check(result.getValue("operation").jsonPrimitive.content == operation)
        val rendererVersion = (result["rendererVersion"] as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 200 }?.content
        if (rendererVersion == null) {
            // The upload was acknowledged. Keep its owned remote identity before
            // rejecting incomplete metadata; another submit must not upload again.
            val state=result.getValue("state").jsonPrimitive.content
            require(state in setOf("QUEUED","RUNNING","SUCCEEDED","FAILED","CANCELLED","EXPIRED"))
            save(owner,task.copy(remoteId=remote,state=state,errorCode="renderer_receipt_incomplete"))
            error("服务已接收文件但渲染版本回执缺失；远端任务已保留，不会重复上传。请同步原任务回执")
        }
        task.copy(remoteId = remote, rendererVersion = rendererVersion,
            state = result.getValue("state").jsonPrimitive.content).also { save(owner, it) }
    } }
    /** Never re-upload an intent whose original network outcome is unknown. */
    suspend fun resume(owner: String, id: String): OfficeConversionTask = withContext(Dispatchers.IO) {mutex.withLock {
        val task=task(owner,id)
        require(!task.cancelRequested && task.remoteId==null && task.state=="SUBMITTING")
        readOriginalRequest(owner,task)
    }}
    private suspend fun readOriginalRequest(owner: String, task: OfficeConversionTask): OfficeConversionTask {
        require(!task.cancelRequested && task.remoteId==null && task.state=="SUBMITTING")
        val endpoint=if(task.operation==XLSX_RECALCULATE) "recalculation-requests" else "conversion-requests"
        val result=execute(request(taskConfig(owner,task),"/api/v1/$endpoint/${task.id}").get().build()).use(::readJson)
        require(result["requestKey"]==JsonPrimitive(task.id) && result["sourceSha256"]==JsonPrimitive(task.sourceSha256) &&
            result["sourceExtension"]==JsonPrimitive(task.extension) && result["operation"]==JsonPrimitive(task.operation)) {
            "原请求的来源或操作回执不匹配；保留提交意图，不会重传"
        }
        val remote=(result["taskId"] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: error("缺少原任务身份")
        require(UUID.fromString(remote).toString()==remote)
        val state=(result["state"] as? JsonPrimitive)?.content
        require(state in setOf("QUEUED","RUNNING","SUCCEEDED","FAILED","CANCELLED","EXPIRED"))
        taskConfig(owner,task)
        val source=materials.get(owner,task.sourceId);materials.verifiedFile(owner,task.sourceId)
        require(source.sha256==task.sourceSha256 && source.extension==task.extension &&
            materials.get(owner,task.sourceId).sha256==task.sourceSha256)
        require(this.task(owner,task.id)==task) {"原提交意图已变化；不发布旧读回"}
        val version=(result["rendererVersion"] as? JsonPrimitive)
            ?.takeIf {it.isString && it.content.isNotBlank() && it.content.length<=200}?.content
        val recovered=task.copy(remoteId=remote,state=requireNotNull(state),rendererVersion=version ?: task.rendererVersion,
            errorCode=if(version==null) "renderer_receipt_incomplete" else result["errorCode"]?.jsonPrimitive?.contentOrNull)
        save(owner,recovered)
        check(version!=null) {"已找回原任务，但渲染版本仍缺失；不会重传或发布成果"}
        return recovered
    }
    suspend fun refresh(owner: String, id: String): OfficeConversionTask = withContext(Dispatchers.IO) { mutex.withLock {
        val task = task(owner, id)
        if(task.remoteId==null)return@withLock readOriginalRequest(owner,task)
        val result = execute(request(taskConfig(owner, task), "/api/v1/jobs/${task.remoteId}").get().build()).use(::readJson)
        require(result.getValue("operation").jsonPrimitive.content == task.operation)
        val state = result.getValue("state").jsonPrimitive.content; require(state in setOf("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"))
        val rendererVersion=if(task.errorCode=="renderer_receipt_incomplete") {
            checkNotNull((result["rendererVersion"] as? JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 200 }?.content) {
                "原任务的渲染版本回执仍缺失；保留远端任务，不会重传或发布"
            }
        } else task.rendererVersion
        task.copy(state = state, rendererVersion=rendererVersion, errorCode = result["errorCode"]?.jsonPrimitive?.contentOrNull).also { save(owner, it) }
    } }
    suspend fun cancel(owner: String, id: String): OfficeConversionTask = withContext(Dispatchers.IO) { mutex.withLock {
        val task = task(owner, id)
        save(owner, task.copy(cancelRequested = true, state = "CANCELLING"))
        if (task.remoteId == null) {
            val endpoint = if (task.operation == XLSX_RECALCULATE) "recalculation-requests" else "conversion-requests"
            val result = execute(request(taskConfig(owner, task), "/api/v1/$endpoint/${task.id}/cancel").post(ByteArray(0).toRequestBody()).build()).use(::readJson)
            val remote = result["taskId"]?.jsonPrimitive?.contentOrNull
            require(remote == null || UUID.fromString(remote).toString() == remote)
            task.copy(remoteId = remote, state = result.getValue("state").jsonPrimitive.content, cancelRequested = true, cancelConfirmed = true).also { save(owner, it) }
        }
        else {
            val result = execute(request(taskConfig(owner, task), "/api/v1/jobs/${task.remoteId}/cancel").post(ByteArray(0).toRequestBody()).build()).use(::readJson)
            task.copy(state = result.getValue("state").jsonPrimitive.content, cancelRequested = true, cancelConfirmed = true).also { save(owner, it) }
        }
    } }
    private fun decodeManifest(text: String, task: OfficeConversionTask): OfficeArtifactManifest {
        val value = if (task.operation == XLSX_RECALCULATE) json.decodeFromString<XlsxRecalculationManifest>(text)
            else json.decodeFromString<OfficePdfManifest>(text)
        require(value.sourceSha256 == task.sourceSha256 && value.rendererVersion == task.rendererVersion &&
            value.warnings.size <= 20 && value.warnings.all { it.length <= 16000 })
        if (value is XlsxRecalculationManifest) {
            require(value.contractVersion == 1 && value.operation == task.operation && value.sourceExtension == "xlsx" &&
                value.renderer == "LibreOffice" && value.renderOptions == XlsxRecalculationOptions())
            require(value.assets.size == 2)
            val primary = value.assets[0]; val witness = value.assets[1]
            require(primary.assetId == "xlsx" && primary.path == "recalculated.xlsx" && primary.mediaType == XLSX_MEDIA_TYPE &&
                primary.size in 1..30L*1024*1024 && primary.sha256.matches(Regex("[a-f0-9]{64}")))
            require(witness.assetId == "calculation" && witness.path == "calculation.json" && witness.mediaType == "application/json" &&
                witness.size in 1..XlsxRecalculationReadback.WITNESS_LIMIT && witness.sha256.matches(Regex("[a-f0-9]{64}")))
        } else {
            require(value is OfficePdfManifest && value.contractVersion == 1 && value.operation == task.operation &&
                value.sourceExtension == task.extension && value.renderer == "LibreOffice" && value.renderOptions == OfficeRenderOptions() && value.pageCount in 1..200)
            val asset = value.assets.single()
            require(asset.assetId == "pdf" && asset.path == "output.pdf" && asset.mediaType == "application/pdf" &&
                asset.size in 1..30L*1024*1024 && asset.sha256.matches(Regex("[a-f0-9]{64}")))
        }
        return value
    }
    private fun manifest(owner: String, task: OfficeConversionTask) = decodeManifest(File(directory(owner), "${task.id}.office-manifest.json").readText(), task)
    private fun witnessFile(owner: String, task: OfficeConversionTask) = File(directory(owner), "${task.id}.office-calculation.json")
    private fun sourceCheck(owner: String, task: OfficeConversionTask) {
        check(materials.get(owner, task.sourceId).sha256 == task.sourceSha256)
        materials.verifiedFile(owner, task.sourceId)
    }
    private fun resultCheck(owner: String, task: OfficeConversionTask, value: OfficeArtifactManifest, file: File) {
        val primary = value.assets.first()
        require(file.length() == primary.size && MaterialFiles.digest(file) == primary.sha256)
        if (value is XlsxRecalculationManifest) {
            val witness = witnessFile(owner, task); val asset = value.assets[1]
            require(witness.length() == asset.size && MaterialFiles.digest(witness) == asset.sha256)
            XlsxRecalculationReadback.verify(file, witness, value)
        } else {
            require(value is OfficePdfManifest)
            val pdf = com.artifex.mupdf.fitz.Document.openDocument(file.absolutePath)
            try { require(pdf.countPages() == value.pageCount) } finally { pdf.destroy() }
        }
    }
    fun artifact(owner: String, id: String): MaterialFile {
        val task = task(owner, id); check(task.downloaded && !task.cancelRequested && task.errorCode!="renderer_receipt_incomplete"); sourceCheck(owner, task)
        val value = manifest(owner, task); val item = materials.get(owner, task.outputId)
        val extension = if (task.operation == XLSX_RECALCULATE) "xlsx" else "pdf"
        require(item.sourceId == task.sourceId && item.output && item.extension == extension && item.requestKey == "office:$id")
        resultCheck(owner, task, value, materials.verifiedFile(owner, item.id))
        return item
    }
    /** Called only after actual workbook/witness readback; hashes bind semantic evidence to disk. */
    fun calculationEvidence(owner: String, id: String): JsonObject {
        val task = task(owner, id)
        require(task.operation == XLSX_RECALCULATE)
        val item = artifact(owner, id)
        val value = manifest(owner, task) as XlsxRecalculationManifest
        return buildJsonObject {
            put("operation", task.operation); put("artifact_id", item.id); put("source_sha256", task.sourceSha256)
            put("output_sha256", item.sha256); put("witness_sha256", value.assets[1].sha256)
            put("manifest_sha256", MaterialFiles.digest(File(directory(owner), "$id.office-manifest.json")))
            put("renderer_version", value.rendererVersion); put("proof", json.encodeToJsonElement(value.recalculation))
        }
    }
    private suspend fun downloadAsset(config: DocumentServiceConfig, remote: String, asset: DocumentAsset, target: File) {
        execute(request(config, "/api/v1/jobs/$remote/assets/${asset.assetId}").get().build()).use { response ->
            check(response.isSuccessful)
            response.body.byteStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192); var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
                    count += n; require(count <= asset.size); output.write(buffer, 0, n)
                }
            } }
        }
        check(target.length() == asset.size && MaterialFiles.digest(target) == asset.sha256) { "Office下载摘要不匹配" }
    }
    suspend fun download(owner: String, id: String): MaterialFile = withContext(Dispatchers.IO) { mutex.withLock {
        val task = task(owner, id); check(!task.cancelRequested && task.errorCode!="renderer_receipt_incomplete"); sourceCheck(owner, task)
        if (task.downloaded) return@withLock artifact(owner, id)
        val config = taskConfig(owner, task); val remote = task.remoteId ?: error("提交尚未完成")
        val raw = execute(request(config, "/api/v1/jobs/$remote/manifest").get().build()).use(::readJson).toString()
        val value = decodeManifest(raw, task)
        write(File(directory(owner), "$id.office-manifest.json"), raw)
        if (value is XlsxRecalculationManifest) {
            val temp = File(directory(owner), "$id.office-calculation.part")
            try {
                downloadAsset(config, remote, value.assets[1], temp)
                java.nio.file.Files.move(temp.toPath(), witnessFile(owner, task).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        val extension = if (task.operation == XLSX_RECALCULATE) "xlsx" else "pdf"
        val existing = materials.list(owner).firstOrNull { it.id == task.outputId && it.state == "SUCCEEDED" }
        val item = if (existing != null) {
            require(existing.sourceId == task.sourceId && existing.output && existing.extension == extension && existing.requestKey == "office:$id")
            resultCheck(owner, task, value, materials.verifiedFile(owner, existing.id)); sourceCheck(owner, task); existing
        } else materials.runTask(MaterialFile(task.outputId, owner, "${task.title}.$extension", extension, output = true, sourceId = task.sourceId, requestKey = "office:$id")) { target ->
            downloadAsset(config, remote, value.assets.first(), target)
            resultCheck(owner, task, value, target)
            currentCoroutineContext()[me.rerere.rikkahub.feature.agent.AgentToolDispatch]?.check()
            taskConfig(owner, task); check(!task(owner, id).cancelRequested); sourceCheck(owner, task)
            val coverage = if (value is XlsxRecalculationManifest) "真实重算及保存缓存已独立核对，${value.recalculation.sheetCount}表/${value.recalculation.formulaCount}公式；布局未核验"
                else "排版转换，${(value as OfficePdfManifest).pageCount}页"
            "LibreOffice ${value.rendererVersion} $coverage；" + value.warnings.joinToString("；")
        }
        save(owner, task.copy(state = "SUCCEEDED", downloaded = true, cleanupPending = true))
        try { cleanupUnlocked(owner, id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Verified file remains; background retries cleanup. */ }
        item
    } }
    suspend fun cleanupRemote(owner: String, id: String) = mutex.withLock { cleanupUnlocked(owner, id) }
    private suspend fun cleanupUnlocked(owner: String, id: String) {
        val task = task(owner, id); val remote = task.remoteId ?: return; val config = taskConfig(owner, task)
        execute(request(config, "/api/v1/jobs/$remote").delete().build()).use(::readJson)
        val status = execute(request(config, "/api/v1/jobs/$remote").get().build()).use(::readJson)
        if (status.getValue("state").jsonPrimitive.content == "EXPIRED") save(owner, task.copy(cleanupPending = false))
    }
    suspend fun syncPending(owner: String) {
        if (!documents.enabled(owner)) return
        for (task in tasks(owner)) {
            try {
                if (task.cancelRequested) {
                    if (!task.cancelConfirmed) cancel(owner, task.id)
                } else if (task.cleanupPending) cleanupRemote(owner, task.id)
                else if (task.remoteId != null && !task.downloaded && task.state in setOf("QUEUED", "RUNNING", "SUCCEEDED")) {
                    val current = refresh(owner, task.id); if (current.state == "SUCCEEDED") download(owner, task.id)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* No background re-upload or invented success. */ }
        }
    }
}
