package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillImportLimitsTest {
    private fun archive(entries: List<Pair<String, Int>>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            val chunk = ByteArray(8192)
            entries.forEach { (name, size) ->
                zip.putNextEntry(ZipEntry(name))
                var remaining = size
                while (remaining > 0) { val count = minOf(chunk.size, remaining); zip.write(chunk, 0, count); remaining -= count }
                zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun rejectsCompressedBombBeforeAllocationAndPreservesNormalBinaryBytes() {
        val bomb = archive(listOf("SKILL.md" to 10, "assets/image.bin" to 128 * 1024 * 1024))
        assertTrue(bomb.size < 150000)
        assertTrue(runCatching { SkillImportLimits.zip(bomb) }.exceptionOrNull() is SkillImportLimits.LimitException)
        val normal = SkillImportLimits.zip(archive(listOf("SKILL.md" to 10, "assets/image.bin" to 321)))
        assertArrayEquals(ByteArray(321), normal["assets/image.bin"])
    }

    @Test fun rejectsTotalInflationEvenWhenEachFileFits() {
        val zip = archive(listOf("one" to SkillImportLimits.FILE_BYTES, "two" to SkillImportLimits.FILE_BYTES, "three" to 1))
        assertTrue(runCatching { SkillImportLimits.zip(zip) }.exceptionOrNull() is SkillImportLimits.LimitException)
    }

    @Test fun rejectsMetadataEntryCountAndDepthLimits() {
        assertTrue(runCatching { SkillImportLimits.zip(archive(listOf("SKILL.md" to SkillImportLimits.MARKDOWN_BYTES + 1))) }.exceptionOrNull() is SkillImportLimits.LimitException)
        assertTrue(runCatching { SkillImportLimits.zip(archive((0..SkillImportLimits.ENTRIES).map { "file$it" to 0 })) }.exceptionOrNull() is SkillImportLimits.LimitException)
        assertTrue(runCatching { SkillImportLimits.path((0..SkillImportLimits.DEPTH).joinToString("/") { "d" }) }.exceptionOrNull() is SkillImportLimits.LimitException)
    }

    @Test fun rejectsTraversalAliasesAndMalformedDirectoriesWithoutDraining() {
        for (name in listOf("../secret", "/absolute", "C:\\secret", "one/../../secret")) {
            assertTrue(runCatching { SkillImportLimits.zip(archive(listOf(name to 128 * 1024 * 1024))) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { SkillImportLimits.zip(archive(listOf("directory/" to 128 * 1024 * 1024))) }.exceptionOrNull() is SkillImportLimits.LimitException)
        assertTrue(runCatching { SkillImportLimits.zip(archive(listOf("file" to 1, "./file" to 1))) }.isFailure)
    }

    @Test fun inputReaderStopsAtLimitPlusOneWithoutTrustingLengthMetadata() {
        var count = 0
        val endless = object : InputStream() {
            override fun read(): Int { count++; return 1 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { count += length; return length }
        }
        assertTrue(runCatching { SkillImportLimits.read(endless, 8192) }.exceptionOrNull() is SkillImportLimits.LimitException)
        assertEquals(8193, count)
        assertArrayEquals(byteArrayOf(1, 2), SkillImportLimits.read(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
    }
}
