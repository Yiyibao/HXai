package me.rerere.rikkahub.feature.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityCaveatGoalTest {
    private val goal = "x,y为实数。验证(x+y)^2+(x-y)^2=2x^2+2y^2，并说明能否直接视为已证明某圆锥曲线的几何结论。"

    @Test fun textbookIdentityIsVerifiedByTheHost() = runBlocking {
        val spec = requireNotNull(IdentityCaveatGoal.parse(goal))
        assertEquals("(x+y)^2+(x-y)^2", spec.left)
        assertEquals("2*x^2+2*y^2", spec.right)
        val certificate = me.rerere.rikkahub.feature.math.MathVerification.verify(spec.request())
        assertEquals("VERIFIED", certificate["status"]?.toString()?.trim('"'))
        assertTrue(IdentityCaveatGoal.quoted("展开后左边等于2x^2+2y^2，即(x+y)^2+(x-y)^2。", spec))
        assertTrue(IdentityCaveatGoal.deniesGeometry("不能直接视为已经证明了某个圆锥曲线的几何结论。"))
    }

    @Test fun wrongIdentityIsNotVerified() = runBlocking {
        val spec = requireNotNull(IdentityCaveatGoal.parse(
            "验证(x+y)^2+(x-y)^2=2x^2，并说明能否视为圆锥曲线的几何结论。"))
        val certificate = me.rerere.rikkahub.feature.math.MathVerification.verify(spec.request())
        assertEquals("DISPROVED", certificate["status"]?.toString()?.trim('"'))
    }

    @Test fun missingCaveatOrUnrelatedGoalsAreNotExtracted() {
        assertNull(IdentityCaveatGoal.parse("验证(x+y)^2=x^2+2xy+y^2。"))
        assertNull(IdentityCaveatGoal.parse("同上，验证圆锥曲线。"))
        assertFalse(IdentityCaveatGoal.deniesGeometry("因此已经证明了该圆锥曲线的几何结论。"))
        assertFalse(IdentityCaveatGoal.deniesGeometry("展开后等于2x^2+2y^2。该圆锥曲线的几何结论已经成立，这里不能有例外。"))
        assertFalse(IdentityCaveatGoal.deniesGeometry("不能忽略展开。因此已经证明了该圆锥曲线的几何结论。"))
        val spec = requireNotNull(IdentityCaveatGoal.parse(goal))
        assertFalse(IdentityCaveatGoal.quoted("题目要求验证(x+y)^2+(x-y)^2=2x^2+2y^2。", spec))
    }
}
