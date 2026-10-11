package me.rerere.rikkahub.feature.material
import org.junit.Assert.*
import org.junit.Test
class MaterialVersionsTest {
    private fun file(id: String, parent: String? = null, profile: String = "a") = MaterialFile(id, profile, id, "txt", sourceId = parent)
    @Test fun familyUsesLinksAndOwnerNotNames() {
        val files = listOf(file("root"), file("child", "root"), file("sibling", "root"), file("other"), file("foreign", "root", "b"))
        assertEquals(setOf("root", "child", "sibling"), MaterialVersions.family(files, "child").map { it.id }.toSet())
    }
    @Test fun cyclicMetadataIsRejected() {
        assertTrue(runCatching { MaterialVersions.family(listOf(file("a", "b"), file("b", "a")), "a") }.isFailure)
    }
}
