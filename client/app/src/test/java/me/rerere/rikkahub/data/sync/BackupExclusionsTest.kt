package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupExclusionsTest {
    @Test fun grantAndCredentialStoresCannotReenterABackup() {
        assertTrue(BackupExclusions.rejects("extension-tool-grants-v1.db"))
        assertTrue(BackupExclusions.rejects("credentials-v1/token"))
        assertFalse(BackupExclusions.rejects("materials/note.md"))
    }
}
