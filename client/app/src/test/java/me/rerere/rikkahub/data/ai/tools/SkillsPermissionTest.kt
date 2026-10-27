package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsPermissionTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun revokedSkillCannotBeLoadedFromPreviouslyCreatedTool(): Unit=runBlocking {
        val dir=temp.newFolder("skill");dir.resolve("SKILL.md").writeText("---\nname: read\ndescription: read\n---\n已授权说明 α")
        var enabled=true
        val tool=createSkillTools(setOf("read"),listOf(SkillMetadata(name="read",description="read",skillDir=dir)),checkEnabled={ require(enabled) }).single()
        val input=buildJsonObject { put("name","read") }
        assertTrue(tool.execute(input).isNotEmpty())
        enabled=false
        assertTrue(runCatching { tool.execute(input) }.isFailure)
    }
}
