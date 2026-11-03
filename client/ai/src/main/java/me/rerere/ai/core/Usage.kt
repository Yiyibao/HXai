package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    val totalTokens: Int? = null,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val countsChanged = (other.promptTokens != null && other.promptTokens != this?.promptTokens) ||
        (other.completionTokens != null && other.completionTokens != this?.completionTokens)
    return TokenUsage(
        promptTokens = other.promptTokens ?: this?.promptTokens,
        completionTokens = other.completionTokens ?: this?.completionTokens,
        totalTokens = other.totalTokens ?: if (countsChanged) null else this?.totalTokens,
        cachedTokens = other.cachedTokens ?: this?.cachedTokens,
    )
}

/** Unknown input keeps an aggregate unknown; never turn a missing counter into zero. */
internal fun sumTokenCounts(first: Int?, second: Int?): Int? {
    if (first == null || second == null) return null
    val sum = first.toLong() + second
    return sum.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
}
