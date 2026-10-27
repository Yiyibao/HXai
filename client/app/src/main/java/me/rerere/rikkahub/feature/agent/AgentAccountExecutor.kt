package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import me.rerere.ai.core.TokenUsage

internal data class AgentHostedReply(val text: String,val usage: TokenUsage?) {
    override fun toString() = "AgentHostedReply([redacted])"
}

/** Host-owned adapter; request data cannot supply code, a stop proof, or a verifier. */
internal fun interface AgentHostedProvider {
    suspend fun generate(task: AgentTask,requestId: String,secret: String,input: String): AgentHostedReply
}

/** A supervised receiver of the existing slot, not another scheduler or model loop.
 * No DI/UI/network entry enables it. Tests use a synthetic provider and account.
 */
internal class AgentAccountExecutor(
    private val store: AgentTaskStore,
    private val transport: AgentAccountTransport,
    private val provider: AgentHostedProvider,
) {
    suspend fun run(owner: String,id: String,handoffId: String,executor: String,
        credential: AgentCredentialHandle,commandId: String,input: String): String = coroutineScope {
        val lease=transport.activate(owner,id,handoffId,executor)
        val job=requireNotNull(currentCoroutineContext()[Job])
        // A duplicate activation ACK never grants a second supervisor for that lease.
        // Failed binding leaves the authoritative slot blocked for trusted recovery.
        store.accountTransaction { db ->
            store.check(lease)
            db.execSQL("INSERT INTO agent_executor_bindings(task_id,fence,executor_id,instance_id) VALUES(?,?,?,?)",
                arrayOf<Any>(id,lease.fence,executor,java.util.UUID.randomUUID().toString()))
            store.supervise(lease,job)
        }
        var outcome="INTERRUPTED"
        try {
            currentCoroutineContext().ensureActive()
            val task=store.get(owner,id)
            val selection=AgentRequestSelection(task.providerId,task.modelId,task.reasoning,
                store.credentialReference(owner,id) ?: task.configurationReference)
            val admitted=transport.admitProvider(lease,credential,commandId,input,selection)
            if(admitted.dispatch) {
                currentCoroutineContext().ensureActive()
                val secret=transport.checkProviderSecret(lease,credential)
                val reply=provider.generate(task,admitted.requestId,secret,input)
                require(reply.text.length<=1_048_576) { "Hosted output exceeds the safety limit" }
                // Trusted physical completion may settle a known result after account
                // revoke; this is not permission for another dispatch or secret read.
                store.accountTransaction { db ->
                    db.execSQL("INSERT INTO agent_hosted_results(request_id,response_text,response_hash) VALUES(?,?,?)",
                        arrayOf(admitted.requestId,reply.text,AgentTaskStore.hash(reply.text)))
                    store.finishRequest(lease,admitted.requestId,reply.usage)
                }
            }
            val result=transport.result(lease,admitted.requestId)
                ?: throw AgentTaskBlocked() // ACK absence is never permission to replay.
            outcome="COMPLETED_UNVERIFIED" // A provider response does not satisfy the user's goal.
            result
        } finally {
            withContext(NonCancellable) { store.finish(lease,outcome) }
        }
    }
}
