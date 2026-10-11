package me.rerere.rikkahub.feature.imagecreation

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.feature.material.MaterialFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class ImageCreationRuntimeTest {
    @Test fun flutterLibraryAndComposerEntryDoNotSubmitPaidRequests() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun find(label: String, root: android.view.accessibility.AccessibilityNodeInfo? = automation.rootInActiveWindow): android.view.accessibility.AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isVisibleToUser && (root.text?.toString()?.contains(label) == true || root.contentDescription?.toString()?.contains(label) == true || root.hintText?.toString()?.contains(label) == true)) return root
            for (i in 0 until root.childCount) find(label, root.getChild(i))?.let { return it }
            return null
        }
        fun seek(label: String): android.view.accessibility.AccessibilityNodeInfo {
            val deadline = System.currentTimeMillis() + 15000
            while (System.currentTimeMillis() < deadline) { find(label)?.let { return it }; Thread.sleep(100) }
            val details = StringBuilder()
            fun dump(node: android.view.accessibility.AccessibilityNodeInfo?) { if (node == null) return
                details.append("${node.text} | ${node.contentDescription} | ${node.hintText}\n")
                for (i in 0 until node.childCount) dump(node.getChild(i)) }
            dump(automation.rootInActiveWindow); context.cacheDir.resolve("image-entry-diagnostic.txt").writeText(details.toString())
            error("Flutter entry missing: $label")
        }
        fun click(label: String) { var node = seek(label); while (!node.isClickable) node = node.parent ?: error("Missing clickable parent")
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(350) }
        fun capture(name: String) { Thread.sleep(650); val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
            try { context.cacheDir.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() } }
        context.startActivity(android.content.Intent(context, me.rerere.rikkahub.flutter.StudyFlutterActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        click("历史对话"); click("图片")
        capture("image-library-runtime.png"); seek("搜索图片"); seek("创建图片")
        click("创建图片"); seek("图片创作"); seek("经济"); capture("image-composer-runtime.png")
        seek("从一个想法开始"); capture("image-studio-top.png")
        fun scroll(node: android.view.accessibility.AccessibilityNodeInfo? = automation.rootInActiveWindow): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
            return false
        }
        assertTrue(scroll()); Thread.sleep(500); capture("image-studio-styles.png")
        repeat(2) { scroll(); Thread.sleep(350) }; capture("image-studio-templates.png")
    }

    @Test fun generationEditingIsolationAndRecoveryWithoutPaidCalls() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val koin = GlobalContext.get()
        val settings: SettingsStore = koin.get()
        withTimeout(15000) { while (settings.settingsFlow.value.init) delay(50) }
        val materials: MaterialFiles = koin.get()
        val owner = UUID.randomUUID().toString()
        val bytes = ByteArrayOutputStream().also { out -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { b ->
            b.eraseColor(android.graphics.Color.RED); b.compress(Bitmap.CompressFormat.PNG, 100, out); b.recycle()
        } }.toByteArray()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val server = ServerSocket(0)
        val serving = CoroutineScope(Dispatchers.IO).launch {
            repeat(3) { requestIndex ->
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream()
                    fun line(): String { val out = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) out.write(c) }; return out.toString("UTF-8") }
                    val first = line(); var length = 0
                    while (true) { val header = line(); if (header.isEmpty()) break; if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt() }
                    val body = input.readNBytes(length).toString(Charsets.UTF_8)
                    requests.add(first to body)
                    if (requestIndex == 2) delay(300)
                    val response = """{"data":[{"b64_json":"$encoded"}]}""".toByteArray()
                    runCatching { socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray() + response) }
                }
            }
        }
        val model = Model(modelId = "gpt-image-test", type = ModelType.IMAGE)
        val provider = ProviderSetting.OpenAI(apiKey = "synthetic-test-key", baseUrl = "http://127.0.0.1:${server.localPort}", models = listOf(model))
        val config = settings.settingsFlow.value.copy(providers = listOf(provider), imageGenerationModelId = model.id)
        val root = context.cacheDir.resolve("image-runtime-$owner")
        val images = ImageCreation(context, settings, materials, { config }, root)
        val conversation = Uuid.random().toString()
        suspend fun completed(id: String): ImageCreationTask = withTimeout(10000) { while (images.get(owner, id).state == "RUNNING") delay(25); images.get(owner, id) }
        try {
            val first = images.start(owner, conversation, "first", "red square", emptyList(), null, "1024x1024", "low") {}
            val original = completed(first.id)
            assertEquals("SUCCEEDED", original.state)
            val originalDigest = materials.get(owner, original.artifactId!!).sha256
            assertEquals(first.id, images.start(owner, conversation, "first", "red square", emptyList(), null, "1024x1024", "low") {}.id)
            assertTrue(runCatching { images.get(UUID.randomUUID().toString(), original.id) }.isFailure)
            val edit = completed(images.start(owner, conversation, "edit", "make blue", emptyList(), original.id, "1024x1024", "low") {}.id)
            assertEquals("SUCCEEDED", edit.state); assertEquals(2, edit.revision)
            assertEquals(original.id, edit.parentId); assertEquals(original.rootId, edit.rootId)
            assertEquals(originalDigest, materials.get(owner, original.artifactId).sha256)
            assertTrue(materials.verifiedFile(owner, edit.artifactId!!).isFile)
            assertEquals(2, requests.size)
            assertTrue(requests[0].first.contains("/images/generations"))
            assertEquals("low", Json.parseToJsonElement(requests[0].second).jsonObject.getValue("quality").jsonPrimitive.content)
            assertTrue(requests[1].first.contains("/images/edits")); assertTrue(requests[1].second.contains("name=\"image\""))
            val stopped = images.start(owner, conversation, "cancel", "cancel sample", emptyList(), null, "1024x1024", "low") {}
            withTimeout(10000) { while (requests.size < 3) delay(10) }
            images.cancel(owner, stopped.id)
            assertEquals("CANCELLED", completed(stopped.id).state)
            assertNull(images.get(owner, stopped.id).artifactId)
            serving.join()
            val metadata = root.resolve(owner).resolve("${edit.id}.json")
            metadata.writeText(metadata.readText().replace("\"state\":\"SUCCEEDED\"", "\"state\":\"RUNNING\""))
            val reopened = ImageCreation(context, settings, materials, { config }, root)
            assertEquals("INTERRUPTED", reopened.get(owner, edit.id).state)
            assertEquals(3, requests.size) // recovery never repeats a paid request
        } finally { server.close(); serving.cancel(); root.deleteRecursively() }
    }
}
