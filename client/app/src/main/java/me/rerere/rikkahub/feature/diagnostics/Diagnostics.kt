package me.rerere.rikkahub.feature.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import me.rerere.rikkahub.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Diagnostics {
    enum class Stage { STARTUP, FOREGROUND, BACKGROUND, NETWORK, RESPONSE_BODY, CHAT, MATERIAL, DOCUMENT, BACKUP, RESTORE, CRASH }
    enum class Result { STARTED, COMPLETED, FAILED, CANCELLED, HTTP_ERROR, CLOSED }
    private const val MAX_EVENTS = 200
    private const val MAX_BYTES = 128 * 1024
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var eventDirectory: File? = null
    /** Debug instrumentation can isolate application lifecycle events before onCreate. */
    @Volatile internal var instrumentationDirectory: File? = null
    private val session = UUID.randomUUID().toString()

    fun install(application: Application) {
        eventDirectory = if (BuildConfig.DEBUG) instrumentationDirectory ?: directory(application) else directory(application)
        record(Stage.STARTUP, Result.STARTED)
        var visible = 0
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (visible++ == 0) record(Stage.FOREGROUND, Result.STARTED) }
            override fun onActivityStopped(activity: Activity) { if (--visible == 0) record(Stage.BACKGROUND, Result.STARTED) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun operationId(): String = UUID.randomUUID().toString()

    /** No message, URL, body, file path or exception.toString enters the diagnostic record. */
    fun failureFingerprint(error: Throwable): JSONObject {
        val root = generateSequence(error) { it.cause }.take(8).last()
        val type = root.javaClass.name.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{1,200}")) } ?: "unknown"
        val frames = root.stackTrace.filter {
            it.className.startsWith("me.rerere.") || it.className.startsWith("org.mathstudy.")
        }.take(12).map { "${it.className}.${it.methodName}:${it.lineNumber}" }
        return JSONObject().put("exceptionType", type).put("frames", JSONArray(frames))
    }

    fun recordFailure(stage: Stage, error: Throwable, operation: String? = null) {
        val root = eventDirectory ?: return
        val event = event(stage, if (error is kotlinx.coroutines.CancellationException) Result.CANCELLED else Result.FAILED,
            operation, (error as? me.rerere.ai.util.HttpException)?.statusCode, null)
            .put("failure", failureFingerprint(error))
        executor.execute { runCatching { append(root, event) } }
    }

    suspend fun <T> track(stage: Stage, action: suspend () -> T): T {
        val id = operationId()
        val start = SystemClock.elapsedRealtime()
        record(stage, Result.STARTED, id)
        try {
            return action().also { record(stage, Result.COMPLETED, id, durationMs = SystemClock.elapsedRealtime() - start) }
        } catch (error: Throwable) {
            record(stage, if (error is kotlinx.coroutines.CancellationException) Result.CANCELLED else Result.FAILED,
                id, durationMs = SystemClock.elapsedRealtime() - start)
            throw error
        }
    }

    fun record(stage: Stage, result: Result, operation: String? = null, status: Int? = null, durationMs: Long? = null) {
        val root = eventDirectory ?: return
        val event = event(stage, result, operation, status, durationMs)
        executor.execute { runCatching { append(root, event) } }
    }

    private fun event(stage: Stage, result: Result, operation: String?, status: Int?, durationMs: Long?) =
        JSONObject().put("time", System.currentTimeMillis()).put("session", session)
            .put("stage", stage.name).put("result", result.name).apply {
                operation?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
                    ?.let { put("operation", it) }
                status?.takeIf { it in 100..599 }?.let { put("httpStatus", it) }
                durationMs?.let { put("durationMs", it.coerceIn(0, 86_400_000)) }
            }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "diagnostics").apply { mkdirs() }

    private fun read(context: Context, name: String): JSONObject = read(directory(context), name)

    private fun read(root: File, name: String): JSONObject = runCatching {
        val atomic = AtomicFile(File(root, name))
        atomic.openRead().use { input ->
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                val buffer = ByteArray(4096)
                while (out.size() <= MAX_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
                    if (count == -1) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            require(bytes.size <= MAX_BYTES)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }.getOrElse { JSONObject() }

    private fun write(context: Context, name: String, value: JSONObject) = write(directory(context), name, value)

    private fun write(root: File, name: String, value: JSONObject) {
        val atomic = AtomicFile(File(root, name))
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    private fun append(root: File, event: JSONObject) = synchronized(lock) {
        val old = read(root, "events.json").optJSONArray("events") ?: JSONArray()
        val events = JSONArray()
        for (index in maxOf(0, old.length() - MAX_EVENTS + 1) until old.length()) events.put(old.getJSONObject(index))
        events.put(event)
        // Failure frames are larger than ordinary status events. Keep newest evidence
        // within both limits instead of dropping every subsequent write at the byte cap.
        while (events.length() > 1 && JSONObject().put("events", events).toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            events.remove(0)
        }
        write(root, "events.json", JSONObject().put("events", events))
    }

    /** Synchronous and independent of the worker queue so process termination cannot lose the last crash. */
    fun captureCrash(context: Context, throwable: Throwable) = runCatching {
        synchronized(lock) {
            val frames = JSONArray()
            throwable.stackTrace.filter { it.className.startsWith("me.rerere.") || it.className.startsWith("org.mathstudy.") }
                .take(40).forEach { frames.put("${it.className}.${it.methodName}:${it.lineNumber}") }
            write(context, "crash.json", event(Stage.CRASH, Result.FAILED, null, null, null)
                .put("exceptionType", throwable.javaClass.name).put("frames", frames)
                .put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE))
        }
    }

    fun summary(context: Context): String = synchronized(lock) {
        val crash = read(context, "crash.json")
        buildString {
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) / ${BuildConfig.BUILD_TYPE}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("最近崩溃：${if (crash.has("time")) java.util.Date(crash.getLong("time")).toString() else "没有记录"}")
            append("记录包含启动、前后台、网络及已接入任务状态。无记录不代表没有故障。")
        }
    }

    fun clear(context: Context) {
        executor.submit { synchronized(lock) {
            AtomicFile(File(directory(context), "events.json")).delete()
            AtomicFile(File(directory(context), "crash.json")).delete()
        } }.get()
        // Already shared/saved copies are owned by the recipient and cannot be recalled.
        File(context.cacheDir, "diagnostic-exports").listFiles()?.forEach { it.delete() }
    }

    /** Called on IO; flush the ordered queue before taking an immutable snapshot. */
    fun export(context: Context, feedback: String): File {
        executor.submit {}.get()
        val report = synchronized(lock) {
            JSONObject().put("schema", 1).put("reportId", operationId()).put("createdAt", System.currentTimeMillis())
                .put("app", JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                    .put("package", context.packageName).put("buildType", BuildConfig.BUILD_TYPE))
                .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("androidRelease", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                    .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())))
                .put("runtime", JSONObject().put("session", session).put("uptimeMs", SystemClock.elapsedRealtime())
                    .put("freeStorageBytes", context.filesDir.usableSpace)
                    .put("memoryClassMb", (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass))
                .put("events", read(context, "events.json").optJSONArray("events") ?: JSONArray())
                .put("lastCrash", read(context, "crash.json"))
        }
        val outputDir = File(context.cacheDir, "diagnostic-exports").apply { mkdirs() }
        outputDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        require(outputDir.listFiles().orEmpty().size < 30) { "诊断包过多，请清除记录后重试" }
        val output = File(outputDir, "mathstudy-diagnostic-${report.getString("reportId")}.zip")
        try {
            ZipOutputStream(output.outputStream()).use { zip ->
                fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                entry("report.json", report.toString(2))
                entry("feedback.txt", feedback.take(2000))
                entry("README.txt", "MathStudyAgent 诊断包\n自动记录不包含聊天正文、密钥、URL、请求响应正文、材料或数据库。feedback.txt 为用户手动填写，可能含个人信息。\n操作编号仅供本地关联，不是服务端任务编号。HTTP成功不表示模型输出或文件内容正确。\n前后台记录不能证明系统杀进程原因；不包含系统ANR、原生崩溃或完整系统日志。\n没有自动上传，需由用户保存或分享。")
            }
            return output
        } catch (error: Throwable) { output.delete(); throw error }
    }
}
