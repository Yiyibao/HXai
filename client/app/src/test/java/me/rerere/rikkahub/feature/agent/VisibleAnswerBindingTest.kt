package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.feature.math.MathVerification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G48-01 baseline. These assertions describe the required binding, not the old substring behavior. */
class VisibleAnswerBindingTest {
    @Test fun negatedOrContradictoryClosedFormIsNotTheClaim() {
        val spec = requireNotNull(InductionGoal.parse("a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2。"))
        assertFalse(InductionGoal.quoted("通项不是n^2，反例当n=2时不相等。", spec))
        assertFalse(InductionGoal.quoted("错误示例是a_n=n^2，正确闭式尚未写出。", spec))
        assertFalse(InductionGoal.quoted("a_n=n^2。因此a_n=n。", spec))
        assertTrue(InductionGoal.quoted("由数学归纳法，对一切非负整数n，都有a_n=n^{2}。", spec))
        assertTrue(InductionGoal.quoted("对一切非负整数，a_n=n^2。n<0不属于本题。", spec))
        assertFalse(InductionGoal.quoted("题目要求证明a_n=n^2。", spec))
        assertFalse(InductionGoal.quoted("a_n=n^2只是题干复述，证明尚未完成。", spec))
        assertFalse(InductionGoal.quoted("不要写成a_n=n^2。", spec))
        assertFalse(InductionGoal.quoted("当n=2时a_n=n^2。", spec))
        assertFalse(InductionGoal.quoted("a_n=n^2。这不是我的结论。", spec))
    }

    @Test fun negatedRationalClaimIsNotExtracted() {
        assertNull(RationalRecurrenceGoal.claim("不是a_n=n*(n+2)/3，该式只是错误示例。"))
        assertNull(RationalRecurrenceGoal.claim("a_n=n*(n+2)/3。因此a_n=n。"))
    }

    @Test fun identityMentionWithoutAffirmationIsNotQuoted() {
        val spec = requireNotNull(IdentityCaveatGoal.parse(
            "x,y为实数。验证(x+y)^2+(x-y)^2=2x^2+2y^2，并说明能否直接视为已证明某圆锥曲线的几何结论。"))
        assertFalse(IdentityCaveatGoal.quoted("并不是(x+y)^2+(x-y)^2=2x^2+2y^2，这只是错误示例。", spec))
        assertTrue(IdentityCaveatGoal.quoted("展开后左边等于2x^2+2y^2，即(x+y)^2+(x-y)^2。", spec))
    }

    @Test fun swappedCubicIntervalsAreNotTheCertificate() = runBlocking {
        val spec = requireNotNull(CubicParameterGoal.parse(
            "对所有实参数a，完整讨论f_a(x)=x^3-3ax在实数域的单调性。必须分别处理退化边界。"))
        val certificate = MathVerification.verify(spec.request())
        assertFalse(CubicParameterGoal.quoted(
            "a<0时在R上严格递增。a=0时孤立驻点仍严格递增。a>0时外侧严格递减，中间严格递增。",
            certificate))
        assertFalse(CubicParameterGoal.quoted(
            "a<0时在R上严格递增。a=0时x=0孤立驻点仍严格递增。a>0时临界点x^2=4a，|x|>2*sqrt(a)严格递增，|x|<2*sqrt(a)严格递减。",
            certificate))
    }

    @Test fun affirmingTheFalseClaimDoesNotBindTheCounterexample() = runBlocking {
        val goal = "请核验 (x+1)^2=x^2+1 是否为恒等式。请以真实核验结果为准并给出反例。"
        val input = buildJsonObject {
            put("operation", "identity")
            put("request", """{"left":"(x+1)^2","right":"x^2+1"}""")
        }
        val certificate = MathVerification.verify(input)
        assertFalse(RefutationDelivery.binds(
            goal, input, certificate, "命题成立。反例：取 x=1，左边为 4，右边为 2。"))
    }
}
