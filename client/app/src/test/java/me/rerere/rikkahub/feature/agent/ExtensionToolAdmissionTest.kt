package me.rerere.rikkahub.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionToolAdmissionTest {
    @Test fun enableAndSchemaDoNotExposeTools() {
        assertEquals(emptyList<String>(), ExtensionToolAdmission.visible("ENABLED", listOf("read_a", "read_b"), emptySet()))
        assertEquals(emptyList<String>(), ExtensionToolAdmission.visible("DISABLED", listOf("read_a"), setOf("read_a")))
    }

    @Test fun oneToolGrantDoesNotExposeAnother() {
        assertEquals(listOf("read_a"), ExtensionToolAdmission.visible("ENABLED", listOf("read_a", "read_b"), setOf("read_a")))
        assertEquals(false, ExtensionToolAdmission.current(1, "", 1, "a".repeat(64)))
        assertEquals(false, ExtensionToolAdmission.current(1, "a".repeat(64), 2, "a".repeat(64)))
        assertEquals(true, ExtensionToolAdmission.current(2, "a".repeat(64), 2, "a".repeat(64)))
    }
}
