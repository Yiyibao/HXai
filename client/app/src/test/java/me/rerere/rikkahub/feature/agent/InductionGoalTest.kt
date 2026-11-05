package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.feature.math.MathVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InductionGoalTest {
    private val goal = "a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2。"
    private val reply = "由数学归纳法，对一切非负整数 n，都有 a_n=n^2。"

    @Test fun textbookSquareRecurrenceIsVerifiedByTheHost() = runBlocking {
        val spec = requireNotNull(InductionGoal.parse(goal))
        assertEquals("0", spec.start)
        assertEquals("0", spec.initial)
        assertEquals("u+2*n+1", spec.next)
        assertEquals("n^2", spec.claimed)
        val certificate = MathVerification.verify(spec.request())
        assertEquals("VERIFIED", certificate.getValue("status").jsonPrimitive.content)
        assertTrue(InductionGoal.quoted(reply, spec))
    }

    @Test fun mixedOrUnscopedGoalsAreNotExtracted() {
        assertNull(InductionGoal.parse("同上初值，但递推增加n(n-1)(n-2)。学生根据前几项宣称仍为n^2，是否有效？"))
        assertNull(InductionGoal.parse("a_0=0，a_(n+1)=a_n+2n+1。证明a_n=n^2。"))
        assertNull(InductionGoal.parse("a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n^2，并讨论圆锥曲线。"))
        val spec = requireNotNull(InductionGoal.parse(goal))
        assertFalse(InductionGoal.quoted("归纳完成，但没有写出闭式。", spec))
    }

    @Test fun wrongClosedFormIsNotVerified() = runBlocking {
        val wrong = InductionGoal.parse("a_0=0，a_(n+1)=a_n+2n+1，n为非负整数。证明a_n=n。")
        val spec = requireNotNull(wrong)
        assertEquals("DISPROVED", MathVerification.verify(spec.request()).getValue("status").jsonPrimitive.content)
    }
}
