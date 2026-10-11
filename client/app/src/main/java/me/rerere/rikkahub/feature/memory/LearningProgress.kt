package me.rerere.rikkahub.feature.memory

/** A view of this profile's evidence, never a mastery score or a review calendar. */
data class LearningProgress(
    val difficulties: List<LearningMemory>,
    val evidence: List<LearningMemory>,
    val reviews: List<LearningMemory>,
)

fun MemoryState.learningProgress(): LearningProgress {
    val valid = sources.filter { it.valid }.map { it.id }.toSet()
    val visible = items.filter {
        it.status in setOf("ACTIVE", "CANDIDATE") && it.sourceIds.isNotEmpty() && it.sourceIds.all { id -> id in valid }
    }.sortedByDescending { it.updatedAt }
    return LearningProgress(
        visible.filter { it.type == MemoryType.DIFFICULTY }.take(5),
        visible.filter { it.type == MemoryType.PERFORMANCE }.take(5),
        visible.filter { it.type == MemoryType.REVIEW_SUGGESTION }.take(5),
    )
}
