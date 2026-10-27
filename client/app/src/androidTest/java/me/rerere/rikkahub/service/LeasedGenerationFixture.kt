package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.feature.agent.AgentExecution
import me.rerere.rikkahub.feature.agent.AgentPlan
import me.rerere.rikkahub.feature.agent.AgentScheduler
import me.rerere.rikkahub.feature.agent.PlanStep
import kotlin.uuid.Uuid

/** Direct-loop fixtures use a real, isolated task domain, never a service-side second executor.
 * Databases (including UNKNOWN) remain in nativeqa until the host runner archives its state. */
internal class LeasedGenerationFixture(
    val settings: Settings,
    assistant: Assistant,
    conversationId: Uuid = Uuid.random(),
    profileId: String = settings.activeProfileId,
) : AutoCloseable {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val acceptedSettings = settings.copy(activeProfileId = profileId,
        assistants = settings.assistants.filterNot { it.id == assistant.id } + assistant)
    private val scheduler = AgentScheduler(context, "qa-legacy-lease-${Uuid.random()}.db")
    val task = scheduler.accept(Conversation.ofId(conversationId, assistant.id).copy(profileId = profileId),
        Uuid.random().toString(), "Synthetic generation boundary", acceptedSettings)

    suspend fun <T> run(tools: List<Tool> = emptyList(), block: suspend () -> T): T =
        scheduler.run(task, { acceptedSettings }, {}, { "COMPLETED_UNVERIFIED" }, { false }) {
            if (tools.isNotEmpty()) {
                val active = requireNotNull(currentCoroutineContext()[AgentExecution])
                // Checkpoint tests start after planning; they do not claim model planning/goal success.
                active.store.publishPlan(active.lease, 1, AgentPlan(steps = tools.mapIndexed { index, tool ->
                    PlanStep("fixture-$index", tool.name, "Synthetic checkpoint boundary")
                }), tools.map { it.name }.toSet())
            }
            block()
        }

    fun snapshot() = scheduler.snapshot(task.owner, task.id)
    suspend fun resume(block: suspend () -> Unit) = scheduler.run(task, { acceptedSettings }, {},
        { "COMPLETED_UNVERIFIED" }, { false }, resumeRequested = true, block = block)

    override fun close() = scheduler.close()
}
