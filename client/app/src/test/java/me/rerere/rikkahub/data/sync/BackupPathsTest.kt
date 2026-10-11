package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BackupPathsTest {
    @Test fun rebasesAttachmentsAndPreservesRemoteUrlsAndPlainText() {
        val original = Json.parseToJsonElement("""{"parts":[{"url":"file:///old/files/materials/a/photo.png"},{"url":"https://example.com/a"}],"text":"file:///old/files/text"}""")
        val root = File("target-files").absoluteFile
        val rebased = BackupPaths.rebase(original, "/old/files", root).jsonObject
        assertEquals("file://${root.absolutePath}/materials/a/photo.png", rebased["parts"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/a", rebased["parts"]!!.jsonArray[1].jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(original.jsonObject["text"], rebased["text"])
    }
    @Test fun encodedTraversalCannotEscapeTheNewInstallation() {
        assertThrows(IllegalArgumentException::class.java) { BackupPaths.rebase(JsonPrimitive("file:///old/files/%2e%2e/escape"), "/old/files", File("target-files")) }
    }
}
