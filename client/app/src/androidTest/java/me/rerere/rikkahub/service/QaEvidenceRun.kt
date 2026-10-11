package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Immutable method/run namespace. Only synthetic QA files are registered here. */
internal class QaEvidenceRun(private val method: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val runId: String = InstrumentationRegistry.getArguments().getString("reviewRunId") ?: UUID.randomUUID().toString()
    private val started = System.currentTimeMillis()
    val directory: File
    private val reusedInputs = mutableSetOf<String>()
    init {
        require(Regex("[A-Za-z0-9_.-]+\\.[A-Za-z0-9_]+", RegexOption.IGNORE_CASE).matches(method))
        require(UUID.fromString(runId).toString() == runId)
        directory = File(instrumentation.targetContext.cacheDir, "qa-evidence/$method/$runId")
        require(!directory.exists() && directory.mkdirs()) { "QA run already exists" }
    }
    fun file(name: String): File {
        require(name.isNotBlank() && !name.contains('\\') && !name.contains(':') && !name.startsWith('/'))
        val target = directory.resolve(name).canonicalFile
        require(target.path.startsWith(directory.canonicalPath + File.separator))
        target.parentFile!!.mkdirs()
        return target
    }
    fun syntheticInput(source: File, name: String): File = file(name).also {
        source.copyTo(it, overwrite = false)
        reusedInputs.add(name)
    }
    fun finish(status: String) {
        val finished = System.currentTimeMillis()
        val entries = JSONArray()
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            val name = file.relativeTo(directory).invariantSeparatorsPath
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            entries.put(JSONObject().put("name", "qa-evidence/$method/$runId/$name").put("sha256", digest)
                .put("bytes", file.length()).put("reusedSyntheticInput", name in reusedInputs))
        }
        val manifest = JSONObject().put("schema", 1).put("method", method).put("runId", runId)
            .put("startedAt", started).put("finishedAt", finished).put("status", status).put("entries", entries)
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("qa_evidence_manifest", manifest.toString()) })
    }
}
