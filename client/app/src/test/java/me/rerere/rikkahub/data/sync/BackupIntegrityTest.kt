package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.*

class BackupIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(name: String = "settings.json", expectedSize: Long = 7, hash: String? = null): java.io.File {
        val content = temporary.newFile().apply { writeText("payload") }
        val described = BackupIntegrity.describe(content)
        return temporary.newFile().also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(content.readBytes()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupIntegrity.manifestName))
            zip.write(Json.encodeToString(BackupManifest(database = true, files = true, entries = mapOf(name to described.copy(size = expectedSize, sha256 = hash ?: described.sha256)))).toByteArray())
            zip.closeEntry()
        } }
    }
    @Test fun verifiesManifestAndRejectsHashSizeAndTraversal() {
        assertEquals(7L, BackupIntegrity.verify(archive()).entries.getValue("settings.json").size)
        listOf(archive(hash = "0".repeat(64)), archive(expectedSize = 6), archive("../escape"), archive("upload/../../escape"), archive("skills\\escape")).forEach {
            assertThrows(IllegalArgumentException::class.java) { BackupIntegrity.verify(it) }
        }
    }
    @Test fun missingManifestNeverAcceptsLegacyPlainArchive() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { it.putNextEntry(ZipEntry("settings.json")); it.write("data".toByteArray()); it.closeEntry() }
        assertThrows(IllegalStateException::class.java) { BackupIntegrity.verify(file) }
    }
}
