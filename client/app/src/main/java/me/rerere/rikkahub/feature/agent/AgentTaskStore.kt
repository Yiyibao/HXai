package me.rerere.rikkahub.feature.agent

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class AgentModelLock(
    val providerId: String,
    val modelId: String,
    val reasoning: String,
    val configurationRevision: String,
    val secretReference: String,
)

@Serializable
enum class AgentTaskState { QUEUED, RUNNING, WAITING_AUTHORIZATION, PAUSED, UNCERTAIN_SIDE_EFFECT, RESPONSE_READY, NEEDS_VERIFICATION }

@Serializable
data class AgentCall(val id: String, val inputHash: String, val state: String, val resultHash: String? = null, val usage: AgentUsage = AgentUsage())

@Serializable
data class AgentUsage(val provenance: String = "unknown", val input: Int? = null, val output: Int? = null, val cached: Int? = null)

@Serializable
data class AgentTask(
    val id: String,
    val profileId: String,
    val conversationId: String,
    val inputHash: String,
    val model: AgentModelLock,
    val state: AgentTaskState = AgentTaskState.QUEUED,
    val fence: Long = 0,
    val calls: List<AgentCall> = emptyList(),
    val manualPaused: Boolean = false,
    val inputHistory: List<String> = emptyList(),
)

@Serializable
data class AgentTaskSnapshot(val schema: Int = 1, val fence: Long = 0, val slot: String? = null, val tasks: List<AgentTask> = emptyList())

/** Implementations commit atomically or throw; future/corrupt data must never be recreated. */
interface AgentTaskPersistence {
    fun transaction(change: (AgentTaskSnapshot) -> AgentTaskSnapshot): AgentTaskSnapshot
}

data class AgentPermit(val taskId: String, val profileId: String, val fence: Long)

/** One store per local user domain, shared across profiles. Model text cannot call this API. */
class AgentTaskStore(private val persistence: AgentTaskPersistence) {
    private fun update(change: (AgentTaskSnapshot) -> AgentTaskSnapshot): AgentTaskSnapshot = persistence.transaction {
        require(it.schema == 1) { "Unsupported task schema; data preserved" }
        change(it)
    }

    fun snapshot(): AgentTaskSnapshot = update { it }
    fun get(id: String): AgentTask? = snapshot().tasks.singleOrNull { it.id == id }

    fun accept(task: AgentTask): AgentTask {
        val result = update { state ->
            val old = state.tasks.singleOrNull { it.id == task.id }
            if (old != null) {
                require(old.profileId == task.profileId && old.conversationId == task.conversationId &&
                    old.inputHash == task.inputHash && old.model == task.model) { "Task identity conflict" }
                state
            } else {
                require(task.state == AgentTaskState.QUEUED && task.fence == 0L && task.calls.isEmpty() && !task.manualPaused) { "Invalid task admission" }
                state.copy(tasks = state.tasks + task)
            }
        }
        return result.tasks.single { it.id == task.id }
    }

    fun claim(id: String, profileId: String): AgentPermit {
        val state = update { state ->
            check(state.slot == null) { "Agent execution is blocked by an active or unknown task" }
            val task = state.tasks.single { it.id == id }
            require(task.profileId == profileId) { "Task access denied" }
            check(!task.manualPaused && task.state in setOf(AgentTaskState.QUEUED, AgentTaskState.WAITING_AUTHORIZATION)) { "Task requires explicit recovery" }
            val next = Math.addExact(state.fence, 1)
            state.copy(fence = next, slot = id, tasks = state.tasks.map {
                if (it.id == id) it.copy(state = AgentTaskState.RUNNING, fence = next) else it
            })
        }
        return AgentPermit(id, profileId, state.fence)
    }

    /** Trusted queue reconciliation only; ordinary accept never changes admitted intent. */
    fun reviseQueuedInput(expected: AgentTask, edited: AgentTask): AgentTask {
        require(edited.id == expected.id && edited.profileId == expected.profileId &&
            edited.conversationId == expected.conversationId && edited.model == expected.model) { "Queue edit identity conflict" }
        val state = update { state ->
            val old = state.tasks.single { it.id == expected.id }
            check(old == expected) { "Stale queue edit" }
            check(state.slot != old.id && old.calls.isEmpty() &&
                old.state in setOf(AgentTaskState.QUEUED, AgentTaskState.PAUSED)) { "Dispatched input is immutable" }
            state.copy(tasks = state.tasks.map { if (it.id == old.id && edited.inputHash != old.inputHash)
                it.copy(inputHash = edited.inputHash, inputHistory = it.inputHistory + it.inputHash) else it })
        }
        return state.tasks.single { it.id == expected.id }
    }

    fun requirePermit(permit: AgentPermit) { update { state -> requirePermit(state, permit); state } }
    private fun requirePermit(state: AgentTaskSnapshot, permit: AgentPermit): AgentTask {
        val task = state.tasks.single { it.id == permit.taskId }
        check(state.slot == task.id && state.fence == permit.fence && task.fence == permit.fence &&
            task.profileId == permit.profileId && task.state == AgentTaskState.RUNNING) { "Stale agent executor" }
        return task
    }

    /** Journal before dispatch, after parsing trusted checkpoint metadata. */
    fun beginCall(permit: AgentPermit, id: String, inputHash: String) {
        update { state ->
            val task = requirePermit(state, permit)
            val old = task.calls.singleOrNull { it.id == id }
            require(old == null) { "Call already journaled; do not replay" }
            state.copy(tasks = state.tasks.map { if (it.id == task.id) it.copy(calls = it.calls + AgentCall(id, inputHash, "IN_FLIGHT")) else it })
        }
    }

    /** A durable return is not a verified goal receipt. */
    fun recordReturn(permit: AgentPermit, id: String, inputHash: String, resultHash: String) {
        update { state ->
            val task = requirePermit(state, permit)
            val call = task.calls.single { it.id == id }
            require(call.inputHash == inputHash && (call.state == "IN_FLIGHT" || call.resultHash == resultHash)) { "Call result conflict" }
            state.copy(tasks = state.tasks.map { if (it.id == task.id) it.copy(calls = it.calls.map {
                if (it.id == id) it.copy(state = "RETURNED", resultHash = resultHash) else it
            }) else it })
        }
    }

    fun recordUsage(permit: AgentPermit, id: String, usage: AgentUsage) { update { state ->
        val task = requirePermit(state, permit)
        val call = task.calls.single { it.id == id }
        require(usage.provenance in setOf("reported", "estimated", "unknown"))
        require(listOfNotNull(usage.input, usage.output, usage.cached).all { it >= 0 })
        require(call.usage == AgentUsage() || call.usage == usage) { "Usage receipt conflict" }
        state.copy(tasks = state.tasks.map { if (it.id == task.id) it.copy(calls = it.calls.map {
            if (it.id == id) it.copy(usage = usage) else it
        }) else it })
    } }

    fun finish(permit: AgentPermit, outcome: AgentTaskState) {
        require(outcome in setOf(AgentTaskState.RESPONSE_READY, AgentTaskState.NEEDS_VERIFICATION, AgentTaskState.WAITING_AUTHORIZATION, AgentTaskState.PAUSED))
        update { state ->
            val task = requirePermit(state, permit)
            val uncertain = task.calls.any { it.state == "IN_FLIGHT" }
            state.copy(slot = if (uncertain) task.id else null, tasks = state.tasks.map {
                if (it.id == task.id) it.copy(state = if (uncertain) AgentTaskState.UNCERTAIN_SIDE_EFFECT else outcome,
                    manualPaused = outcome == AgentTaskState.PAUSED) else it
            })
        }
    }

    /** Called after acquiring the OS execution lock; external effects remain unknown. */
    fun recoverStoppedProcess() { update { state ->
        state.copy(tasks = state.tasks.map { if (it.id == state.slot && it.state == AgentTaskState.RUNNING)
            it.copy(state = AgentTaskState.UNCERTAIN_SIDE_EFFECT) else it })
    } }

    fun resumePaused(id: String, profileId: String) { update { state ->
        val task = state.tasks.single { it.id == id }
        require(task.profileId == profileId)
        check(state.slot != id && task.calls.none { it.state == "IN_FLIGHT" } && task.state == AgentTaskState.PAUSED) { "Unknown effects need independent reconciliation" }
        state.copy(tasks = state.tasks.map { if (it.id == id) it.copy(state = AgentTaskState.QUEUED, manualPaused = false) else it })
    } }
}

fun agentHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
