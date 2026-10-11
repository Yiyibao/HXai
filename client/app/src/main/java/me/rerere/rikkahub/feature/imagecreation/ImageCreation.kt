package me.rerere.rikkahub.feature.imagecreation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.sync.PendingRestore
import me.rerere.rikkahub.feature.material.MaterialFile
import me.rerere.rikkahub.feature.material.MaterialFiles
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MultipartBody
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class ImageCreationTask(
    val id: String, val profileId: String, val conversationId: String,
    val prompt: String, val references: List<String> = emptyList(),
    val parentId: String? = null, val rootId: String = id, val revision: Int = 1,
    val size: String = "1024x1024", val quality: String = "low",
    val model: String, val state: String = "RUNNING", val error: String? = null,
    val artifactId: String? = null, val createdAt: Long = System.currentTimeMillis(),
) {
    val messageId: String get() = UUID.nameUUIDFromBytes("image-assistant:$id".toByteArray()).toString()
}

/** Stateless Responses requests: no server storage or automatic paid retries. */
class ImageCreationFailure(message: String) : IllegalStateException(message)

object ImageCreationProtocol {
    fun imageBody(model: String, prompt: String, size: String, quality: String): JsonObject {
        body(model, prompt, size, quality, emptyList()) // same bounded parameter validation
        return buildJsonObject { put("model", model); put("prompt", prompt); put("n", 1); put("size", size); put("quality", quality); put("output_format", "png") }
    }
    fun imageResult(body: String): String {
        val response = Json.parseToJsonElement(body).jsonObject
        if (response["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "upstream_error")
            throw ImageCreationFailure("图片服务暂时不可用，请稍后手动重试。")
        return response["data"]?.jsonArray?.singleOrNull()?.jsonObject?.get("b64_json")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("服务未返回单张最终图片")
    }
    fun body(model: String, prompt: String, size: String, quality: String, images: List<String>): JsonObject {
        require(prompt.isNotBlank() && prompt.length <= 8000)
        require(size in setOf("1024x1024", "1536x1024", "1024x1536"))
        require(quality in setOf("low", "medium", "high"))
        require(images.size <= 4)
        return buildJsonObject {
            put("model", model); put("store", false); put("stream", false)
            putJsonObject("reasoning") { put("effort", "low") }
            putJsonArray("input") { add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "input_text"); put("text", "请实际生成且只生成一张图片。$prompt") })
                    images.forEach { image -> add(buildJsonObject { put("type", "input_image"); put("image_url", image) }) }
                }
            }) }
            putJsonArray("tools") { add(buildJsonObject {
                put("type", "image_generation"); put("size", size); put("quality", quality)
                put("output_format", "png")
            }) }
            putJsonObject("tool_choice") { put("type", "image_generation") }
        }
    }
    fun result(body: String): String {
        val response = Json.parseToJsonElement(body).jsonObject
        require(response["status"]?.jsonPrimitive?.content == "completed") { "服务未完成生成" }
        return response["output"]?.jsonArray.orEmpty().map { it.jsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.content == "image_generation_call" &&
                it["status"]?.jsonPrimitive?.content == "completed" }
            ?.get("result")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("服务未返回最终图片")
    }
}

class ImageCreation internal constructor(
    private val context: Context, private val settings: SettingsStore, private val materials: MaterialFiles,
    private val configuration: () -> me.rerere.rikkahub.data.datastore.Settings = { settings.settingsFlow.value },
    private val root: File = context.filesDir.resolve("image_creation"),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES).retryOnConnectionFailure(false).build()
    private val keys = KeyRoulette.lru(context)
    init {
        root.listFiles().orEmpty().filter { MaterialFiles.validProfile(it.name) }.forEach { dir ->
            list(dir.name).filter { it.state == "RUNNING" }.forEach { record(it.copy(state = "INTERRUPTED", error = "上次生成已中断，服务结果未知；请手动重试。")) }
        }
    }
    private fun directory(owner: String): File {
        require(MaterialFiles.validProfile(owner))
        return root.resolve(owner).apply { mkdirs() }
    }
    @Synchronized fun list(owner: String): List<ImageCreationTask> = directory(owner).listFiles().orEmpty()
        .filter { it.extension == "json" }.mapNotNull { f -> runCatching { json.decodeFromString<ImageCreationTask>(f.readText()) }.getOrNull()
            ?.takeIf { it.profileId == owner && f.name == "${it.id}.json" } }.sortedByDescending { it.createdAt }
    fun get(owner: String, id: String) = list(owner).singleOrNull { it.id == id } ?: error("图片不属于当前档案")
    @Synchronized private fun record(task: ImageCreationTask) {
        UUID.fromString(task.id)
        PendingRestore.writeDurably(directory(task.profileId).resolve("${task.id}.json"), json.encodeToString(task))
    }
    fun modelInfo(): Map<String, Any> {
        val config = configuration()
        val model = config.findModelById(config.imageGenerationModelId)
        val provider = model?.findProvider(config.providers)
        val direct = model?.modelId?.startsWith("gpt-image") == true || (provider is ProviderSetting.OpenAI && !provider.useResponseApi)
        return mapOf("ready" to (model != null && provider is ProviderSetting.OpenAI && provider.enabled && provider.apiKey.isNotBlank()),
            "model" to (model?.modelId ?: "未选择模型"), "reasoning" to if (direct) "none" else "low", "protocol" to if (direct) "images" else "responses")
    }
    @Synchronized fun start(owner: String, conversationId: String, requestId: String, prompt: String,
        references: List<String>, parentId: String?, size: String, quality: String,
        onChange: suspend (ImageCreationTask) -> Unit,
    ): ImageCreationTask {
        require(requestId.isNotBlank() && requestId.length <= 200)
        val id = UUID.nameUUIDFromBytes("image:$owner:$requestId".toByteArray()).toString()
        list(owner).firstOrNull { it.id == id }?.let {
            require(it.prompt == prompt && it.conversationId == conversationId && it.parentId == parentId && it.references == references && it.size == size && it.quality == quality) { "请求标识内容已改变" }
            return it
        }
        require(list(owner).none { it.state == "RUNNING" }) { "当前档案已有图片正在生成" }
        val config = configuration()
        val model = config.findModelById(config.imageGenerationModelId) ?: error("请先选择图片创作模型")
        val provider = model.findProvider(config.providers) as? ProviderSetting.OpenAI ?: error("图片创作需使用 OpenAI 兼容提供商")
        require(provider.enabled && provider.apiKey.isNotBlank()) { "请启用提供商并配置模型凭据" }
        val parent = parentId?.let { get(owner, it).also { t -> require(t.state == "SUCCEEDED"); require(t.conversationId == conversationId) } }
        val allRefs = (listOfNotNull(parent?.artifactId) + references).distinct()
        require(allRefs.size <= 4)
        val direct = model.modelId.startsWith("gpt-image") || !provider.useResponseApi
        val referenceFiles = allRefs.map { ref ->
            val file = materials.verifiedFile(owner, ref)
            require(file.extension in setOf("png", "jpg", "jpeg", "webp") && file.length() <= 8 * 1024 * 1024) { "参考图格式无效或超过 8 MB" }
            file
        }
        val effectiveSize = parent?.size ?: size
        // Direct edits stream the files as multipart, without a redundant base64 copy.
        val body = if (direct) ImageCreationProtocol.imageBody(model.modelId, prompt, effectiveSize, quality)
            else ImageCreationProtocol.body(model.modelId, prompt, effectiveSize, quality, referenceFiles.map { file ->
                val mime = if (file.extension in setOf("jpg", "jpeg")) "image/jpeg" else "image/${file.extension}"
                "data:$mime;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            })
        val task = ImageCreationTask(id, owner, conversationId, prompt, references, parentId, parent?.rootId ?: id,
            (list(owner).filter { it.rootId == parent?.rootId }.maxOfOrNull { it.revision } ?: 0) + 1,
            effectiveSize, quality, model.modelId)
        record(task)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                onChange(task)
                val requestBody = if (!direct) body.toString().toRequestBody("application/json".toMediaType())
                    else if (allRefs.isEmpty()) body.toString().toRequestBody("application/json".toMediaType())
                    else MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                        addFormDataPart("model", model.modelId); addFormDataPart("prompt", prompt)
                        addFormDataPart("n", "1"); addFormDataPart("size", effectiveSize); addFormDataPart("quality", quality); addFormDataPart("output_format", "png")
                        allRefs.forEach { ref -> val file = materials.verifiedFile(owner, ref)
                            val mime = if (file.extension in setOf("jpg", "jpeg")) "image/jpeg" else "image/${file.extension}"
                            addFormDataPart(if (allRefs.size == 1) "image" else "image[]", file.name, file.asRequestBody(mime.toMediaType()))
                        }
                    }.build()
                val endpoint = if (!direct) provider.responsesPath else if (allRefs.isEmpty()) "/images/generations" else "/images/edits"
                val request = Request.Builder().url(provider.baseUrl.trimEnd('/') + endpoint)
                    .header("Authorization", "Bearer ${keys.next(provider.apiKey, provider.id.toString())}")
                    .header("User-Agent", "MathStudyAgent/1.0")
                    .post(requestBody).build()
                val call = client.newCall(request).also { calls[id] = it }
                ensureActive()
                val encoded = call.await().use { response ->
                    if (response.code in setOf(401, 403)) throw ImageCreationFailure("图片服务凭据不可用，请检查提供商配置。")
                    if (response.code == 429) throw ImageCreationFailure("图片服务达到额度或频率限制，请稍后手动重试。")
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val source = response.body.source(); require(source.request(48L * 1024 * 1024).not()) { "图片响应过大" }
                    if (direct) ImageCreationProtocol.imageResult(source.readUtf8()) else ImageCreationProtocol.result(source.readUtf8())
                }
                require(encoded.length <= 42 * 1024 * 1024)
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) { "图片尺寸无效" }
                ensureActive()
                val artifact = materials.runTask(MaterialFile(id, owner, "${prompt.take(24).replace(Regex("[\\\\/:*?\"<>|]"), "_")}.png", "png", output = true, sourceId = parent?.artifactId)) { file ->
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片解码失败")
                    try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
                    "AI 生成图片；模型 ${model.modelId}，$quality 质量"
                }
                val completed = task.copy(state = "SUCCEEDED", artifactId = artifact.id)
                record(completed); onChange(completed)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { val cancelled = task.copy(state = "CANCELLED", error = "已停止接收生成结果；远端可能已产生费用。")
                    if (get(owner, id).state != "SUCCEEDED") { record(cancelled); runCatching { onChange(cancelled) } } }
            } catch (e: Exception) {
                val failed = if (!isActive) task.copy(state = "CANCELLED", error = "已停止接收生成结果；远端可能已产生费用。")
                    else task.copy(state = "FAILED", error = if (e is ImageCreationFailure) e.message else "图片生成未完成，请检查模型、接口或网络后手动重试。")
                if (get(owner, id).state != "SUCCEEDED") { record(failed); withContext(NonCancellable) { runCatching { onChange(failed) } } }
            } finally { calls.remove(id)?.cancel(); jobs.remove(id) }
        }
        jobs[id] = job; job.start()
        return task
    }
    fun cancel(owner: String, id: String) { require(get(owner, id).state == "RUNNING"); jobs[id]?.cancel(); calls[id]?.cancel() }
    fun registerToolImage(owner: String, conversationId: String, artifact: MaterialFile, prompt: String, model: String) {
        require(artifact.profileId == owner && artifact.state == "SUCCEEDED" && artifact.extension == "png")
        materials.verifiedFile(owner, artifact.id)
        record(ImageCreationTask(artifact.id, owner, conversationId, prompt, model = model, state = "SUCCEEDED", artifactId = artifact.id))
    }
    fun toMap(task: ImageCreationTask): Map<String, Any?> = mapOf(
        "id" to task.id, "conversationId" to task.conversationId, "messageId" to task.messageId,
        "prompt" to task.prompt, "references" to task.references, "parentId" to task.parentId,
        "rootId" to task.rootId, "revision" to task.revision, "size" to task.size, "quality" to task.quality,
        "model" to task.model, "state" to task.state, "error" to task.error, "artifactId" to task.artifactId,
        "createdAt" to task.createdAt, "path" to task.artifactId?.let { runCatching { materials.verifiedFile(task.profileId, it).absolutePath }.getOrNull() },
    )
    companion object {
        @Volatile private var instance: ImageCreation? = null
        fun get(context: Context, settings: SettingsStore, materials: MaterialFiles): ImageCreation = instance ?: synchronized(this) {
            instance ?: ImageCreation(context.applicationContext, settings, materials).also { instance = it }
        }
    }
}
