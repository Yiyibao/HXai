package me.rerere.rikkahub.feature.studychat

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNames
import me.rerere.rikkahub.data.datastore.DEFAULT_PROFILE_ID

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LearnerProfile(
    val id: String,
    @JsonNames("nickname") val name: String,
    // Preserve the earlier stored preference; automatic extraction is implemented in S2.
    val memoryEnabled: Boolean = false,
) {
    companion object {
        fun defaults() = listOf(LearnerProfile(DEFAULT_PROFILE_ID, "默认学习者"))
        fun checkedName(value: String): String = value.trim().also {
            require(it.isNotEmpty() && it.length <= 40) { "名称需要 1–40 个字符" }
        }
    }
}
