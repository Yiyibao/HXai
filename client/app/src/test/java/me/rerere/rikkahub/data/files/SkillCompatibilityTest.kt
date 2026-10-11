package me.rerere.rikkahub.data.files
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class SkillCompatibilityTest {
    @Test fun distinguishesInstructionsReferencesAssetsAndMissingRuntime() {
        val root = java.nio.file.Files.createTempDirectory("skill-status").toFile()
        try {
            val dir = File(root, "file-guide").apply { mkdirs() }
            File(dir, "SKILL.md").writeText("---\nname: file-guide\ndescription: 文件说明\nlicense: MIT\nallowed-tools: read_material_text_page\n---\nUse native file tools and [reference](references/guide.md).")
            File(dir, "references").mkdirs(); File(dir, "references/guide.md").writeText("bounded reference")
            val metadata = SkillMetadata("file-guide", "说明", skillDir = dir, license = "MIT", allowedTools = "read_material_text_page")
            assertEquals("READY", SkillCompatibility.inspect(metadata).state)
            assertTrue(SkillCompatibility.inspect(metadata).files.contains("references/guide.md"))
            File(dir, "scripts").mkdirs(); File(dir, "scripts/run.py").writeText("print('synthetic')")
            assertEquals("PARTIAL", SkillCompatibility.inspect(metadata).state)
            assertTrue(SkillCompatibility.inspect(metadata).detail.contains("不能执行Python"))
            File(dir, "scripts/run.py").delete(); File(dir, "scripts").delete()
            assertEquals("PARTIAL", SkillCompatibility.inspect(metadata.copy(compatibility = "Requires node and LibreOffice")).state)
            val parsed = SkillFrontmatterParser.parse(File(dir, "SKILL.md").readText())
            assertEquals("MIT", parsed["license"]); assertEquals("read_material_text_page", parsed["allowed-tools"])
        } finally { root.deleteRecursively() }
    }
}
