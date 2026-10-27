package me.rerere.rikkahub.feature.memory

/** A manual request's stable ID is independent of its classification.
 * Read legacy per-type tombstones as well as the independent identity marker.
 * A new explicit fact may clear a fact tombstone, never the old request identity.
 */
internal object ManualMemoryIdentity {
    fun tombstone(id: String, fingerprint: (String) -> String): String = fingerprint("manual-request:$id")
    fun isDeleted(id: String, suppressedFacts: Set<String>, fingerprint: (String) -> String): Boolean =
        suppressedFacts.isNotEmpty() && (tombstone(id, fingerprint) in suppressedFacts || MemoryType.entries.any { type ->
            fingerprint("$type:手动添加:$id") in suppressedFacts
        })
}
