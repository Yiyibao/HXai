package me.rerere.rikkahub.feature.material

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdfAssemblyPathTest {
    @Test fun hardLinkedDestinationIsRejectedBeforeAnyPdfWrite() = runBlocking {
        val folder = Files.createTempDirectory("pdf-hardlink").toFile()
        try {
            // Path safety must run before parsing; even unreadable original bytes are never overwritten.
            val source = File(folder, "source.pdf").apply { writeText("source bytes must remain immutable") }
            val output = File(folder, "output.pdf")
            Files.createLink(output.toPath(), source.toPath())
            assertTrue(Files.isSameFile(source.toPath(), output.toPath()))
            val before = source.readBytes()
            val failure = runCatching { MaterialEditor.pdfPages(listOf(source to listOf(1)), output) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("office_source_must_be_preserved", failure?.message)
            assertArrayEquals(before, source.readBytes())
        } finally { folder.deleteRecursively() }
    }
}
