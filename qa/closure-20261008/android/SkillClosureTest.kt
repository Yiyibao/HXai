package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.files.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

class SkillClosureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun nestedBrokenLinksAndSiblingReplacement() = runBlocking {
        val manager = GlobalContext.get().get<SkillManager>(); val evidence = QaEvidenceRun("SkillClosureTest.nestedBrokenLinksAndSiblingReplacement"); val name = "qa-${evidence.runId}"; val dir = File(manager.getSkillsDir(), name); val alias = File(manager.getSkillsDir(), "$name-alias"); val outside = evidence.file("outside/sentinel.txt").apply { writeText("QA sentinel immutable") }; var status = "FAILED"
        val content = "---\nname: $name\ndescription: QA\n---\noriginal"
        try {
            assertNotNull(manager.saveSkill(name, content)); val nested = File(dir, "nested/deep").apply { mkdirs() }
            android.system.Os.symlink(outside.parentFile!!.path, File(nested, "external").path)
            android.system.Os.symlink(File(outside.parentFile, "missing").path, File(nested, "broken").path)
            assertNull(manager.resolveSkillFile(name, "nested/deep/external/sentinel.txt"))
            val tool = me.rerere.rikkahub.data.ai.tools.createSkillTools(setOf(name), manager.listSkills()).single()
            assertTrue(runCatching { tool.execute(kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name)); put("path", kotlinx.serialization.json.JsonPrimitive("nested/deep/broken"))
            }) }.isFailure)
            android.system.Os.symlink(dir.path, alias.path)
            assertFalse(manager.deleteSkill(alias.name)); assertFalse(manager.saveSkillFileBytesAtomically(alias.name, mapOf("SKILL.md" to "bad".toByteArray())))
            assertEquals(content, manager.readSkillContent(name)); assertTrue(manager.deleteSkill(name)); assertEquals("QA sentinel immutable", outside.readText()); assertFalse(dir.exists()); status = "PASS"
        } finally { alias.delete(); File(dir, "nested/deep/external").delete(); File(dir, "nested/deep/broken").delete(); manager.deleteSkill(name); evidence.finish(status) }
    }
    @Test fun replacementAndRollbackFailureKeepOriginalContentAndEnabledState() = runBlocking {
        val settings = GlobalContext.get().get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }; val manager = GlobalContext.get().get<SkillManager>(); val evidence = QaEvidenceRun("SkillClosureTest.replacementAndRollbackFailureKeepOriginalContentAndEnabledState"); val name = "qa-${evidence.runId}"; val content = "---\nname: $name\ndescription: QA\n---\noriginal"; val dir = File(manager.getSkillsDir(), name); var status = "FAILED"
        try {
            assertNotNull(manager.saveSkill(name, content)); settings.update { s -> s.copy(assistants = s.assistants.map { it.copy(enabledSkills = it.enabledSkills + name) }) }
            for (failures in listOf(setOf(1), setOf(2), setOf(2, 3))) {
                var calls = 0
                val controlled = SkillManager(context, settings, renameTree = { from, to -> calls++; if(calls in failures) false else from.renameTo(to) })
                assertFalse(controlled.saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.replace("original", "replacement").toByteArray())))
                assertEquals("rename failures $failures retain original", content, manager.readSkillContent(name)); assertTrue(settings.settingsFlow.value.assistants.all { name in it.enabledSkills })
            }
            val cleanupFails = SkillManager(context, settings, removeTree = { false })
            assertTrue(cleanupFails.saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.replace("original", "replacement").toByteArray())))
            assertEquals(content.replace("original", "replacement"), manager.readSkillContent(name)); assertTrue(settings.settingsFlow.value.assistants.all { name in it.enabledSkills })
            assertFalse(cleanupFails.deleteSkill(name)); assertEquals(content.replace("original", "replacement"), manager.readSkillContent(name)); assertTrue(settings.settingsFlow.value.assistants.all { name in it.enabledSkills })
            status = "PASS"
        } finally {
            manager.deleteSkill(name)
            manager.getSkillsDir().listFiles().orEmpty().filter { it.name.startsWith(".$name.") }.forEach { deleteSkillTree(it) }
            settings.update(original); evidence.file("assertions.txt").writeText("status=$status\n"); evidence.finish(status)
        }
    }
    @Test fun partialUninstallCleanupFailureRestoresEntireSkill() = runBlocking {
        val settings = GlobalContext.get().get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }; val manager=GlobalContext.get().get<SkillManager>(); val evidence=QaEvidenceRun("SkillClosureTest.partialUninstallCleanupFailureRestoresEntireSkill"); val name="qa-${evidence.runId}"; val content="---\nname: $name\ndescription: QA\n---\noriginal"; var status="FAILED"
        try {
            assertTrue(manager.saveSkillFileBytesAtomically(name,mapOf("SKILL.md" to content.toByteArray(),"assets/data.bin" to byteArrayOf(0,1,2,-1))))
            settings.update { s -> s.copy(assistants=s.assistants.map { it.copy(enabledSkills=it.enabledSkills+name) }) }
            withTimeout(20000) { settings.settingsFlow.first { it.assistants.all { a -> name in a.enabledSkills } } }
            var failed=false
            val controlled=SkillManager(context,settings,removeTree={ directory -> if(!failed && !directory.name.contains("backup")) { failed=true; File(directory,"SKILL.md").delete(); false } else deleteSkillTree(directory) })
            assertFalse(controlled.deleteSkill(name))
            val actual=manager.readSkillContent(name)
            evidence.file("result.txt").writeText("partialCleanupInjected=$failed\noriginalReadable=${actual==content}\nenabled=${settings.settingsFlow.value.assistants.all { name in it.enabledSkills }}\n")
            assertEquals("Failure after deleting SKILL.md must retain complete installed content",content,actual)
            assertArrayEquals(byteArrayOf(0,1,2,-1),File(manager.getSkillDir(name),"assets/data.bin").readBytes()); assertTrue(settings.settingsFlow.value.assistants.all { name in it.enabledSkills }); status="PASS"
        } finally { manager.deleteSkill(name); manager.getSkillsDir().listFiles().orEmpty().filter { it.name.startsWith(".$name.") }.forEach { deleteSkillTree(it) }; settings.update(original); evidence.finish(status) }
    }
}
