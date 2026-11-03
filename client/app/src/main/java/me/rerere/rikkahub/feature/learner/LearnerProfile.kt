package me.rerere.rikkahub.feature.learner

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val DEFAULT_LEARNER_ID = "default-learner"

@Serializable
data class LearnerProfile(
    val id: String = Uuid.random().toString(),
    val nickname: String,
    val memoryEnabled: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(nickname.isNotBlank() && nickname.length <= 40)
    }
}

val DEFAULT_LEARNER = LearnerProfile(DEFAULT_LEARNER_ID, "我的学习档案")
