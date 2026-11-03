package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Text delivery evidence; never a mathematical certificate or an external-effect receipt. */
internal object ResponseCompletion {
    const val STEP = "_response"
    const val KIND = "response_delivery"
    const val MAX_CHARS = 100_000

    fun eligible(message: UIMessage): Boolean = message.role==MessageRole.ASSISTANT &&
        message.finishedAt!=null && message.toText().isNotBlank() && message.toText().length<=MAX_CHARS &&
        message.parts.all {it is UIMessagePart.Text || it is UIMessagePart.Reasoning}

    fun disposition(result: JsonObject, revision: Long, digest: String, text: String): String? {
        val keys=setOf("outcome","goalRevision","evidenceHash","responseQuote","reason","requiresIndependentEvidence")
        val version=(result["goalRevision"] as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull
        if(result.keys!=keys || version!=revision ||
            result["evidenceHash"]!=JsonPrimitive(digest))return null
        val outcome=(result["outcome"] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: return null
        val quote=(result["responseQuote"] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: return null
        val reason=(result["reason"] as? JsonPrimitive)?.takeIf {it.isString}?.content ?: return null
        val external=(result["requiresIndependentEvidence"] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull ?: return null
        if(reason.isBlank() || reason.length>4000 || quote.isBlank() || quote.length>4000 || quote !in text)return null
        if(outcome !in setOf("delivered","needs_input","unverified") || outcome=="delivered" && external)return null
        return outcome
    }
}
