package me.rerere.rikkahub.feature.agent

/** Goals that ask for a certificate this host cannot mint. Text delivery must not erase that gap. */
internal object UncertifiedGoal {
    fun blocksTextSuccess(goal: String): Boolean {
        if (InductionGoal.parse(goal) != null || RationalRecurrenceGoal.parse(goal) != null ||
            CubicParameterGoal.parse(goal) != null || IdentityCaveatGoal.parse(goal) != null) return false
        val text = goal.replace(" ", "")
        if ("同上" in text && listOf("递推", "数列", "初值", "通项").any { it in text }) return true
        if ("一致收敛" in text) return true
        if (("交点" in text || "相交" in text) && listOf("椭圆", "圆锥", "直线", "参数").any { it in text }) return true
        if ("证明" in text && listOf("非负整数", "单调", "归纳", "通项", "一致").any { it in text }) return true
        if ("完整讨论" in text && "单调" in text) return true
        return false
    }

    fun blocksOpenObligation(goal: String): Boolean {
        val text = goal.replace(" ", "")
        if ("一致收敛" in text) return true
        return ("交点" in text || "相交" in text) && listOf("椭圆", "圆锥", "直线", "参数").any { it in text }
    }
}
