package me.rerere.rikkahub.feature.material

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.CredentialVault
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class DocumentServiceConfig(val enabled: Boolean = false, val baseUrl: String = "", val credentialReference: String = "")
@Serializable data class DocumentTask(
    val id: String, val sourceId: String, val sourceSha256: String, val start: Int, val end: Int,
    val configurationKey: String, val remoteId: String? = null, val state: String = "SUBMITTING",
    val errorCode: String? = null, val downloaded: Boolean = false, val cleanupPending: Boolean = false,
    val cancelRequested: Boolean = false, val cancelConfirmed: Boolean = false,
)
@Serializable data class DocumentBatch(val id: String, val sourceId: String, val sourceSha256: String, val start: Int, val end: Int, val configurationKey: String, val cancelled: Boolean = false, val taskIds: List<String> = emptyList())

/** Separate protected configuration; neither secrets nor references enter ordinary backups. */
class DocumentServiceRepository(private val context: Context, private val materials: MaterialFiles) {
    val officeConversions by lazy { OfficeConversionRepository(context, materials, this) }
    private val json = Json { ignoreUnknownKeys = false }
    private val vault = CredentialVault(context)
    private val mutex = Mutex()
    private val batchMutex = Mutex()
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addInterceptor(me.rerere.rikkahub.feature.diagnostics.DiagnosticsNetworkInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()
    private fun directory(owner: String): File {
        require(MaterialFiles.validProfile(owner))
        return File(context.noBackupFilesDir, "document-service/$owner").apply { mkdirs() }
    }
    @Synchronized fun config(owner: String): DocumentServiceConfig {
        val file = AtomicFile(File(directory(owner), "config.json"))
        return if (file.baseFile.exists()) json.decodeFromString(file.readFully().toString(Charsets.UTF_8)) else DocumentServiceConfig()
    }
    private fun write(file: File, value: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(value.toByteArray()); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    @Synchronized fun configure(owner: String, baseUrl: String, token: String, enabled: Boolean) {
        val previous = config(owner)
        val url = DocumentContract.endpoint(baseUrl).toString().trimEnd('/')
        val reference = if (token.isNotBlank()) {
            require(token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "无效服务凭据" }
            vault.protect(token, previous.credentialReference.takeIf { it.isNotBlank() })
        } else previous.credentialReference
        require(!enabled || reference.isNotBlank()) { "需要管理员提供服务凭据" }
        if (enabled) {
            val value = vault.resolve(reference)
            require(configuredOwners().filter { it != owner }.none { other ->
                val otherConfig = config(other)
                otherConfig.credentialReference.isNotBlank() && vault.resolve(otherConfig.credentialReference) == value
            }) { "每个学习档案需要独立服务凭据" }
        }
        write(File(directory(owner), "config.json"), json.encodeToString(DocumentServiceConfig(enabled, url, reference)))
        changes.value++
    }
    fun enabled(owner: String) = config(owner).enabled
    @Synchronized fun batch(owner: String, id: String): DocumentBatch {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return json.decodeFromString<DocumentBatch>(AtomicFile(File(directory(owner), "$id.batch.json")).readFully().toString(Charsets.UTF_8))
    }
    fun batchTasks(owner: String, batch: DocumentBatch): List<DocumentTask> = batch.taskIds.map { id -> task(owner, id).also {
        require(it.sourceId == batch.sourceId && it.sourceSha256 == batch.sourceSha256 && it.configurationKey == batch.configurationKey && it.start in batch.start..batch.end && (it.start - batch.start) % 5 == 0 && it.end == minOf(it.start + 4, batch.end))
    } }.sortedBy { it.start }
    fun findBatch(owner: String, sourceId: String, start: Int, end: Int): DocumentBatch? = directory(owner).listFiles().orEmpty()
        .filter { it.name.endsWith(".batch.json") }.sortedByDescending { it.lastModified() }
        .map { batch(owner, it.name.removeSuffix(".batch.json")) }.firstOrNull { it.sourceId == sourceId && it.start == start && it.end == end && !it.cancelled }
    suspend fun submitBatch(owner: String, sourceId: String, start: Int, end: Int): Pair<DocumentBatch, DocumentTask> = batchMutex.withLock {
        require(start in 1..200 && end in start..200)
        val source = materials.get(owner, sourceId); val key = configurationKey(requireConfig(owner))
        require(source.extension == "pdf")
        val doc = com.artifex.mupdf.fitz.Document.openDocument(materials.verifiedFile(owner, sourceId).absolutePath)
        try { require(!doc.needsPassword() && end <= doc.countPages()) } finally { doc.destroy() }
        val previous = directory(owner).listFiles().orEmpty().filter { it.name.endsWith(".batch.json") }.map { batch(owner, it.name.removeSuffix(".batch.json")) }.firstOrNull {
            it.sourceId == sourceId && it.sourceSha256 == source.sha256 && it.start == start && it.end == end && it.configurationKey == key && !it.cancelled && batchTasks(owner, it).none { task -> task.cancelRequested || (task.state in setOf("FAILED", "CANCELLED", "EXPIRED") && !task.downloaded) || (task.downloaded && runCatching { read(owner, task.id) }.isFailure) }
        }
        val id = previous?.id ?: me.rerere.rikkahub.feature.interactive.InteractiveSurface.hash("batch-v2:$sourceId:${source.sha256}:$start:$end:$key:${UUID.randomUUID()}")
        if (previous == null) saveBatch(owner, DocumentBatch(id, sourceId, source.sha256, start, end, key))
        val first = submit(owner, sourceId, start, minOf(start + 4, end), batchId = id)
        batch(owner, id) to first
    }
    /** Sequential batches stay under the server's identity queue limit; every resume checks upload policy at caller. */
    suspend fun advanceBatch(owner: String, id: String, allowUpload: () -> Unit): DocumentTask? = batchMutex.withLock {
        val batch = batch(owner, id)
        check(!batch.cancelled) { "批任务已取消" }
        check(materials.get(owner, batch.sourceId).sha256 == batch.sourceSha256 && configurationKey(requireConfig(owner)) == batch.configurationKey) { "源版本或服务配置改变" }
        val existing = batchTasks(owner, batch)
        for (start in batch.start..batch.end step 5) {
            val current = existing.firstOrNull { it.start == start }
            if (current == null) {
                allowUpload()
                val next = submit(owner, batch.sourceId, start, minOf(start + 4, batch.end), batchId = id)
                return@withLock next
            }
            check(!current.cancelRequested && current.state !in setOf("FAILED", "CANCELLED", "EXPIRED")) { "第${current.start}–${current.end}页失败或取消；不会自动重复上传" }
            if (!current.downloaded) return@withLock current
            val parsed = read(owner, current.id)
            check(parsed.failedPages.isEmpty() && parsed.processedPages.toSet() == (current.start..current.end).toSet()) { "已下载批次有缺失页，停止续批" }
        }
        null
    }
    suspend fun cancelBatch(owner: String, id: String) {
        // Record every child before attempting any network call. One offline child
        // must not prevent cancellation of the remaining children after restart.
        val children = synchronized(this) {
            val value = batch(owner, id)
            saveBatch(owner, value.copy(cancelled = true))
            batchTasks(owner, value).map { requestCancellation(owner, it.id) }
        }
        var failure: Exception? = null
        for (child in children) {
            try { cancel(owner, child.id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (failure == null) failure = e }
        }
        failure?.let { throw it }
    }
    @Synchronized private fun saveBatch(owner: String, value: DocumentBatch) {
        val file = File(directory(owner), "${value.id}.batch.json")
        val previous = if (file.exists()) batch(owner, value.id) else null
        write(file, json.encodeToString(value.copy(cancelled = value.cancelled || previous?.cancelled == true)))
        changes.value++
    }
    private fun batches(owner: String): List<DocumentBatch> = directory(owner).listFiles().orEmpty()
        .filter { it.name.endsWith(".batch.json") }.map { batch(owner, it.name.removeSuffix(".batch.json")) }
    private fun requireNotCancelled(owner: String, value: DocumentTask) {
        check(!value.cancelRequested && value.state !in setOf("CANCELLING", "CANCELLED")) { "任务已请求取消，不会恢复上传" }
        check(batches(owner).none { it.cancelled && value.id in it.taskIds }) { "批任务已取消，不会恢复上传" }
    }
    private fun configurationKey(config: DocumentServiceConfig): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((config.baseUrl + config.credentialReference).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun requireConfig(owner: String): DocumentServiceConfig = config(owner).also {
        check(it.enabled && it.credentialReference.isNotBlank()) { "请先为当前档案启用可信自建文档服务" }
    }
    private fun request(config: DocumentServiceConfig, route: String): Request.Builder = Request.Builder()
        .url((config.baseUrl + route).toHttpUrl()).header("Authorization", "Bearer " + vault.resolve(config.credentialReference))
    private suspend fun execute(request: Request, beforeEnqueue: (() -> Unit)? = null): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("文档服务连接失败；原文件保留，可重试"))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        }
        if (beforeEnqueue == null) call.enqueue(callback)
        else synchronized(this@DocumentServiceRepository) {
            // Cancellation writes use this same monitor. Once intent is durable,
            // no later upload can enqueue; do not hold the monitor for the reply.
            beforeEnqueue()
            call.enqueue(callback)
        }
    }
    private fun readJson(response: Response, limit: Int = 2 * 1024 * 1024): JsonObject {
        check(response.isSuccessful) { "文档服务请求失败（HTTP ${response.code}）；请核对服务配置及任务状态" }
        val body = response.body
        body.byteStream().use { input ->
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= limit) { "服务结果超过限制" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            require(bytes.size <= limit) { "服务结果超过限制" }
            return json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
    }
    suspend fun capabilities(owner: String): JsonObject = withContext(Dispatchers.IO) {
        execute(request(requireConfig(owner), "/api/v1/capabilities").get().build()).use { readJson(it) }
    }
    @Synchronized private fun save(owner: String, task: DocumentTask): DocumentTask {
        val previous = tasks(owner).firstOrNull { it.id == task.id }
        val requested = task.cancelRequested || previous?.cancelRequested == true
        val confirmed = task.cancelConfirmed || previous?.cancelConfirmed == true
        val value = task.copy(cancelRequested = requested, cancelConfirmed = confirmed,
            state = if (requested && !confirmed) "CANCELLING" else task.state)
        write(File(directory(owner), "${task.id}.task.json"), json.encodeToString(value))
        changes.value++
        return value
    }
    @Synchronized private fun requestCancellation(owner: String, id: String): DocumentTask {
        val value = task(owner, id)
        return save(owner, value.copy(cancelRequested = true))
    }
    @Synchronized fun tasks(owner: String): List<DocumentTask> = directory(owner).listFiles().orEmpty().filter { it.name.endsWith(".task.json") }
        .map { json.decodeFromString<DocumentTask>(AtomicFile(it).readFully().toString(Charsets.UTF_8)) }
    fun availableResults(owner: String, sourceId: String, offset: Int, count: Int): JsonObject {
        require(offset >= 0 && count in 1..20)
        val source = materials.get(owner, sourceId)
        materials.verifiedFile(owner, sourceId)
        val currentConfiguration = configurationKey(config(owner))
        val entries = tasks(owner).filter { it.sourceId == sourceId && it.sourceSha256 == source.sha256 && !it.cancelRequested }
            .sortedBy { it.id }.mapNotNull { task ->
                val document = if (task.downloaded) runCatching { read(owner, task.id) }.getOrNull() ?: return@mapNotNull null else null
                if (document == null && (task.configurationKey != currentConfiguration || task.state !in setOf("SUBMITTING", "QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL"))) return@mapNotNull null
                buildJsonObject {
                    put("task_id", task.id); put("state", task.state); put("downloaded", document != null)
                    put("start_page", task.start); put("end_page", task.end)
                    document?.let { put("processed_pages", JsonArray(it.processedPages.map(::JsonPrimitive))); put("complete", it.complete) }
                }
            }
        val end = (offset.toLong() + count).coerceAtMost(entries.size.toLong()).toInt()
        require(offset <= entries.size)
        return buildJsonObject {
            put("source_id", sourceId); put("source_sha256", source.sha256)
            put("results", JsonArray(entries.subList(offset, end)))
            if (end < entries.size) put("next_offset", end)
        }
    }
    private fun task(owner: String, id: String): DocumentTask = tasks(owner).firstOrNull { it.id == id } ?: error("任务不属于当前档案")
    private fun taskConfig(owner: String, task: DocumentTask) = requireConfig(owner).also {
        check(configurationKey(it) == task.configurationKey) { "服务配置已变更；旧任务不能发送到新服务" }
    }
    suspend fun submit(owner: String, sourceId: String, start: Int, end: Int, expectedTaskId: String? = null, batchId: String? = null): DocumentTask = withContext(Dispatchers.IO) {
        mutex.withLock {
            expectedTaskId?.let { requireNotCancelled(owner, task(owner, it)) }
            batchId?.let { check(!batch(owner, it).cancelled) { "批任务已取消，不会上传" } }
            DocumentContract.range(start, end)
            val source = materials.get(owner, sourceId)
            require(source.extension in setOf("pdf", "png", "jpg", "jpeg", "webp")) { "第一批增强解析仅支持 PDF 和图片" }
            require(source.extension == "pdf" || (start == 1 && end == 1))
            val file = materials.verifiedFile(owner, sourceId)
            val config = requireConfig(owner)
            val configuration = configurationKey(config)
            val existingBatches = batches(owner)
            val previous = tasks(owner).firstOrNull { it.sourceSha256 == source.sha256 && it.sourceId == sourceId && it.start == start && it.end == end && it.configurationKey == configuration && !it.cancelRequested && it.state !in setOf("FAILED", "CANCELLING", "CANCELLED", "EXPIRED") && existingBatches.none { batch -> batch.cancelled && it.id in batch.taskIds } && (!it.downloaded || runCatching { read(owner, it.id) }.isSuccess) }
            require(expectedTaskId == null || previous?.id == expectedTaskId) { "原任务配置已改变，未重新上传；请重新发起任务" }
            val task = previous ?: DocumentTask(UUID.randomUUID().toString(), sourceId, source.sha256, start, end, configuration)
            synchronized(this@DocumentServiceRepository) {
                val batch = batchId?.let { batch(owner, it) }
                check(batch?.cancelled != true) { "批任务已取消，不会上传" }
                requireNotCancelled(owner, task)
                save(owner, task)
                // A batch must never reference an unwritten task, and both records
                // must exist before the server can accept the upload.
                if (batch != null && task.id !in batch.taskIds) saveBatch(owner, batch.copy(taskIds = batch.taskIds + task.id))
            }
            if (task.remoteId != null) return@withLock task
            val url = (config.baseUrl + "/api/v1/jobs").toHttpUrl().newBuilder()
                .addQueryParameter("extension", source.extension).addQueryParameter("start_page", start.toString()).addQueryParameter("end_page", end.toString()).build()
            val result = execute(request(config, "/api/v1/jobs").url(url)
                .header("Idempotency-Key", task.id).header("X-Source-Sha256", source.sha256)
                .post(file.asRequestBody("application/octet-stream".toMediaType())).build()) {
                    requireNotCancelled(owner, task(owner, task.id))
                    batchId?.let { check(!batch(owner, it).cancelled) { "批任务已取消，不会上传" } }
                }.use { readJson(it) }
            val remote = result.getValue("taskId").jsonPrimitive.content
            require(UUID.fromString(remote).toString() == remote)
            save(owner, task.copy(remoteId = remote, state = result.getValue("state").jsonPrimitive.content))
        }
    }
    suspend fun resumeSubmission(owner: String, id: String): DocumentTask {
        val task = task(owner, id)
        requireNotCancelled(owner, task)
        require(task.state == "SUBMITTING" && task.remoteId == null)
        taskConfig(owner, task)
        return submit(owner, task.sourceId, task.start, task.end, expectedTaskId = id)
    }
    suspend fun refresh(owner: String, id: String): DocumentTask = withContext(Dispatchers.IO) {
        mutex.withLock {
            val task = task(owner, id); val config = taskConfig(owner, task)
            if (task.cancelRequested) return@withLock cancelUnlocked(owner, task)
            val remote = task.remoteId ?: error("提交尚未确认；请按相同材料与范围重试")
            val result = execute(request(config, "/api/v1/jobs/$remote").get().build()).use { readJson(it) }
            val state = result.getValue("state").jsonPrimitive.content
            require(state in setOf("QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "EXPIRED"))
            if (state == "FAILED") me.rerere.rikkahub.feature.diagnostics.Diagnostics.record(
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.DOCUMENT,
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.FAILED)
            save(owner, task.copy(state = state, errorCode = result["errorCode"]?.jsonPrimitive?.contentOrNull))
        }
    }
    suspend fun cancel(owner: String, id: String): DocumentTask = withContext(Dispatchers.IO) {
        requestCancellation(owner, id)
        mutex.withLock { cancelUnlocked(owner, task(owner, id)) }
    }
    private suspend fun cancelUnlocked(owner: String, task: DocumentTask): DocumentTask {
        if (task.cancelConfirmed) return task
        val config = taskConfig(owner, task)
        val route = task.remoteId?.let { "/api/v1/jobs/$it/cancel" }
            ?: "/api/v1/parse-requests/${task.id}/cancel"
        val result = execute(request(config, route).post(ByteArray(0).toRequestBody()).build()).use { readJson(it) }
        val remote = result["taskId"]?.jsonPrimitive?.contentOrNull ?: task.remoteId
        require(remote == null || UUID.fromString(remote).toString() == remote)
        val state = result.getValue("state").jsonPrimitive.content
        require(state in setOf("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "EXPIRED"))
        return save(owner, task.copy(remoteId = remote, state = state, cancelRequested = true, cancelConfirmed = true))
    }
    suspend fun download(owner: String, id: String): ParsedDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val task = task(owner, id)
            check(!task.cancelRequested) { "任务已请求取消，不会下载或交付" }
            materials.verifiedFile(owner, task.sourceId)
            check(materials.get(owner, task.sourceId).sha256 == task.sourceSha256) { "原文件版本已改变" }
            if (task.downloaded) return@withLock read(owner, id)
            val config = taskConfig(owner, task); val remote = task.remoteId ?: error("提交未完成")
            val manifestJson = execute(request(config, "/api/v1/jobs/$remote/manifest").get().build()).use { readJson(it) }
            val manifest = json.decodeFromString<DocumentManifest>(manifestJson.toString())
            DocumentContract.validate(manifest, task.sourceSha256, task.start, task.end)
            val staging = File(directory(owner), "$id.pending").apply { deleteRecursively(); mkdirs() }
            try {
                for (asset in manifest.assets) {
                    currentCoroutineContext().ensureActive()
                    val target = File(staging, asset.assetId)
                    execute(request(config, "/api/v1/jobs/$remote/assets/${asset.assetId}").get().build()).use { response ->
                        check(response.isSuccessful) { "结果下载失败" }
                        val input = response.body.byteStream()
                        input.use { stream -> target.outputStream().use { out ->
                            val buffer = ByteArray(8192); var count = 0L
                            while (true) { currentCoroutineContext().ensureActive(); val n = stream.read(buffer); if (n < 0) break; count += n; require(count <= asset.size); out.write(buffer, 0, n) }
                        } }
                    }
                    check(target.length() == asset.size && MaterialFiles.digest(target) == asset.sha256) { "结果摘要不匹配" }
                    if (asset.mediaType == "image/png") {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(target.path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000)
                    }
                }
                val document = json.decodeFromString<ParsedDocument>(File(staging, "document").readText())
                validateDocument(document, manifest)
                File(staging, "manifest.json").writeText(manifestJson.toString())
                val committed = File(directory(owner), "$id.result")
                if (committed.exists()) committed.deleteRecursively()
                check(staging.renameTo(committed)) { "结果导入失败" }
                save(owner, task.copy(downloaded = true, cleanupPending = true))
                // A cleanup failure cannot turn a verified local download into a failure.
                try { cleanupRemoteUnlocked(owner, id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* Retry cleanup in the background. */ }
                document
            } finally { staging.deleteRecursively() }
        }
    }
    private fun validateDocument(document: ParsedDocument, manifest: DocumentManifest) {
        require(document.sourceSha256 == manifest.sourceSha256 && document.requestedRange == manifest.requestedRange && document.processedPages == manifest.processedPages && document.complete == manifest.complete)
        require(document.contractVersion == 1 && document.tier == manifest.tier && document.parserVersion == manifest.parserVersion && document.resultRevision == manifest.resultRevision)
        require(document.blocks.size <= 10000 && document.blocks.sumOf { it.text.length } <= 1_000_000)
        require(document.blocks.map { it.blockId }.distinct().size == document.blocks.size)
        document.blocks.forEach { require(it.page in document.processedPages && it.blockId.matches(Regex("p[0-9]+-b[0-9.]+")) && (it.assetId == null || manifest.assets.any { a -> a.assetId == it.assetId })) }
    }
    fun read(owner: String, id: String): ParsedDocument {
        val task = task(owner, id); check(task.downloaded) { "结果尚未下载" }
        materials.verifiedFile(owner, task.sourceId)
        check(materials.get(owner, task.sourceId).sha256 == task.sourceSha256)
        val directory = File(directory(owner), "$id.result")
        val manifest = json.decodeFromString<DocumentManifest>(File(directory, "manifest.json").readText())
        DocumentContract.validate(manifest, task.sourceSha256, task.start, task.end)
        val asset = manifest.assets.single { it.assetId == "document" }
        val file = File(directory, "document")
        check(file.length() == asset.size && MaterialFiles.digest(file) == asset.sha256)
        return json.decodeFromString<ParsedDocument>(file.readText()).also { validateDocument(it, manifest) }
    }
    suspend fun cleanupRemote(owner: String, id: String) = mutex.withLock { cleanupRemoteUnlocked(owner, id) }
    private suspend fun cleanupRemoteUnlocked(owner: String, id: String) {
        val task = task(owner, id); val config = taskConfig(owner, task); val remote = task.remoteId ?: return
        execute(request(config, "/api/v1/jobs/$remote").delete().build()).use { readJson(it) }
        val status = execute(request(config, "/api/v1/jobs/$remote").get().build()).use { readJson(it) }
        if (status.getValue("state").jsonPrimitive.content == "EXPIRED") save(owner, task.copy(cleanupPending = false))
        // DELETE is asynchronous; retain the pending marker until status reports EXPIRED.
    }
    suspend fun sourcePage(owner: String, id: String, blockId: String): List<me.rerere.ai.ui.UIMessagePart> {
        val document = read(owner, id)
        val block = document.blocks.singleOrNull { it.blockId == blockId } ?: error("来源块不存在")
        val task = task(owner, id)
        val source = materials.get(owner, task.sourceId)
        return if (source.extension == "pdf") renderMaterialPdfPages(materials, owner, source.id, block.page, 1)
        else listOf(me.rerere.ai.ui.UIMessagePart.Text("原材料图片；识别结果需要核对"), me.rerere.ai.ui.UIMessagePart.Image(materials.verifiedFile(owner, source.id).toUri().toString()))
    }
    suspend fun blockImage(owner: String, id: String, blockId: String): File = withContext(Dispatchers.IO) {
        val document = read(owner, id)
        val block = document.blocks.singleOrNull { it.blockId == blockId } ?: error("来源块不存在")
        val assetId = block.assetId ?: error("此块没有独立图片，请查看原页")
        val directory = File(directory(owner), "$id.result")
        val manifest = json.decodeFromString<DocumentManifest>(File(directory, "manifest.json").readText())
        val asset = manifest.assets.single { it.assetId == assetId && it.mediaType == "image/png" }
        File(directory, assetId).also { check(it.length() == asset.size && MaterialFiles.digest(it) == asset.sha256) }
    }
    fun configuredOwners(): List<String> = File(context.noBackupFilesDir, "document-service").listFiles().orEmpty()
        .filter { it.isDirectory && MaterialFiles.validProfile(it.name) }.map { it.name }
    suspend fun syncPending(owner: String) {
        if (!enabled(owner)) return
        officeConversions.syncPending(owner)
        for (task in tasks(owner)) {
            currentCoroutineContext().ensureActive()
            try {
                val cancelledBatch = batches(owner).any { it.cancelled && task.id in it.taskIds }
                if (task.cancelRequested || cancelledBatch) {
                    if (!task.cancelConfirmed) cancel(owner, task.id)
                    if (task.cleanupPending) cleanupRemote(owner, task.id)
                }
                else if (task.cleanupPending) cleanupRemote(owner, task.id)
                // Unconfirmed uploads require the foreground chat's current branch
                // and latest human permissions. This worker has neither; retain the
                // intent for the guarded foreground recovery instead of re-uploading.
                else if (task.remoteId != null && !task.downloaded && task.state in setOf("QUEUED", "RUNNING", "SUCCEEDED", "PARTIAL")) {
                    val refreshed = refresh(owner, task.id)
                    if (refreshed.state in setOf("SUCCEEDED", "PARTIAL")) download(owner, task.id)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.record(
                    me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.DOCUMENT,
                    me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.FAILED)
                /* Keep durable intent; a later connected run retries. */
            }
        }
    }
}
