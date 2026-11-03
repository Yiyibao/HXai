package me.rerere.rikkahub.data.ai

/** Trusted host journal. Models cannot supply permits or receipts. */
interface ProviderRequestJournal {
    fun beforeRequest(role: String, inputHash: String): String
    fun returned(requestId: String, inputHash: String, resultHash: String, usage: me.rerere.ai.core.TokenUsage?)
}
