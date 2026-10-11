package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.feature.memory.MemoryState

internal object MemoryRestore {
    fun merge(saved: MemoryState, live: MemoryState, fingerprint: (String) -> String): MemoryState {
        require(saved.fingerprintVersion == 1 && live.fingerprintVersion == 1)
        val sources = saved.suppressedSources + live.suppressedSources
        val facts = saved.suppressedFacts + live.suppressedFacts
        val topics = saved.suppressedTopics + live.suppressedTopics
        val currentById = live.items.associateBy { it.id }
        val items = saved.items.filterNot { item ->
            (live.recoveryBlocked && item.id !in currentById) ||
            fingerprint("${item.type}:${item.topicKey}:${item.factKey}") in facts || fingerprint(item.topicKey) in topics ||
                item.sourceIds.any { id -> saved.sources.any { source -> source.id == id &&
                    (fingerprint("message:${source.messageId}") in sources || fingerprint("${source.messageId}:${source.hash}") in sources) } }
        }.map { item -> currentById[item.id]?.takeIf { it.origin in setOf("USER_EDITED", "USER_EXPLICIT", "USER_MANUAL") && it.revision >= item.revision } ?: item }
        return saved.copy(epoch = maxOf(saved.epoch, live.epoch) + 1,
            recoveryBlocked = saved.recoveryBlocked || live.recoveryBlocked,
            items = items, suppressedSources = sources, suppressedFacts = facts, suppressedTopics = topics,
            processedSources = saved.processedSources + live.processedSources +
                saved.jobs.filter { it.state in setOf("PENDING", "RUNNING", "RETRY_WAIT") }.flatMap { it.sourceIds } +
                live.jobs.filter { it.state == "SUCCEEDED" }.flatMap { it.sourceIds },
            usedMemories = emptyMap(), jobs = saved.jobs.map {
                if (it.state in setOf("PENDING", "RUNNING", "RETRY_WAIT")) it.copy(state = "CANCELLED", leaseUntil = 0, error = "恢复后旧任务已撤销") else it
            })
    }
}

class RestorePreview internal constructor(
    internal val staging: java.io.File,
    val createdAt: Long,
    val profiles: List<String>,
    val entries: Int,
)
