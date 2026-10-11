package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.feature.diagnostics.Diagnostics
import me.rerere.rikkahub.feature.diagnostics.DiagnosticsNetworkInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ExecutorService

@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {
    @Test fun largeFailureFramesRetainNewestEvidenceWithinByteCap() {
        val error = IllegalStateException("QA_PRIVATE_MESSAGE").apply {
            stackTrace = Array(12) { StackTraceElement("me.rerere." + "LongFrame".repeat(20), "operation", "private-file", 1) }
        }
        repeat(230) { Diagnostics.recordFailure(Diagnostics.Stage.CHAT, error) }
        Diagnostics.recordFailure(Diagnostics.Stage.CHAT, java.io.IOException("QA_PRIVATE_NEWEST"))
        val zip = Diagnostics.export(context, "")
        val report = ZipFile(zip).use { it.getInputStream(it.getEntry("report.json")).readBytes().toString(Charsets.UTF_8) }
        val events = JSONObject(report).getJSONArray("events")
        assertTrue(events.length() in 1..200)
        assertEquals("java.io.IOException", events.getJSONObject(events.length() - 1).getJSONObject("failure").getString("exceptionType"))
        assertTrue(File(directory, "events.json").length() <= 128 * 1024)
        assertFalse(report.contains("QA_PRIVATE"))
        assertFalse(report.contains("private-file"))
    }
    @Test fun failureFingerprintDoesNotIncludeMessageOrPaths() {
        val error = IllegalStateException("QA_SECRET https://secret.example /private/file.docx",
            java.io.IOException("QA_SECRET_BODY"))
        val fingerprint = Diagnostics.failureFingerprint(error).toString()
        assertTrue(fingerprint.contains("java.io.IOException"))
        assertFalse(fingerprint.contains("QA_SECRET"))
        assertFalse(fingerprint.contains("secret.example"))
        assertFalse(fingerprint.contains("/private/"))
    }
    private val originalContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: Context
    private val directory get() = File(context.noBackupFilesDir, "diagnostics")
    private lateinit var root: File
    private lateinit var fixture: String
    private val preferenceNames = mutableSetOf<String>()
    private var originalDirectory: File? = null
    private val eventField get() = Diagnostics::class.java.getDeclaredField("eventDirectory").apply { isAccessible = true }
    private fun flush() {
        (Diagnostics::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(Diagnostics) as ExecutorService).submit {}.get()
    }
    private fun originalFingerprints(): Map<String, String> {
        val roots = listOf("records" to File(originalContext.noBackupFilesDir, "diagnostics"),
            "exports" to File(originalContext.cacheDir, "diagnostic-exports"))
        return roots.flatMap { (kind, folder) -> folder.listFiles().orEmpty().filter { it.isFile }.map { kind to it } }.associate { (kind, file) ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(8192)
                while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            }
            "$kind/${file.name}" to digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
    private var before = emptyMap<String, String>()
    @Before fun isolate() {
        flush()
        before = originalFingerprints()
        fixture = UUID.randomUUID().toString()
        root = File(originalContext.cacheDir, "qa-diagnostics-tests/$fixture")
        require(!root.exists() && root.mkdirs())
        context = object : ContextWrapper(originalContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "qa-diagnostics-$fixture-$name"
                preferenceNames.add(isolated)
                return originalContext.getSharedPreferences(isolated, mode)
            }
        }
        directory.mkdirs()
        originalDirectory = eventField.get(Diagnostics) as File?
        eventField.set(Diagnostics, directory)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("qa_diagnostics_fixture", fixture)
        })
    }
    @After fun restore() {
        if (!::context.isInitialized) return
        flush()
        eventField.set(Diagnostics, originalDirectory)
        assertTrue("Pre-existing diagnosis files remain unchanged", before == originalFingerprints())
        require(root.canonicalFile.parentFile == File(originalContext.cacheDir, "qa-diagnostics-tests").canonicalFile)
        require(root.name == fixture)
        // Register only this newly created UUID fixture, then remove exact files.
        val owned = root.walkTopDown().toList()
        owned.forEach { require(it.canonicalPath == root.canonicalPath || it.canonicalPath.startsWith(root.canonicalPath + File.separator)) }
        owned.sortedByDescending { it.path.length }.forEach { assertTrue("QA fixture cleanup", it.delete()) }
        preferenceNames.forEach { originalContext.deleteSharedPreferences(it) }
    }
    private fun report(): JSONObject {
        val file = Diagnostics.export(context, "")
        return try { ZipFile(file).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText()) } }
        finally { file.delete() }
    }

    @Test fun boundedDurableEventsAndSanitizedCrashSurviveSafeModeClearing() {
        repeat(230) { Diagnostics.record(Diagnostics.Stage.NETWORK, Diagnostics.Result.HTTP_ERROR, "secret-invalid-id", 503, it.toLong()) }
        val error = IllegalArgumentException("sk-private-secret https://private.example/path?token=secret").apply {
            stackTrace = arrayOf(StackTraceElement("me.rerere.rikkahub.Fixture", "run", "secret-file-name", 12))
        }
        Diagnostics.captureCrash(context, error)
        me.rerere.rikkahub.utils.CrashHandler.clearCrashed(context)
        val result = report()
        assertEquals(200, result.getJSONArray("events").length())
        assertEquals("java.lang.IllegalArgumentException", result.getJSONObject("lastCrash").getString("exceptionType"))
        val text = result.toString()
        listOf("sk-private-secret", "private.example", "secret-file-name", "secret-invalid-id").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("Fixture.run:12"))
        assertTrue(File(directory, "crash.json").isFile)
        assertTrue(File(directory, "events.json").length() < 128 * 1024)
    }

    @Test fun networkFailuresHttpErrorsAndTruncatedStreamsDoNotExposeRequestData() {
        val request = Request.Builder().url("https://private.example/secret?token=sk-test")
            .header("Authorization", "Bearer sk-test").build()
        fun client(mode: Int) = OkHttpClient.Builder().addInterceptor(DiagnosticsNetworkInterceptor()).addInterceptor { chain ->
            if (mode == 0) throw IOException("private.example token=sk-test")
            val body = if (mode == 1) "private response".toResponseBody() else object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                private val input = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("private stream content sk-test")
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
                override fun source(): BufferedSource = input
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (mode == 1) 503 else 200)
                .message("fixture").body(body).build()
        }.build()
        assertTrue(runCatching { client(0).newCall(request).execute() }.exceptionOrNull() is IOException)
        client(1).newCall(request).execute().use { assertEquals("private response", it.body.string()) }
        assertTrue(runCatching { client(2).newCall(request).execute().use { it.body.string() } }.exceptionOrNull() is IOException)
        val events = report().getJSONArray("events").toString()
        assertTrue(events.contains("HTTP_ERROR"))
        assertTrue(events.contains("RESPONSE_BODY"))
        assertTrue(events.contains("FAILED"))
        listOf("private.example", "sk-test", "Authorization", "private response", "private stream").forEach { assertFalse(events.contains(it)) }
    }

    @Test fun feedbackIsExplicitBoundedAndCancelledTasksAreDistinguished() = runBlocking {
        assertTrue(runCatching { Diagnostics.track(Diagnostics.Stage.DOCUMENT) { throw kotlinx.coroutines.CancellationException("secret") } }.isFailure)
        val file = Diagnostics.export(context, "反馈".repeat(1500))
        try { ZipFile(file).use { zip ->
            assertEquals(setOf("report.json", "feedback.txt", "README.txt"), zip.entries().asSequence().map { it.name }.toSet())
            assertEquals(2000, zip.getInputStream(zip.getEntry("feedback.txt")).bufferedReader().readText().length)
            val report = zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText()
            assertTrue(report.contains("CANCELLED"))
            assertFalse(report.contains("反馈"))
        } } finally { file.delete() }
    }

    @Test fun corruptMetadataRecoversWithoutReadingLearningFiles() {
        File(directory, "events.json").writeText("broken")
        Diagnostics.record(Diagnostics.Stage.BACKUP, Diagnostics.Result.FAILED)
        assertEquals(1, report().getJSONArray("events").length())
        Diagnostics.clear(context)
        assertEquals(0, report().getJSONArray("events").length())
        assertEquals(0, report().getJSONObject("lastCrash").length())
    }
}
