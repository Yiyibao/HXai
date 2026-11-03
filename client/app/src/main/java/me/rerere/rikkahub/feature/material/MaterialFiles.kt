package me.rerere.rikkahub.feature.material

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val mathInputConfirmed: Boolean = false,
    val sourceReferences: List<MaterialSourceBinding> = emptyList(),
    val libraryVersion: Int = 1,
    val folder: String = "", val tags: List<String> = emptyList(),
    val deletedAt: Long? = null,
)

/** No model supplied path or identity is accepted. Each task closes over its original owner. */
class MaterialFiles(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = false }
    // Derived text never survives this instance; legacy disk caches are not source evidence.
    private var lastRead: Pair<MaterialFile, MaterialContent>? = null
    val changes = MutableStateFlow(0L)
    internal val mathWorkInputs by lazy { MathWorkInputs(context, this) }
    internal fun <T> withSourceMutationLock(block: () -> T): T = synchronized(mutationLock, block)
    init {
        context.filesDir.resolve("materials").listFiles().orEmpty().filter { it.isDirectory && validProfile(it.name) }.forEach { recover(it.name) }
    }
    private fun directory(profile: String): File {
        require(validProfile(profile)) { "无效档案" }
        return context.filesDir.resolve("materials/$profile").apply { mkdirs() }
    }
    fun list(profile: String): List<MaterialFile> = stored(profile).filter { it.deletedAt == null }
    fun trash(profile: String): List<MaterialFile> = stored(profile).filter { it.deletedAt != null }
    private fun stored(profile: String): List<MaterialFile> = synchronized(mutationLock) { directory(profile).listFiles().orEmpty()
        .filter { it.extension == "json" }.sortedByDescending { it.name.endsWith(".meta.json") }
        .mapNotNull { source -> runCatching { json.decodeFromString<MaterialFile>(source.readText()) }.getOrNull()?.takeIf {
            source.name == "${it.id}.meta.json" || (source.name == "${it.id}.json" && it.extension != "json")
        } }
        .filter { it.profileId == profile && it.libraryVersion in 1..2 }.distinctBy { it.id }.sortedByDescending { it.createdAt } }
    fun record(item: MaterialFile): Unit = synchronized(mutationLock) {
        require(runCatching { UUID.fromString(item.id) }.isSuccess) { "无效文件 ID" }
        require(item.libraryVersion in 1..2)
        MaterialLibraryMetadata.validate(item.folder, item.tags)
        val dir = directory(item.profileId)
        val metadata = dir.resolve("${item.id}.meta.json")
        val previous = if (metadata.isFile) json.decodeFromString<MaterialFile>(metadata.readText()) else null
        check(previous == null || previous.id == item.id && previous.profileId == item.profileId && previous.libraryVersion in 1..2) { "不支持的既有材料元数据，未覆盖原件" }
        check(previous?.deletedAt == null || previous == item) { "材料已删除；旧写入不能复活。请从回收站显式恢复为新版本" }
        me.rerere.rikkahub.data.sync.PendingRestore.writeDurably(dir.resolve("${item.id}.meta.json"), json.encodeToString(item))
        if (item.extension != "json") dir.resolve("${item.id}.json").delete()
        changes.value++
    }
    fun get(profile: String, id: String): MaterialFile = synchronized(mutationLock) {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "无效文件 ID" }
        val dir = directory(profile)
        val modern = dir.resolve("$id.meta.json")
        val item = if (modern.isFile) json.decodeFromString<MaterialFile>(modern.readText())
            else dir.resolve("$id.json").takeIf { it.isFile }?.let {
                json.decodeFromString<MaterialFile>(it.readText()).also { legacy -> check(legacy.extension != "json") }
            } ?: error("文件不属于当前档案或不存在")
        check(item.id == id && item.profileId == profile && item.libraryVersion in 1..2 && item.deletedAt == null) {
            "文件不属于当前档案、已删除或元数据版本不支持"
        }
        item
    }
    fun file(item: MaterialFile): File {
        require(runCatching { UUID.fromString(item.id) }.isSuccess && item.extension.matches(Regex("[a-z0-9]{1,8}"))) { "无效文件标识或格式" }
        return directory(item.profileId).resolve("${item.id}.${item.extension}")
    }
    fun verifiedFile(profile: String, id: String): File {
        val item = get(profile, id)
        check(item.state == "SUCCEEDED") { "文件尚未成功完成" }
        return file(item).also { check(it.isFile && it.length() == item.size && digest(it) == item.sha256) { "文件缺失或校验失败，请重试" } }
    }

    fun organize(profile: String, id: String, expectedHash: String, folder: String, tags: List<String>) = synchronized(mutationLock) {
        MaterialLibraryMetadata.validate(folder, tags)
        val item = get(profile, id)
        check(item.sha256 == expectedHash) { "源版本已改变" }
        record(item.copy(libraryVersion = 2, folder = folder, tags = tags.distinct()))
    }

    /** Tombstone commits before cache invalidation. Original bytes remain local, never in a backup. */
    fun delete(profile: String, id: String, expectedHash: String) = synchronized(mutationLock) {
        val item = get(profile, id)
        check(item.sha256 == expectedHash) { "源版本已改变" }
        record(item.copy(libraryVersion = 2, deletedAt = System.currentTimeMillis(), state = "DELETED",
            processingState = "CANCELLED", detail = "已从资料库删除；本机回收站保留原件"))
        lastRead = null
        directory(profile).listFiles().orEmpty().filter {
            it.name.startsWith("$id.") && (it.name.endsWith(".cache") || it.name.endsWith(".part"))
        }.forEach { check(it.delete() || !it.exists()) { "缓存清理失败；删除保护仍有效" } }
        MaterialSourceCaches.invalidate(context, profile, id)
    }

    suspend fun restore(profile: String, id: String, stillAllowed: () -> Unit = {}): MaterialFile = restoreMutex.withLock {
        val deleted = trash(profile)
        val item = deleted.singleOrNull { it.id == id } ?: error("回收站记录不存在")
        stillAllowed()
        val deletedIds = deleted.mapTo(HashSet(deleted.size)) { it.id }
        var restoredId = UUID.nameUUIDFromBytes("library-restore-v1:$profile:$id:${item.deletedAt}".toByteArray(Charsets.UTF_8)).toString()
        var generation = 0
        while (restoredId in deletedIds) {
            check(++generation <= 500) { "恢复谱系超过安全上限；原件保留，请重新导入" }
            restoredId = UUID.nameUUIDFromBytes("library-restore-v1:$profile:$id:${item.deletedAt}:$restoredId".toByteArray(Charsets.UTF_8)).toString()
        }
        list(profile).firstOrNull { it.id == restoredId && it.state == "SUCCEEDED" }?.let { existing ->
            check(existing.sourceId == id && existing.sha256 == item.sha256)
            verifiedFile(profile, existing.id); stillAllowed()
            return existing
        }
        val source = file(item)
        check(source.isFile && source.length() == item.size && digest(source) == item.sha256) { "回收站原件缺失或损坏" }
        val candidate = item.copy(id = restoredId, libraryVersion = 2, deletedAt = null,
            state = "PENDING", processingState = "NOT_STARTED", sourceId = item.id, requestKey = "",
            createdAt = System.currentTimeMillis())
        val interrupted = file(candidate)
        if (interrupted.exists()) {
            check(interrupted.length() == item.size && digest(interrupted) == item.sha256) { "先前恢复输出损坏；原件保留，请重新导入" }
            stillAllowed()
            return candidate.copy(state = "SUCCEEDED", detail = "已独立读回先前恢复输出；未重复创建版本").also(::record)
        }
        return runTask(candidate) { target ->
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive(); stillAllowed()
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            check(digest(target) == item.sha256) { "恢复原件校验失败" }
            stillAllowed()
            "已从回收站恢复为新版本；旧删除记录保持"
        }
    }

    fun purge(profile: String, id: String) = synchronized(mutationLock) {
        val item = trash(profile).singleOrNull { it.id == id } ?: error("回收站记录不存在")
        check(file(item).delete() || !file(item).exists()) { "原件清理失败" }
        changes.value++
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
    fun confirmSymbol(profile: String, id: String, sourceHash: String, correction: String?, fullMathInput: Boolean = false,
        stillAllowed: () -> Unit = {}) = synchronized(mutationLock) {
        val item = get(profile, id)
        verifiedFile(profile, id)
        check(item.sha256 == sourceHash) { "材料已改变，请重新核对" }
        require(correction == null || (correction.isNotBlank() && correction.length <= 4000)) { "符号修正不能为空或超过 4000 字符" }
        stillAllowed()
        record(item.copy(libraryVersion = 2, symbolCorrection = correction, symbolSourceSha256 = sourceHash,
            symbolCheckPending = correction == null, mathInputConfirmed = correction != null && fullMathInput))
    }
    suspend fun import(profile: String, uri: Uri, name: String, folder: String = "", stillAllowed: () -> Unit = {},
        maxBytes: Long = 30L * 1024 * 1024): MaterialFile = withContext(Dispatchers.IO) {
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in setOf("docx", "xlsx", "pptx", "pdf", "txt", "md", "csv", "json", "png", "jpg", "jpeg", "webp", "doc", "xls", "ppt")) { "不支持此格式，请转换为 Office Open XML、PDF、UTF-8 文本或常用图片" }
        MaterialLibraryMetadata.validate(folder, emptyList())
        stillAllowed()
        require(maxBytes in 1..30L * 1024 * 1024)
        val item = MaterialFile(UUID.randomUUID().toString(), profile, name.take(160), ext,
            libraryVersion = 2, folder = folder, symbolCheckPending = ext in setOf("png", "jpg", "jpeg", "webp"))
        runTask(item) { target ->
            record(item.copy(state = "RUNNING", detail = "正在复制上传文件"))
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(8192); var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    stillAllowed()
                    val n = input.read(buffer); if (n < 0) break
                    count += n; require(count <= maxBytes) { "文件超过本次复制字节上限，请拆分后重试" }
                    output.write(buffer, 0, n)
                }
            } } ?: error("无法读取所选文件")
            require(target.length() > 0) { "文件为空" }
            MaterialImportValidation.check(target, ext)
            stillAllowed()
            "已上传；尚未分析内容"
        }
    }
    suspend fun runTask(item: MaterialFile, writer: suspend (File) -> String): MaterialFile {
        val diagnosticId = me.rerere.rikkahub.feature.diagnostics.Diagnostics.operationId()
        fun diagnostic(result: me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result) =
            me.rerere.rikkahub.feature.diagnostics.Diagnostics.record(
                me.rerere.rikkahub.feature.diagnostics.Diagnostics.Stage.MATERIAL, result, diagnosticId)
        diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.STARTED)
        val operationKey = directory(item.profileId).canonicalPath
        synchronized(mutationLock) {
            check(!file(item).exists()) { "不可变版本已存在；请读回原结果，不覆盖或重试写入" }
            record(item.copy(state = "RUNNING", detail = "正在处理"))
            activeIoByDirectory[operationKey] = (activeIoByDirectory[operationKey] ?: 0) + 1
        }
        val tmp = directory(item.profileId).resolve("${item.id}.part")
        try {
            val detail = writer(tmp)
            currentCoroutineContext().ensureActive()
            check(tmp.isFile && tmp.length() > 0)
            val hash = digest(tmp); val size = tmp.length()
            java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
            val completed = synchronized(mutationLock) {
                val current = get(item.profileId, item.id)
                check(current.state == "RUNNING" && !file(item).exists()) { "写入状态已改变；未覆盖原件或发布旧执行结果" }
                java.nio.file.Files.move(tmp.toPath(), file(item).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                current.copy(state = "SUCCEEDED", detail = detail, size = size, sha256 = hash).also(::record)
            }
            diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.COMPLETED)
            return completed
        } catch (e: CancellationException) {
            diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.CANCELLED)
            updateWriteFailure(item, "CANCELLED", "已取消；没有登记成功文件"); throw e
        } catch (e: Exception) {
            diagnostic(me.rerere.rikkahub.feature.diagnostics.Diagnostics.Result.FAILED)
            updateWriteFailure(item, "FAILED", if (item.output && item.extension == "png") "图片生成失败；请检查输入或生成工具，未登记成功文件" else e.message?.take(160) ?: "处理失败"); throw e
        } finally { tmp.delete(); finishOperation(operationKey) }
    }
    private fun updateWriteFailure(item: MaterialFile, state: String, detail: String) = synchronized(mutationLock) {
        val current = runCatching { get(item.profileId, item.id) }.getOrNull()
        if (current?.state == "RUNNING") record(current.copy(state = state, detail = detail))
    }
    private fun finishOperation(key: String) = synchronized(mutationLock) {
        val remaining = activeIoByDirectory.getValue(key) - 1
        if (remaining == 0) activeIoByDirectory.remove(key) else activeIoByDirectory[key] = remaining
    }
    fun recover(profile: String) = synchronized(mutationLock) {
        val dir = directory(profile)
        if (activeIoByDirectory.containsKey(dir.canonicalPath)) return@synchronized
        dir.listFiles().orEmpty().filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }.forEach { it.delete() }
        dir.listFiles().orEmpty().filter { it.extension == "json" && !it.name.endsWith(".meta.json") }.forEach { source ->
            val legacy = runCatching { json.decodeFromString<MaterialFile>(source.readText()) }.getOrNull()
            if (legacy != null && legacy.profileId == profile && legacy.extension == "json" && source.name == "${legacy.id}.json" &&
                !dir.resolve("${legacy.id}.meta.json").exists()) {
                record(legacy.copy(state = "FAILED", detail = "旧版 JSON 文件与元数据冲突；请重新上传原文件"))
            }
        }
        list(profile).forEach {
            if (it.state == "RUNNING") record(it.copy(state = "FAILED", detail = "上次处理已中断，请重新执行原请求"))
            else if (it.processingState == "RUNNING") record(it.copy(processingState = "FAILED", detail = "上次提取中断；原文件保留，可重新读取"))
        }
    }
    /** Extract only a digest-checked frozen input when producing a version-bound source receipt. */
    suspend fun readSourceTextPage(profile: String, id: String, expectedSha256: String, offset: Int, count: Int): kotlinx.serialization.json.JsonObject {
        require(offset >= 0 && count in 1..24000)
        return withSourceText(profile, id, expectedSha256) { item, content -> MaterialSourceReceipt.page(item, content, offset, count) }
    }

    /** UI pagination chooses a complete Unicode boundary; the strict citation API remains unchanged. */
    suspend fun readLibraryTextPage(profile: String, id: String, expectedSha256: String, offset: Int, count: Int): kotlinx.serialization.json.JsonObject {
        require(offset >= 0 && count in 1..24000)
        return withSourceText(profile, id, expectedSha256) { item, content ->
            require(offset <= content.text.length)
            var end = minOf(content.text.length, offset + count)
            if (end > offset && end < content.text.length && content.text[end - 1].isHighSurrogate() && content.text[end].isLowSurrogate()) end--
            if (end == offset && offset < content.text.length) end = minOf(content.text.length, offset + 2)
            MaterialSourceReceipt.page(item, content, offset, maxOf(1, end - offset))
        }
    }

    suspend fun searchSourceText(profile: String, id: String, expectedSha256: String, query: String, offset: Int, limit: Int): kotlinx.serialization.json.JsonObject {
        MaterialSourceSearch.validate(query, offset, limit)
        return withSourceText(profile, id, expectedSha256) { item, content -> MaterialSourceSearch.search(item, content, query, offset, limit) }
    }

    /** Page/slide numbers are extractor-derived; offset is relative to that location's body. */
    suspend fun readSourceLocation(profile: String, id: String, expectedSha256: String,
        kind: String, number: Int, offset: Int, count: Int): kotlinx.serialization.json.JsonObject {
        MaterialSourceLocationRead.validate(kind, number, offset, count)
        return withSourceText(profile, id, expectedSha256) { item, content ->
            MaterialSourceLocationRead.page(item, content, kind, number, offset, count)
        }
    }

    suspend fun searchLibraryText(profile: String, query: String, cursor: String, limit: Int, sourceLimit: Int): kotlinx.serialization.json.JsonObject =
        MaterialLibrarySearch.search(this, profile, query, cursor, limit, sourceLimit)

    /** Persisted heading candidates are rederived from verified bytes, never from the text cache. */
    suspend fun chapterIndex(profile: String, id: String, expectedSha256: String, offset: Int, limit: Int): kotlinx.serialization.json.JsonObject = withContext(Dispatchers.IO) {
        require(offset in 0..512 && limit in 1..20)
        val snapshot = withSourceText(profile, id, expectedSha256, build = MaterialChapterIndex::build)
        require(offset <= snapshot.getValue("chapters").let { (it as kotlinx.serialization.json.JsonArray).size })
        currentCoroutineContext().ensureActive()
        synchronized(mutationLock) {
            check(get(profile, id).sha256 == expectedSha256) { "来源已改变；未发布旧章节索引" }
            val cache = directory(profile).resolve("$id.chapters-v1.cache")
            val storage = MaterialChapterIndex.persist(cache, snapshot)
            MaterialChapterIndex.page(snapshot, offset, limit, storage)
        }
    }

    /** Deletion/restore hook; derived cache only, even when source metadata has already been removed. */
    fun invalidateChapterIndex(profile: String, id: String) {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "无效文件 ID" }
        MaterialChapterIndex.invalidate(directory(profile).resolve("$id.chapters-v1.cache"))
    }

    /** Read only actual source body within a freshly reconstructed, hash-bound heading section. */
    suspend fun readChapter(profile: String, id: String, expectedSha256: String, indexSha256: String,
        chapterId: String, offset: Int, count: Int): kotlinx.serialization.json.JsonObject {
        MaterialChapterRead.validate(indexSha256, chapterId, offset, count)
        return withSourceText(profile, id, expectedSha256) { item, content ->
            val snapshot = MaterialChapterIndex.build(item, content)
            MaterialChapterRead.page(item, content, snapshot, indexSha256, chapterId, offset, count)
        }
    }

    /** Search only actual body in a fresh, source-bound chapter candidate, not its neighbors. */
    suspend fun searchChapter(profile: String, id: String, expectedSha256: String, indexSha256: String,
        chapterId: String, query: String, offset: Int, limit: Int): kotlinx.serialization.json.JsonObject {
        MaterialChapterSearch.validate(indexSha256, chapterId, query, offset, limit)
        return withSourceText(profile, id, expectedSha256) { item, content ->
            val snapshot = MaterialChapterIndex.build(item, content)
            MaterialChapterSearch.search(item, content, snapshot, indexSha256, chapterId, query, offset, limit)
        }
    }

    private suspend fun <T> withSourceText(profile: String, id: String, expectedSha256: String,
        reader: suspend (File, String) -> MaterialContent = MaterialReader::read,
        build: suspend (MaterialFile, MaterialContent) -> T): T = withContext(Dispatchers.IO) {
        val item = get(profile, id)
        check(item.state == "SUCCEEDED" && item.sha256 == expectedSha256 && expectedSha256.length == 64 && expectedSha256.all { it in '0'..'9' || it in 'a'..'f' }) { "源版本摘要不匹配，请重新选择材料" }
        val source = file(item)
        val folder = File(context.cacheDir, "material-evidence/$profile").apply { check(mkdirs() || isDirectory) }
        val snapshot = File.createTempFile("source-", ".input", folder)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.inputStream().use { input ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        check(copied <= item.size && copied <= 30L * 1024 * 1024) { "源文件大小已改变或超过上限" }
                        output.write(buffer, 0, read); hash.update(buffer, 0, read)
                    }
                }
            }
            val actual = MaterialSourceReceipt.hex(hash.digest())
            check(copied == item.size && actual == expectedSha256) { "源文件已改变，不能签发引用依据" }
            val content = reader(snapshot, item.extension)
            val result = build(item, content)
            currentCoroutineContext().ensureActive()
            verifiedFile(profile, id)
            val current = get(profile, id)
            check(current.sha256 == expectedSha256 && current.size == item.size && current.extension == item.extension && current.sourceId == item.sourceId) { "源版本或谱系已改变，请重新选择材料" }
            result
        } finally {
            val removed = snapshot.delete() || !snapshot.exists()
            check(removed || !currentCoroutineContext().isActive) { "引用临时输入无法清理；没有交付来源回执" }
        }
    }

    /** Independently re-read the source; the candidate receipt never supplies owner or authority. */
    suspend fun revalidateSourceTextPage(profile: String, id: String, expectedSha256: String, offset: Int, count: Int,
        expectedReceipt: kotlinx.serialization.json.JsonObject, expectedQuote: String): kotlinx.serialization.json.JsonObject {
        require(expectedQuote.isNotEmpty() && expectedQuote.length <= 24000) { "待核验引用正文必须非空且不超过24000个UTF16单位" }
        val page = readSourceTextPage(profile, id, expectedSha256, offset, count)
        return MaterialSourceReceipt.revalidated(page, expectedReceipt, expectedQuote)
    }

    private fun sameSource(current: MaterialFile, original: MaterialFile): Boolean =
        current.state == "SUCCEEDED" && current.profileId == original.profileId && current.id == original.id &&
            current.sha256 == original.sha256 && current.size == original.size &&
            current.extension == original.extension && current.sourceId == original.sourceId

    /** Update only an extant, unchanged source, preserving concurrent symbol checks and other metadata. */
    private fun updateReadState(original: MaterialFile, state: String, detail: String) = synchronized(mutationLock) {
        val current = runCatching { get(original.profileId, original.id) }.getOrNull()
        if (current != null && sameSource(current, original)) record(current.copy(processingState = state, detail = detail))
    }

    suspend fun readContent(profile: String, id: String): MaterialContent =
        readContentUsing(profile, id, MaterialReader::read)

    internal suspend fun readContentUsing(profile: String, id: String,
        reader: suspend (File, String) -> MaterialContent): MaterialContent = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val operationKey = directory(profile).canonicalPath
        val item = synchronized(mutationLock) {
            get(profile, id).also {
                check(it.state == "SUCCEEDED") { "文件尚未成功完成" }
                record(it.copy(processingState = "RUNNING", detail = "正在提取文本；不是完整格式识别"))
                activeIoByDirectory[operationKey] = (activeIoByDirectory[operationKey] ?: 0) + 1
            }
        }
        try {
            val cached = synchronized(mutationLock) {
                lastRead?.takeIf { sameSource(it.first, item) }?.second
            }
            val result = if (cached == null) withSourceText(profile, id, item.sha256, reader) { _, content -> content }
            else cached.also { verifiedFile(profile, id) }
            currentCoroutineContext().ensureActive()
            synchronized(mutationLock) {
                val current = get(profile, id)
                check(sameSource(current, item)) { "源版本或谱系已改变，请重新选择材料" }
                record(current.copy(processingState = "SUCCEEDED", detail = result.coverage))
                if (cached == null) lastRead = item to result
            }
            result
        } catch (e: CancellationException) {
            updateReadState(item, "CANCELLED", "提取已取消，原文件保留")
            throw e
        } catch (e: Exception) {
            updateReadState(item, "FAILED", e.message?.take(160) ?: "提取失败；可重试")
            throw e
        } finally { finishOperation(operationKey) }
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
        private val mutationLock = Any()
        private val restoreMutex = Mutex()
        private val activeIoByDirectory = HashMap<String, Int>()
        fun validProfile(profile: String) = profile == me.rerere.rikkahub.data.datastore.DEFAULT_PROFILE_ID || runCatching { UUID.fromString(profile) }.isSuccess
        fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    } }
}
