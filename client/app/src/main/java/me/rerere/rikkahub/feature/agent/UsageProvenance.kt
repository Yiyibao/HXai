package me.rerere.rikkahub.feature.agent

import me.rerere.ai.core.TokenUsage

/** Missing counters stay unknown. A declared estimate is never stored as provider-reported. */
internal object UsageProvenance {
    fun of(usage: TokenUsage?, declared: String? = null): String {
        val hasCount = usage?.promptTokens != null || usage?.completionTokens != null ||
            usage?.cachedTokens != null || usage?.totalTokens != null
        if (!hasCount) return "unknown"
        return when (declared) {
            null, "reported" -> "reported"
            "estimated" -> "estimated"
            else -> "unknown"
        }
    }
}
