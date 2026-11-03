package me.rerere.rikkahub.feature.material

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable data class MaterialFile(
    val id: String, val profileId: String, val name: String, val extension: String,
    val output: Boolean = false, val state: String = "PENDING", val detail: String = "等待处理",
    val size: Long = 0, val sha256: String = "", val createdAt: Long = System.currentTimeMillis(),
    val processingState: String = "NOT_STARTED",
    val requestKey: String = "",
    val sourceId: String? = null, val pageNumber: Int? = null,
    val symbolCorrection: String? = null, val symbolSourceSha256: String? = null,
    val symbolCheckPending: Boolean = false,
)

/** No model supplied path or identity is accepted. Each task closes over its original owner. */
class MaterialFiles(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = false }
    val changes = MutableStateFlow(0L)
    init {
        context.filesDir.resolve("materials").listFiles().orEmpty().filter { it.isDirectory && validProfile(it.name) }.forEach { recover(it.name) }
    }
    private fun directory(profile: String): File {
        require(validProfile(profile)) { "无效档案" }
        return context.filesDir.resolve("materials/$profile").apply { mkdirs() }
    }
    @Synchronized fun list(profile: String): List<MaterialFile> = directory(profile).listFiles().orEmpty()
        .filter { it.extension == "json" }.sortedByDescending { it.name.endsWith(".meta.json") }
        .mapNotNull { source -> runCatching { json.decodeFromString<MaterialFile>(source.readText()) }.getOrNull()?.takeIf {
            source.name == "${it.id}.meta.json" || (source.name == "${it.id}.json" && it.extension != "json")
        } }
        .filter { it.profileId == profile }.distinctBy { it.id }.sortedByDescending { it.createdAt }
    @Synchronized fun record(item: MaterialFile) {
        require(runCatching { UUID.fromString(item.id) }.isSuccess) { "无效文件 ID" }
        val dir = directory(item.profileId)
        me.rerere.rikkahub.data.sync.PendingRestore.writeDurably(dir.resolve("${item.id}.meta.json"), json.encodeToString(item))
        if (item.extension != "json") dir.resolve("${item.id}.json").delete()
        changes.value++
    }
    fun get(profile: String, id: String): MaterialFile = list(profile).firstOrNull { it.id == id } ?: error("文件不属于当前档案或不存在")
    fun file(item: MaterialFile): File {
        require(runCatching { UUID.fromString(item.id) }.isSuccess && item.extension.matches(Regex("[a-z0-9]{1,8}"))) { "无效文件标识或格式" }
        return directory(item.profileId).resolve("${item.id}.${item.extension}")
    }
    fun verifiedFile(profile: String, id: String): File {
        val item = get(profile, id)
        check(item.state == "SUCCEEDED") { "文件尚未成功完成" }
        return file(item).also { check(it.isFile && it.length() == item.size && digest(it) == item.sha256) { "文件缺失或校验失败，请重试" } }
    }
    /** Success means the destination was closed, reopened and matched the source digest. */
    suspend fun saveVerified(profile: String, id: String, destination: Uri, stillAllowed: () -> Boolean = { true }) = saveVerifiedUsing(
        profile, id, destination, { context.contentResolver.openOutputStream(it) }, { context.contentResolver.openInputStream(it) },
        stillAllowed,
    )

    internal suspend fun saveVerifiedUsing(
        profile: String, id: String, destination: Uri,
        openOutput: (Uri) -> java.io.OutputStream?,
        openInput: (Uri) -> java.io.InputStream?,
        stillAllowed: () -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        fun checkAccess() { check(stillAllowed()) { "学习者或成果已改变，请重新选择" } }
        checkAccess()
        val item = get(profile, id)
        val source = verifiedFile(profile, id)
        checkAccess()
        openOutput(destination)?.use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    checkAccess()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法写入目标文件")
        val actual = openInput(destination)?.use { input ->
            checkAccess()
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                checkAccess()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        check(actual == item.sha256) { "另存文件校验失败" }
        checkAccess()
    }
    fun confirmSymbol(profile: String, id: String, sourceHash: String, correction: String?) {
        val item = get(profile, id)
        verifiedFile(profile, id)
        check(item.sha256 == sourceHash) { "材料已改变，请重新核对" }
        require(correction == null || (correction.isNotBlank() && correction.length <= 4000)) { "符号修正不能为空或超过 4000 字符" }
        record(item.copy(symbolCorrection = correction, symbolSourceSha256 = sourceHash, symbolCheckPending = correction == null))
    }
    suspend fun import(profile: String, uri: Uri, name: String): MaterialFile = withContext(Dispatchers.IO) {
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in setOf("docx", "xlsx", "pptx", "pdf", "txt", "md", "csv", "json", "png", "jpg", "jpeg", "webp", "doc", "xls", "ppt")) { "不支持此格式，请转换为 Office Open XML、PDF、UTF-8 文本或常用图片" }
        val item = MaterialFile(UUID.randomUUID().toString(), profile, name.take(160), ext)
        runTask(item) { target ->
            record(item.copy(state = "RUNNING", detail = "正在复制上传文件"))
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192); var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    count += n; require(count <= 30L * 1024 * 1024) { "文件超过 30 MiB，请拆分后重试" }
                    output.write(buffer, 0, n)
                }
            } } ?: error("无法读取所选文件")
            require(target.length() > 0) { "文件为空" }
            "已上传；尚未分析内容"
        }
    }
    suspend fun runTask(item: MaterialFile, writer: suspend (File) -> String): MaterialFile {
        val diagnosticId = me.rerere.rikkahub.feature.diagnostics.Diagnostics.operationId()
        fun diagnostic(result: me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result) =
            me.rerere.rikkahub.feature.diagnostics.Diagnostics.record(
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.MATERIAL, result, diagnosticId)
        diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.STARTED)
        record(item.copy(state = "RUNNING", detail = "正在处理"))
        val tmp = directory(item.profileId).resolve("${item.id}.part")
        try {
            val detail = writer(tmp)
            currentCoroutineContext().ensureActive()
            check(tmp.isFile && tmp.length() > 0)
            val hash = digest(tmp); val size = tmp.length()
            java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
            java.nio.file.Files.move(tmp.toPath(), file(item).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return item.copy(state = "SUCCEEDED", detail = detail, size = size, sha256 = hash).also(::record).also {
                diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.COMPLETED)
            }
        } catch (e: CancellationException) {
            diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.CANCELLED)
            record(item.copy(state = "CANCELLED", detail = "已取消；没有登记成功文件")); throw e
        } catch (e: Exception) {
            diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.FAILED)
            record(item.copy(state = "FAILED", detail = if (item.output && item.extension == "png") "图片生成失败；请检查输入或生成工具，未登记成功文件" else e.message?.take(160) ?: "处理失败")); throw e
        } finally { tmp.delete() }
    }
    fun recover(profile: String) {
        directory(profile).listFiles().orEmpty().filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }.forEach { it.delete() }
        directory(profile).listFiles().orEmpty().filter { it.extension == "json" && !it.name.endsWith(".meta.json") }.forEach { source ->
            val legacy = runCatching { json.decodeFromString<MaterialFile>(source.readText()) }.getOrNull()
            if (legacy != null && legacy.profileId == profile && legacy.extension == "json" && source.name == "${legacy.id}.json" &&
                !directory(profile).resolve("${legacy.id}.meta.json").exists()) {
                record(legacy.copy(state = "FAILED", detail = "旧版 JSON 文件与元数据冲突；请重新上传原文件"))
            }
        }
        list(profile).forEach {
        if (it.state == "RUNNING") record(it.copy(state = "FAILED", detail = "上次处理已中断，请重新执行原请求"))
        else if (it.processingState == "RUNNING") record(it.copy(processingState = "FAILED", detail = "上次提取中断；原文件保留，可重新读取"))
    } }
    suspend fun readContent(profile: String, id: String): MaterialContent = withContext(Dispatchers.IO) {
        val item = get(profile, id); val source = verifiedFile(profile, id)
        val cache = directory(profile).resolve("$id.read-v4.cache")
        record(item.copy(processingState = "RUNNING", detail = "正在提取文本；不是完整格式识别"))
        try {
            val result = if (cache.exists()) json.decodeFromString<MaterialContent>(cache.readText()) else MaterialReader.read(source, item.extension).also { content ->
                currentCoroutineContext().ensureActive()
                val temp = File(cache.path + ".tmp"); temp.writeText(json.encodeToString(content)); check(temp.renameTo(cache))
            }
            currentCoroutineContext().ensureActive()
            record(item.copy(processingState = "SUCCEEDED", detail = result.coverage))
            result
        } catch (e: CancellationException) { record(item.copy(processingState = "CANCELLED", detail = "提取已取消，原文件保留")); throw e }
        catch (e: Exception) { record(item.copy(processingState = "FAILED", detail = e.message?.take(160) ?: "提取失败；可重试")); throw e }
    }
    suspend fun bindParts(profile: String, parts: List<UIMessagePart>): List<UIMessagePart> = parts.map { part ->
        val url = when (part) { is UIMessagePart.Document -> part.url; is UIMessagePart.Image -> part.url; else -> null }
        if (url == null || !url.startsWith("file:")) part else {
            val source = url.toUri().toFile().canonicalFile
            val own = directory(profile).canonicalFile
            val target = if (source.parentFile == own) source else {
                // Legacy attachments are accepted only from the application's picker upload directory.
                require(source.parentFile == context.filesDir.resolve("upload").canonicalFile) { "附件不属于本档案" }
                val name = if (part is UIMessagePart.Document) part.fileName else source.name
                file(import(profile, source.toUri(), name))
            }
            when (part) { is UIMessagePart.Document -> part.copy(url = target.toUri().toString()); is UIMessagePart.Image -> part.copy(url = target.toUri().toString()); else -> part }
        }
    }
    companion object {
        fun validProfile(profile: String) = profile == me.rerere.rikkahub.data.datastore.DEFAULT_PROFILE_ID || runCatching { UUID.fromString(profile) }.isSuccess
        fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    } }
}
