package me.rerere.rikkahub.service

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.files.*
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.feature.material.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyHostFacade
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

class CoreSkillsWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun instructionsReferencesNativeAssetsAndEnabledBridgeWork() = runBlocking {
        val koin = GlobalContext.get(); val manager = koin.get<SkillManager>(); val settings = koin.get<SettingsStore>(); val materials = koin.get<MaterialFiles>()
        val original = settings.settingsFlow.first { !it.init }; val owner = original.activeProfileId
        val names = (1..3).map { "qa-core-skill-${UUID.randomUUID()}" }
        val artifacts = mutableListOf<MaterialFile>()
        val template = File(context.cacheDir, "qa-skill-template.docx")
        val facade = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), materials, koin.get(), pickFiles = { emptyList() })
        try {
            MaterialWriter.write(template, "docx", "Native template paragraph", emptyList(), emptyList())
            for ((index, name) in names.withIndex()) {
                val body = if (index == 0) "Answer concisely using the loaded instruction." else if (index == 1) "Read [guide](references/guide.md)." else "Load [template](assets/template.docx), inspect its material ID, then edit with native file tools. Do not execute scripts."
                val files = mutableMapOf("SKILL.md" to "---\nname: $name\ndescription: Native QA workflow\nlicense: MIT\nallowed-tools: use_skill inspect_material_edit edit_material_file\n---\n$body".toByteArray())
                if (index == 1) files["references/guide.md"] = "QA reference evidence 42".toByteArray()
                if (index == 2) files["assets/template.docx"] = template.readBytes()
                assertTrue(manager.saveSkillFileBytesAtomically(name, files))
                val metadata = manager.findSkill(name)!!
                assertEquals("MIT", metadata.license)
                assertEquals("READY", SkillCompatibility.inspect(metadata).state)
                assertTrue(facade.setSkillEnabled(owner, name, true, 1L).success)
                assertTrue(facade.getSkills(owner).single { it.name == name }.enabled)
                val tool = createSkillTools(setOf(name), listOf(metadata), materials, owner).single()
                assertTrue((tool.execute(buildJsonObject { put("name", name) }).single() as UIMessagePart.Text).text.contains(body))
                if (index == 1) assertEquals("QA reference evidence 42", (tool.execute(buildJsonObject { put("name", name); put("path", "references/guide.md") }).single() as UIMessagePart.Text).text)
                if (index == 2) {
                    val result = tool.execute(buildJsonObject { put("name", name); put("path", "assets/template.docx") })
                    val document = result.filterIsInstance<UIMessagePart.Document>().single()
                    val asset = materials.list(owner).single { materials.file(it).toURI().path == android.net.Uri.parse(document.url).path }; artifacts += asset
                    assertTrue(MaterialReader.read(materials.verifiedFile(owner, asset.id), "docx").text.contains("Native template paragraph"))
                    val editor = materialEditTools(materials, owner).single { it.name == "edit_material_file" }
                    editor.execute(buildJsonObject { put("id", asset.id); put("expected_sha256", asset.sha256); put("operation", "replace_paragraph"); put("spec", "{\"index\":1,\"expectedText\":\"Native template paragraph\",\"text\":\"Native skill output\"}"); put("title", "QA native skill") })
                    val output = materials.list(owner).single { it.output && it.sourceId == asset.id }; artifacts += output
                    assertTrue(MaterialReader.read(materials.verifiedFile(owner, output.id), "docx").text.contains("Native skill output"))
                }
                assertTrue(runCatching { tool.execute(buildJsonObject { put("name", name); put("path", "../secret") }) }.isFailure)
            }
            assertTrue(runCatching { facade.setSkillEnabled("another-owner", names[0], false, 1L) }.isFailure)
            assertTrue(runCatching { facade.setSkillEnabled(owner, names[0], false, 999L) }.isFailure)
        } finally {
            facade.close(); names.forEach { manager.deleteSkill(it) }; settings.update(original); template.delete()
            artifacts.forEach { materials.file(it).delete(); File(context.filesDir, "materials/$owner/${it.id}.meta.json").delete() }
        }
    }
    @Test fun pinnedCommunityInstructionsAndReferencesLoadAndOfficeDependenciesAreExplicit() = runBlocking {
        val directory = File(context.cacheDir, "core-v1-community")
        require(directory.isDirectory) { "Pinned public community fixtures must be supplied" }
        val commsDir = File(directory, "internal-comms")
        val commsContent = File(commsDir, "SKILL.md").readText(); val commsFront = SkillFrontmatterParser.parse(commsContent)
        val comms = SkillMetadata(commsFront["name"]!!, commsFront["description"]!!, skillDir = commsDir, license = commsFront["license"])
        val tool = createSkillTools(setOf(comms.name), listOf(comms)).single()
        assertTrue((tool.execute(buildJsonObject { put("name", comms.name) }).single() as UIMessagePart.Text).text.isNotBlank())
        val reference = tool.execute(buildJsonObject { put("name", comms.name); put("path", "examples/3p-updates.md") }).single() as UIMessagePart.Text
        assertEquals(File(commsDir, "examples/3p-updates.md").readText(), reference.text)
        val docxDir = File(directory, "docx"); val front = SkillFrontmatterParser.parse(File(docxDir, "SKILL.md").readText())
        val docx = SkillMetadata(front["name"]!!, front["description"]!!, skillDir = docxDir, license = front["license"])
        val status = SkillCompatibility.inspect(docx)
        assertEquals("PARTIAL", status.state)
        assertTrue(status.detail.contains("不能执行Python")); assertTrue(docx.license!!.contains("Proprietary"))
    }
}
