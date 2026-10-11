package me.rerere.rikkahub.feature.imagecreation

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opt in with -e imageLive true. At most one generation and one edit. */
@RunWith(AndroidJUnit4::class)
class ImageCreationLiveUiTest {
    @Test fun loadingPreviewWithoutRequests() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("imageLoadingPreview") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val id = arguments.getString("imageResume") ?: error("A saved image is required")
        val file = context.filesDir.resolve("image_creation").walkTopDown().single { it.name == "$id.json" }
        val saved = file.readText()
        val record = Json.parseToJsonElement(saved).jsonObject
        fun find(label: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && listOf(node.text, node.contentDescription, node.hintText).any { it?.toString()?.contains(label) == true }) return node
            for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
            return null
        }
        fun seek(label: String): AccessibilityNodeInfo {
            val until = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < until) { find(label)?.let { return it }; Thread.sleep(100) }
            error("Missing preview UI: $label")
        }
        fun click(label: String) { var node = seek(label)
            while (!node.isClickable) node = node.parent ?: error("Missing preview control")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(400) }
        fun launch() = context.startActivity(android.content.Intent(context, me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        launch(); click("历史对话"); click("图片"); click(record.getValue("prompt").jsonPrimitive.content)
        click("回到来源聊天"); seek("已生成这张图片")
        try {
            // Temporarily preview a waiting state after the service singleton initialized.
            // No imageStart call, HTTP request, or conversation modification occurs.
            file.writeText(buildJsonObject { record.forEach { (key, value) -> if (key != "artifactId") put(key, value) }; put("state", "RUNNING") }.toString())
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            Thread.sleep(500); launch(); seek("正在生成图片"); Thread.sleep(300)
            repeat(8) { frame ->
                val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
                try { context.cacheDir.resolve("image-loading-$frame.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
                Thread.sleep(450)
            }
        } finally { file.writeText(saved) }
        assertEquals(saved, file.readText())
    }

    @Test fun generationAndReferenceEditThroughFlutter() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("imageLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun find(label: String, node: AccessibilityNodeInfo? = automation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && listOf(node.text, node.contentDescription, node.hintText).any { it?.toString()?.contains(label) == true }) return node
            for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
            return null
        }
        fun seek(label: String): AccessibilityNodeInfo {
            val until = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < until) { find(label)?.let { return it }; Thread.sleep(100) }
            error("Missing UI: $label")
        }
        fun click(label: String) {
            val until = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < until) {
                var node = find(label)
                while (node != null && !node.isClickable) node = node.parent
                if (node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(400); return }
                Thread.sleep(100)
            }
            val details = StringBuilder()
            fun dump(node: AccessibilityNodeInfo?) { if (node == null) return
                details.append("${node.text} | ${node.contentDescription} | ${node.hintText} | enabled=${node.isEnabled} clickable=${node.isClickable}\n")
                for (i in 0 until node.childCount) dump(node.getChild(i)) }
            dump(automation.rootInActiveWindow)
            context.cacheDir.resolve("image-live-diagnostic.txt").writeText(details.toString())
            error("Missing clickable control: $label")
        }
        fun enter(label: String, text: String) {
            seek(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            seek(label).performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(500)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            assertTrue(seek(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            Thread.sleep(500)
            assertEquals("Input must match before any paid request", text, seek(label).text?.toString())
        }
        fun capture(name: String) {
            val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
            try { context.cacheDir.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        }
        fun records() = context.filesDir.resolve("image_creation").walkTopDown().filter { it.extension == "json" }
            .mapNotNull { runCatching {
                val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
                codec.encodeToJsonElement(codec.decodeFromString<ImageCreationTask>(it.readText())).jsonObject
            }.getOrNull() }.toList()
        fun awaitTask(prompt: String): JsonObject {
            val until = System.currentTimeMillis() + 310000
            while (System.currentTimeMillis() < until) {
                records().firstOrNull { it["prompt"]?.jsonPrimitive?.content == prompt }?.let { task ->
                    if (task["state"]?.jsonPrimitive?.content != "RUNNING") {
                        assertEquals(task["error"]?.toString(), "SUCCEEDED", task["state"]?.jsonPrimitive?.content)
                        return task
                    }
                }
                Thread.sleep(500)
            }
            error("Live image task timed out; no automatic retry")
        }
        val resume = InstrumentationRegistry.getArguments().getString("imageResume")?.let { id -> records().single { it["id"]?.jsonPrimitive?.content == id } }
        val marker = System.currentTimeMillis()
        val prompt = "A flat blue circle on a plain white background, no text. Test $marker."
        context.startActivity(android.content.Intent(context, me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        click("历史对话"); click("图片")
        val original = if (resume == null) {
            click("创建图片"); seek("gpt-image-2"); seek("经济")
            enter("描述你想创作的图片", prompt); click("生成图片")
            awaitTask(prompt).also { seek("已生成这张图片"); capture("image-live-chat.png"); click("修改") }
        } else {
            assertEquals("SUCCEEDED", resume["state"]?.jsonPrimitive?.content)
            click(resume.getValue("prompt").jsonPrimitive.content)
            resume
        }
        seek("当前版本 v1")
        val originalFile = context.filesDir.resolve("materials/${original.getValue("profileId").jsonPrimitive.content}/${original.getValue("artifactId").jsonPrimitive.content}.png")
        val originalBytes = originalFile.readBytes()
        val savedEdit = InstrumentationRegistry.getArguments().getString("imageEditResume")?.let { id -> records().single { it["id"]?.jsonPrimitive?.content == id } }
        val edited = if (savedEdit == null) {
            val editPrompt = "Keep the same composition and white background, change the blue circle to green. Test $marker."
            enter("描述你想修改的地方", editPrompt); click("生成图片")
            awaitTask(editPrompt)
        } else { click("v2"); savedEdit }
        assertEquals("SUCCEEDED", edited.getValue("state").jsonPrimitive.content)
        assertEquals(original.getValue("id"), edited["parentId"])
        assertEquals(original.getValue("rootId"), edited["rootId"])
        assertEquals(2, edited.getValue("revision").jsonPrimitive.int)
        assertEquals(original.getValue("conversationId"), edited["conversationId"])
        assertArrayEquals(originalBytes, originalFile.readBytes())
        seek("当前版本 v2"); seek("基于 v2"); Thread.sleep(1500); capture("image-live-edit.png")
        click("回到来源聊天"); seek("已生成这张图片")
        seek("生成图片预览 v2"); capture("image-live-chat.png")
        click("历史对话"); click("图片"); seek("AI 生成 · v2"); capture("image-live-library.png")
        context.cacheDir.resolve("image-live-result.json").writeText(buildJsonObject {
            put("generationState", original.getValue("state")); put("editState", edited.getValue("state"))
            put("model", edited.getValue("model")); put("quality", edited.getValue("quality"))
            put("revision", edited.getValue("revision")); put("sameConversation", true)
        }.toString())
    }
}
