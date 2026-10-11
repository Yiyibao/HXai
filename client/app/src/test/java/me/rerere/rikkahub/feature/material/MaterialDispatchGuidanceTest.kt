package me.rerere.rikkahub.feature.material

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MaterialDispatchGuidanceTest {
    @Test fun enabledServiceGivesAutomaticRoutingAndAsyncContinuation() {
        val prompt = MaterialDispatchGuidance.prompt(setOf("parse_material_enhanced", "list_material_parse_results"), true)
        assertTrue(prompt.contains("应主动调用"))
        assertTrue(prompt.contains("超过5页仍传完整范围"))
        assertTrue(prompt.contains("主动用read_material_parse_blocks"))
        assertTrue(prompt.contains("只有附件没有任务"))
        assertTrue(prompt.contains("进行中的任务不重复提交"))
    }

    @Test fun unavailableRemoteAndVisionDoNotAdvertiseThoseCapabilities() {
        val prompt = MaterialDispatchGuidance.prompt(setOf("read_material_text_page"), false)
        assertTrue(prompt.contains("本轮不能新增远端解析任务"))
        assertTrue(prompt.contains("当前模型没有视觉输入"))
        assertFalse(prompt.contains("本轮parse_material_enhanced可用"))
    }

    @Test fun historicalContentDoesNotInventPdfCoverage() {
        val content = Json.decodeFromString<MaterialContent>("""{"text":"old cached text","coverage":"legacy"}""")
        assertNull(content.totalPages)
        assertNull(content.extractedPages)
        assertTrue(content.pagesWithoutExtractableText.isEmpty())
    }
}
