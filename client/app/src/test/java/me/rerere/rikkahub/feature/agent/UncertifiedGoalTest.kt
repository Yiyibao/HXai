package me.rerere.rikkahub.feature.agent

import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UncertifiedGoalTest {
    @Test fun recognizedCertificatesStayOnTheirOwnPath() {
        assertFalse(UncertifiedGoal.blocksTextSuccess("a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2。"))
        assertFalse(UncertifiedGoal.blocksTextSuccess("你好"))
    }
    @Test fun sealedUnsupportedPromptsCannotBeDeliveredAsChat() {
        val conic = "椭圆x^2/4+y^2=1与直线y=kx+1，k为任意实数。完整给出交点，并讨论何时相切、何时有两个不同交点。保留参数退化与几何依据。"
        val uniform = "在[-1,1]上令f_n(x)=sqrt(x^2+1/n)，n>=1。证明其一致收敛，求极限函数，并判断是否能由每个f_n可微推断极限在0可微。解释导数极限与极限求导的区别。"
        assertTrue(UncertifiedGoal.blocksTextSuccess(conic))
        assertTrue(UncertifiedGoal.blocksTextSuccess(uniform))
        assertTrue(UncertifiedGoal.blocksTextSuccess("同上初值，但递推增加n(n-1)(n-2)。学生根据前几项宣称仍为n^2，是否有效？"))
        assertTrue(UncertifiedGoal.blocksOpenObligation(conic))
        assertTrue(UncertifiedGoal.blocksOpenObligation(uniform))
        assertFalse(UncertifiedGoal.blocksOpenObligation("对所有实参数a，完整讨论f_a(x)=x^3-3ax在实数域的单调性。必须分别处理退化边界。"))
        for (goal in listOf(conic, uniform)) {
            assertNull(InductionGoal.parse(goal))
            assertNull(RationalRecurrenceGoal.parse(goal))
            assertNull(CubicParameterGoal.parse(goal))
            assertNull(IdentityCaveatGoal.parse(goal))
        }
    }

    @Test fun estimatedCountsAreNotReportedAndMissingCountsStayUnknown() {
        assertEquals("reported", UsageProvenance.of(TokenUsage(promptTokens = 1)))
        assertEquals("estimated", UsageProvenance.of(TokenUsage(promptTokens = 1), "estimated"))
        assertEquals("unknown", UsageProvenance.of(TokenUsage(promptTokens = 1), "guess"))
        assertEquals("unknown", UsageProvenance.of(null, "reported"))
        assertEquals("unknown", UsageProvenance.of(TokenUsage(), "estimated"))
    }
}
