package me.rerere.rikkahub.service

import me.rerere.rikkahub.feature.studychat.LearnerProfile
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LearnerProfileCompatibilityTest {
    @Test
    fun `earlier nickname and memory preference survive rename and round trip`() {
        val old = """[{"id":"learner-b","nickname":"Learner B","memoryEnabled":false}]"""
        val profiles = JsonInstant.decodeFromString<List<LearnerProfile>>(old)
        val updated = profiles.map { it.copy(name = "Renamed B") }
        val restored = JsonInstant.decodeFromString<List<LearnerProfile>>(JsonInstant.encodeToString(updated))
        assertEquals("learner-b", restored.single().id)
        assertEquals("Renamed B", restored.single().name)
        assertFalse(restored.single().memoryEnabled)
    }
}
