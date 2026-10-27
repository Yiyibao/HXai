package me.rerere.rikkahub.feature.material

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.*

class OfficePackageTest {
    private val pkg = "http://schemas.openxmlformats.org/package/2006/relationships"
    private fun withZip(parts: Map<String, ByteArray>, check: (File) -> Unit) {
        val dir = Files.createTempDirectory("office-zip").toFile()
        try {
            val source = File(dir, "source.zip")
            ZipOutputStream(source.outputStream()).use { out -> parts.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() } }
            check(source)
        } finally { dir.deleteRecursively() }
    }
    @Test fun normalDirectoriesAndUtf16PartsPassWithoutExtraction() {
        withZip(mapOf("word/" to byteArrayOf(), "word/document.xml" to "<document>中文</document>".toByteArray(Charsets.UTF_16))) {
            assertEquals(2, OfficePackage.read(it).size)
        }
    }
    @Test fun rejectsTraversalWindowsPathsMacrosSignaturesAndMalformedUnusedXml() {
        listOf("../escape", "/absolute", "word/../escape", "word/./escape", "word//escape", "C:/escape", "word\\escape", "_XMLSIGNATURES/sig.xml", "word/vbaProject.bin", "word/vbaData.xml").forEach { name ->
            withZip(mapOf(name to "<root/>".toByteArray())) { assertTrue(name, runCatching { OfficePackage.read(it) }.isFailure) }
        }
        withZip(mapOf("word/document.xml" to "<document/>".toByteArray(), "word/unused.xml" to "<broken>".toByteArray())) {
            assertTrue(runCatching { OfficePackage.read(it) }.isFailure)
        }
    }
    @Test fun rejectsDuplicateNamesInCentralDirectory() {
        withZip(mapOf("word/file1.xml" to "<root/>".toByteArray(), "word/file2.xml" to "<root/>".toByteArray())) { file ->
            // Same length replacement in both local and central headers creates a real duplicate ZIP.
            file.writeBytes(file.readBytes().toString(Charsets.ISO_8859_1).replace("word/file2.xml", "word/file1.xml").toByteArray(Charsets.ISO_8859_1))
            assertTrue(runCatching { OfficePackage.read(file) }.isFailure)
        }
    }
    @Test fun rejectsExternalAndDuplicateRelationshipsAndMacroContentTypes() {
        listOf(
            """<Relationships xmlns="$pkg"><Relationship Id="a" Target="https://invalid.example/image" TargetMode="External"/></Relationships>""",
            """<Relationships xmlns="$pkg"><Relationship Id="a" Target="file"/><Relationship Id="a" Target="other"/></Relationships>""",
            """<Relationships xmlns="$pkg"><Relationship Id="a" Target="file:///synthetic"/></Relationships>""",
        ).forEach { xml -> withZip(mapOf("_rels/.rels" to xml.toByteArray(Charsets.UTF_16))) { assertTrue(runCatching { OfficePackage.read(it) }.isFailure) } }
        withZip(mapOf("[Content_Types].xml" to """<Types><Override ContentType="application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml"/></Types>""".toByteArray())) {
            assertTrue(runCatching { OfficePackage.read(it) }.isFailure)
        }
    }
    @Test fun rejectsPerPartExpandedAndEntryCountLimits() {
        listOf(mapOf("word/document.xml" to ByteArray(4 * 1024 * 1024 + 1)),
            mapOf("media/large.bin" to ByteArray(16 * 1024 * 1024 + 1)),
            (1..5).associate { "media/$it.bin" to ByteArray(14 * 1024 * 1024) },
            (1..2001).associate { "media/$it.bin" to byteArrayOf() }
        ).forEach { entries -> withZip(entries) { assertTrue(runCatching { OfficePackage.read(it) }.isFailure) } }
    }
}
