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
)
@Serializable data class OfficeRenderOptions(val scope: String = "print_layout", val includeHiddenSlides: Boolean = false, val includeNotes: Boolean = false)
@Serializable data class OfficePdfManifest(
    val contractVersion: Int, val operation: String, val sourceSha256: String, val sourceExtension: String,
    val renderer: String, val rendererVersion: String, val renderOptions: OfficeRenderOptions,
    val pageCount: Int, val warnings: List<String>, val assets: List<DocumentAsset>,
)

/** Shared authentication, separate durable conversion intents and strictly checked PDF artifacts. */
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
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); atomic.finishWrite(stream) } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun save(owner: String, task: OfficeConversionTask) {
        write(File(directory(owner), "${task.id}.office.json"), json.encodeToString(task)); documents.changes.value++
    }
    fun tasks(owner: String): List<OfficeConversionTask> = directory(owner).listFiles().orEmpty().filter { it.name.endsWith(".office.json") }.map {
        json.decodeFromString<OfficeConversionTask>(AtomicFile(it).readFully().toString(Charsets.UTF_8)).also { task ->
            require(UUID.fromString(task.id).toString() == task.id && it.name == "${task.id}.office.json" && UUID.fromString(task.outputId).toString() == task.outputId)
        }
    }
    fun task(owner: String, id: String) = tasks(owner).singleOrNull { it.id == id } ?: error("转换任务不属于当前档案")
    private fun config(owner: String) = documents.config(owner).also { check(it.enabled && it.credentialReference.isNotBlank()) { "请先启用当前档案的文档服务；原文件保留" } }
    private fun key(config: DocumentServiceConfig) = me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash(config.baseUrl + config.credentialReference)
    private fun taskConfig(owner: String, task: OfficeConversionTask) = config(owner).also { check(key(it) == task.configurationKey) { "服务配置已改变，不会向新服务发送旧任务" } }
    private fun request(config: DocumentServiceConfig, path: String) = Request.Builder().url((config.baseUrl + path).toHttpUrl()).header("Authorization", "Bearer " + vault.resolve(config.credentialReference))
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(java.io.IOException("转换服务连接失败；原件与提交记录保留，可重试")) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
    private fun readJson(response: Response): JsonObject {
        check(response.isSuccessful) { "转换服务请求失败（HTTP ${response.code}）；原件保留" }
        val bytes = response.body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 65536); output.write(buffer, 0, n) }; output.toByteArray()
        }
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    suspend fun submit(owner: String, sourceId: String, expectedSha256: String, title: String, expectedTaskId: String? = null): OfficeConversionTask = withContext(Dispatchers.IO) { mutex.withLock {
        val source = materials.get(owner, sourceId); val file = materials.verifiedFile(owner, sourceId)
        require(source.sha256 == expectedSha256 && source.extension in setOf("docx", "xlsx", "pptx")) { "原件版本不匹配或格式不支持" }
        require(title.length in 1..80 && title.none { it.isISOControl() || it in "\\/:*?\"<>|" })
        val config = config(owner)
        val previous = tasks(owner).firstOrNull {
            (expectedTaskId == null || it.id == expectedTaskId) &&
                it.sourceId == sourceId && it.sourceSha256 == expectedSha256 &&
                it.configurationKey == key(config) && !it.cancelRequested &&
                it.state !in setOf("FAILED", "CANCELLED", "EXPIRED")
        }
        require(expectedTaskId == null || previous != null) { "原转换配置已改变，未重复上传；请重新发起" }
        if (previous?.remoteId != null) return@withLock previous
        val version = previous?.rendererVersion ?: run {
            val capability = documents.capabilities(owner)["officePdf"]?.jsonObject ?: error("当前服务没有Office渲染器")
            check(capability["ready"]?.jsonPrimitive?.boolean == true) { "Office渲染器未就绪，原件保留" }
            capability.getValue("rendererVersion").jsonPrimitive.content
        }
        val task = previous ?: OfficeConversionTask(UUID.randomUUID().toString(), sourceId, expectedSha256, source.extension, title, key(config), version)
        save(owner, task)
        val url = (config.baseUrl + "/api/v1/conversions").toHttpUrl().newBuilder().addQueryParameter("extension", source.extension).build()
        val result = execute(request(config, "/api/v1/conversions").url(url).header("Idempotency-Key", task.id).header("X-Source-Sha256", expectedSha256).post(file.asRequestBody("application/octet-stream".toMediaType())).build()).use(::readJson)
        val remote = result.getValue("taskId").jsonPrimitive.content; require(UUID.fromString(remote).toString() == remote)
        check(result.getValue("operation").jsonPrimitive.content == "office_pdf")
        val rendererVersion = result.getValue("rendererVersion").jsonPrimitive.content
        require(rendererVersion.isNotBlank())
        task.copy(remoteId = remote, rendererVersion = rendererVersion,
            state = result.getValue("state").jsonPrimitive.content).also { save(owner, it) }
    } }
    suspend fun resume(owner: String, id: String): OfficeConversionTask {
        val task = task(owner, id); require(task.remoteId == null && task.state == "SUBMITTING"); taskConfig(owner, task)
        return submit(owner, task.sourceId, task.sourceSha256, task.title, id)
    }
    suspend fun refresh(owner: String, id: String): OfficeConversionTask = withContext(Dispatchers.IO) { mutex.withLock {
        val task = task(owner, id); val result = execute(request(taskConfig(owner, task), "/api/v1/jobs/${task.remoteId ?: error("提交尚未确认")}").get().build()).use(::readJson)
        require(result.getValue("operation").jsonPrimitive.content == "office_pdf")
        val state = result.getValue("state").jsonPrimitive.content; require(state in setOf("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED"))
        task.copy(state = state, errorCode = result["errorCode"]?.jsonPrimitive?.contentOrNull).also { save(owner, it) }
    } }
    suspend fun cancel(owner: String, id: String): OfficeConversionTask = withContext(Dispatchers.IO) { mutex.withLock {
        val task = task(owner, id)
        save(owner, task.copy(cancelRequested = true, state = "CANCELLING"))
        if (task.remoteId == null) {
            val result = execute(request(taskConfig(owner, task), "/api/v1/conversion-requests/${task.id}/cancel").post(ByteArray(0).toRequestBody()).build()).use(::readJson)
            val remote = result["taskId"]?.jsonPrimitive?.contentOrNull
            require(remote == null || UUID.fromString(remote).toString() == remote)
            task.copy(remoteId = remote, state = result.getValue("state").jsonPrimitive.content, cancelRequested = false).also { save(owner, it) }
        }
        else {
            val result = execute(request(taskConfig(owner, task), "/api/v1/jobs/${task.remoteId}/cancel").post(ByteArray(0).toRequestBody()).build()).use(::readJson)
            task.copy(state = result.getValue("state").jsonPrimitive.content, cancelRequested = false).also { save(owner, it) }
        }
    } }
    private fun manifest(owner: String, task: OfficeConversionTask) = json.decodeFromString<OfficePdfManifest>(File(directory(owner), "${task.id}.office-manifest.json").readText()).also { validate(it, task) }
    private fun validate(value: OfficePdfManifest, task: OfficeConversionTask) {
        require(value.contractVersion == 1 && value.operation == "office_pdf" && value.sourceSha256 == task.sourceSha256 && value.sourceExtension == task.extension && value.renderer == "LibreOffice" && value.rendererVersion == task.rendererVersion && value.renderOptions == OfficeRenderOptions())
        require(value.pageCount in 1..200 && value.warnings.size <= 20 && value.warnings.all { it.length <= 16000 })
        val asset = value.assets.single(); require(asset.assetId == "pdf" && asset.path == "output.pdf" && asset.mediaType == "application/pdf" && asset.size in 1..30L*1024*1024 && asset.sha256.matches(Regex("[a-f0-9]{64}")))
    }
    fun artifact(owner: String, id: String): MaterialFile {
        val task = task(owner, id); check(task.downloaded); val value = manifest(owner, task)
        check(materials.get(owner, task.sourceId).sha256 == task.sourceSha256); materials.verifiedFile(owner, task.sourceId)
        val item = materials.get(owner, task.outputId); materials.verifiedFile(owner, item.id)
        require(item.sourceId == task.sourceId && item.output && item.extension == "pdf" && item.sha256 == value.assets.single().sha256 && item.size == value.assets.single().size)
        return item
    }
    suspend fun download(owner: String, id: String): MaterialFile = withContext(Dispatchers.IO) { mutex.withLock {
        val task = task(owner, id); materials.verifiedFile(owner, task.sourceId); check(materials.get(owner, task.sourceId).sha256 == task.sourceSha256)
        if (task.downloaded) return@withLock artifact(owner, id)
        val config = taskConfig(owner, task); val remote = task.remoteId ?: error("提交尚未完成")
        val value = execute(request(config, "/api/v1/jobs/$remote/manifest").get().build()).use { json.decodeFromString<OfficePdfManifest>(readJson(it).toString()) }; validate(value, task)
        val asset = value.assets.single(); write(File(directory(owner), "$id.office-manifest.json"), json.encodeToString(value))
        val existing = materials.list(owner).firstOrNull { it.id == task.outputId && it.state == "SUCCEEDED" }
        val item = if (existing != null) { materials.verifiedFile(owner, existing.id); require(existing.sha256 == asset.sha256 && existing.size == asset.size); existing }
        else materials.runTask(MaterialFile(task.outputId, owner, "${task.title}.pdf", "pdf", output = true, sourceId = task.sourceId, requestKey = "office:$id")) { target ->
            execute(request(config, "/api/v1/jobs/$remote/assets/pdf").get().build()).use { response ->
                check(response.isSuccessful); response.body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(8192); var count = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; count += n; require(count <= asset.size); output.write(buffer, 0, n) }
                } }
            }
            check(target.length() == asset.size && MaterialFiles.digest(target) == asset.sha256) { "PDF下载摘要不匹配" }
            val pdf = com.artifex.mupdf.fitz.Document.openDocument(target.absolutePath)
            try { require(pdf.countPages() == value.pageCount) } finally { pdf.destroy() }
            "LibreOffice ${value.rendererVersion} 排版转换，${value.pageCount}页；" + value.warnings.joinToString("；")
        }
        save(owner, task.copy(state = "SUCCEEDED", downloaded = true, cleanupPending = true))
        try { cleanupUnlocked(owner, id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Verified file remains deliverable; background retries cleanup. */ }
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
                if (task.cancelRequested) cancel(owner, task.id)
                else if (task.cleanupPending) cleanupRemote(owner, task.id)
                else if (task.remoteId != null && !task.downloaded && task.state in setOf("QUEUED", "RUNNING", "SUCCEEDED")) {
                    val current = refresh(owner, task.id); if (current.state == "SUCCEEDED") download(owner, task.id)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* No background re-upload or invented success. */ }
        }
    }
}
