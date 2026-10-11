package me.rerere.rikkahub.service

import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillsVM
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillsInstallBoundaryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun uninstallAndReplacementDoNotFollowDirectoryLinksOrSiblingAliases() = runBlocking {
        val manager = GlobalContext.get().get<SkillManager>()
        val name = "qa-delete-${UUID.randomUUID()}"
        val outside = File(context.cacheDir, "$name-outside").apply { mkdir() }
        val sentinel = File(outside, "sentinel.txt").apply { writeText("synthetic private QA bytes") }
        val directory = File(manager.getSkillsDir(), name)
        val alias = File(manager.getSkillsDir(), "$name-alias")
        val content = "---\nname: $name\ndescription: QA\n---\nsynthetic"
        require(!directory.exists() && !alias.exists())
        try {
            assertNotNull(manager.saveSkill(name, content))
            android.system.Os.symlink(directory.path, alias.path)
            assertFalse(manager.deleteSkill(alias.name))
            assertTrue(directory.exists())
            alias.delete()
            android.system.Os.symlink(outside.path, File(directory, "assets").path)
            assertTrue(manager.saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray())))
            assertEquals("synthetic private QA bytes", sentinel.readText())
            android.system.Os.symlink(outside.path, File(directory, "assets").path)
            assertTrue(manager.deleteSkill(name))
            assertEquals("synthetic private QA bytes", sentinel.readText())
            assertFalse(directory.exists())
        } finally {
            // Unlink first even if a regression assertion fails; do not traverse the target.
            alias.delete(); File(directory, "assets").delete(); manager.deleteSkill(name)
            sentinel.delete(); outside.delete()
        }
    }
    @Test fun oversizedZipIsRejectedAndExistingSkillAndBinaryInstallArePreserved() = runBlocking {
        val manager = GlobalContext.get().get<SkillManager>()
        val name = "qa-bounded-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "$name.zip")
        val directory = File(manager.getSkillsDir(), name)
        val content = "---\nname: $name\ndescription: synthetic QA\n---\nOriginal instructions"
        require(!directory.exists())
        val vm = SkillsVM(manager)
        suspend fun install(): Pair<Boolean, String> {
            val result = CompletableDeferred<Pair<Boolean, String>>()
            withContext(Dispatchers.Main) { vm.importSkillFromFile(context, source.toUri()) { ok, message -> result.complete(ok to message) } }
            return withTimeout(20000) { result.await() }
        }
        fun zip(size: Int) {
            ZipOutputStream(source.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("SKILL.md")); out.write(content.toByteArray()); out.closeEntry()
                out.putNextEntry(ZipEntry("assets/binary.bin"))
                val chunk = ByteArray(8192) { 0x7f }
                var remaining = size
                while (remaining > 0) { val count = minOf(remaining, chunk.size); out.write(chunk, 0, count); remaining -= count }
                out.closeEntry()
            }
        }
        try {
            assertNotNull(manager.saveSkill(name, content))
            zip(128 * 1024 * 1024)
            val denied = install()
            assertFalse(denied.first); assertTrue(denied.second.contains("上限"))
            assertEquals(content, manager.readSkillContent(name))
            assertFalse(File(directory, "assets/binary.bin").exists())
            zip(321)
            assertTrue(install().first)
            assertEquals(content, manager.readSkillContent(name))
            assertArrayEquals(ByteArray(321) { 0x7f }, File(directory, "assets/binary.bin").readBytes())
            assertTrue(manager.deleteSkill(name))
            assertFalse(directory.exists())
            assertNull(manager.readSkillContent(name))
            assertTrue(manager.listSkills().none { it.name == name })
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("qa_skill_fixture", name) })
        } finally {
            require(directory.canonicalFile.parentFile == manager.getSkillsDir().canonicalFile)
            manager.deleteSkill(name)
            source.delete()
        }
    }

    @Test fun defaultSkillReadAndListingRejectOutsideSymlinks() = runBlocking {
        val manager = GlobalContext.get().get<SkillManager>()
        val name = "qa-link-${UUID.randomUUID()}"
        val directory = File(manager.getSkillsDir(), name)
        val external = File(context.cacheDir, "$name.md")
        require(!directory.exists()); directory.mkdir()
        val link = File(directory, "SKILL.md")
        try {
            external.writeText("---\nname: $name\ndescription: synthetic outside file\n---\nQA_PRIVATE_SENTINEL")
            android.system.Os.symlink(external.path, link.path)
            assertTrue(manager.listSkills().none { it.name == name })
            assertNull(manager.readSkillContent(name))
            val tool = createSkillTools(setOf(name), listOf(SkillMetadata(name = name, description = "QA", skillDir = directory))).single()
            assertTrue(runCatching { tool.execute(buildJsonObject { put("name", name) }) }.isFailure)
            assertTrue(runCatching { tool.execute(buildJsonObject { put("name", name); put("path", "SKILL.md") }) }.isFailure)
        } finally { link.delete(); directory.delete(); external.delete() }
    }
}
