package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Wraps existing jobs. There is no second generator and no lease-timeout takeover. */
class AgentScheduler(private val store: AgentTaskStore, private val acquireExecution: () -> AutoCloseable = { AutoCloseable {} }) {
    private val execution = Mutex()

    suspend fun <T> execute(task: AgentTask, outcome: () -> AgentTaskState, block: suspend (AgentPermit) -> T): T {
        store.accept(task)
        return execution.withLock {
            acquireExecution().use {
            store.recoverStoppedProcess()
            val permit = store.claim(task.id, task.profileId)
            try { block(permit) } finally {
                withContext(NonCancellable) { store.finish(permit, outcome()) }
            }
            }
        }
    }
}
