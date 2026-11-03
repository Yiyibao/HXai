package me.rerere.rikkahub.feature.agent

import android.util.AtomicFile
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Separate schema from Room v32; no destructive migration and no secret payload. */
class AndroidAgentTaskPersistence(private val directory: File) : AgentTaskPersistence {
    private val monitor = monitors.computeIfAbsent(directory.canonicalPath) { Any() }
    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create task store" } }

    override fun transaction(change: (AgentTaskSnapshot) -> AgentTaskSnapshot): AgentTaskSnapshot = synchronized(monitor) {
        RandomAccessFile(File(directory, "transaction.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val file = AtomicFile(File(directory, "tasks.json"))
                require(file.baseFile.length() <= 8 * 1024 * 1024) { "Task store exceeds safe read size; data preserved" }
                val state = if (file.baseFile.exists() || File(directory, "tasks.json.bak").exists())
                    JsonInstant.decodeFromString<AgentTaskSnapshot>(file.readFully().toString(Charsets.UTF_8)) else AgentTaskSnapshot()
                require(state.schema == 1) { "Unsupported task schema; data preserved" }
                val changed = change(state)
                if (changed != state) {
                    val bytes = JsonInstant.encodeToString(changed).toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 8 * 1024 * 1024) { "Task store full; data preserved" }
                    val stream = file.startWrite()
                    try { stream.write(bytes); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
                    check(file.readFully().contentEquals(bytes)) { "Task commit readback failed" }
                }
                changed
            }
        }
    }

    companion object { private val monitors = ConcurrentHashMap<String, Any>() }
}

/** OS lock remains held across all provider/tools and persistence finalizers. */
fun acquireAgentExecution(directory: File): AutoCloseable {
    check(directory.isDirectory || directory.mkdirs())
    val handle = RandomAccessFile(File(directory, "executor.lock"), "rw")
    try {
        val lock = handle.channel.tryLock() ?: error("Agent executor still active")
        return AutoCloseable { try { lock.release() } finally { handle.close() } }
    } catch (error: Throwable) { handle.close(); throw error }
}
