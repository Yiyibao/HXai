package me.rerere.rikkahub.feature.agent

import me.rerere.ai.ui.UIMessagePart
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the already journaled call, never authority from a model or remote service. */
internal class AgentToolDispatch(private val execution: AgentExecution,private val tool: UIMessagePart.Tool,
    private val validateSource: (() -> Unit)? = null) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentToolDispatch>
    fun check() {
        execution.check()
        validateSource?.invoke()
        execution.store.checkToolDispatch(execution.lease,tool)
    }
}
