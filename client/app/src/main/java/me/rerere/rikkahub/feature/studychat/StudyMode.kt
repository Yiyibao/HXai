package me.rerere.rikkahub.feature.studychat

import kotlinx.serialization.Serializable

@Serializable
enum class StudyMode(val label: String, val instruction: String) {
    CHAT("通用聊天", "直接满足用户当前请求；需要教学时遵循用户要求。"),
    GUIDED("引导", "先了解已做步骤，每轮只处理一个卡点，通过问题帮助学习者自己推进。"),
    HINT("提示", "给当前卡点的最小可用提示；必要时逐步加深，不主动给出完整解答。"),
    CHECK("检查", "检查学习者给出的步骤，指出第一处实质错误或逻辑缺口，区分确定错误和待核验判断。"),
    EXPLAIN("完整讲解", "主动给出完整推导、适用条件和关键步骤解释，不强迫先回答引导问题。"),
    ;

    val prompt: String get() = "当前教学模式：$label。$instruction 用户当次明确要求优先于模式默认行为。"
}
