package me.rerere.rikkahub.service

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.tools.displayImageTool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.interactive.interactiveUiTool
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Actual Kotlin -> Flutter rendering with owned synthetic PNG bytes; no model or remote image calls. */
class ChatImageRenderingTest {
    @Test fun assistantImagesAppearInActualChat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val koin = GlobalContext.get()
        val settings = runBlocking { koin.get<SettingsStore>().settingsFlow.first { !it.init } }
        val service = koin.get<ChatService>()
        val repository = koin.get<ConversationRepository>()
        val blue = Color.rgb(12, 105, 220)
        val bitmap = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(blue)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val materials = koin.get<me.rerere.rikkahub.feature.material.MaterialFiles>()
        val asset = runBlocking {
            me.rerere.rikkahub.data.ai.tools.persistWebImage(me.rerere.search.VerifiedWebImage(
                me.rerere.search.SearchImage("https://source.example/figure.png"),240,120,"image/png",
                java.time.Instant.now().toString(),png),materials,settings.activeProfileId)
        }
        val imageFile = materials.verifiedFile(settings.activeProfileId,asset.id)
        val url = imageFile.toURI().toString()
        val imageArgs = buildJsonObject {
            put("url", url); put("caption", "工具配图")
            put("sourceUrl", "https://source.example/article"); put("sourceTitle", "图片来源测试")
        }
        val surfaceArgs = buildJsonObject { put("definition", """{"version":3,"title":"界面配图","components":[{"id":"figure","type":"image","url":"$url","caption":"真实图片组件"}]}""") }
        val scenes = mapOf(
            "markdown" to listOf(UIMessagePart.Text("配图前的解释。\n\n![网络配图]($url)\n\n配图后的说明。")),
            "tool" to listOf(UIMessagePart.Tool("qa-image", "display_image", imageArgs.toString(), runBlocking { displayImageTool(materials,settings.activeProfileId).execute(imageArgs) }, executionState = ToolExecutionState.COMPLETED)),
            "base64" to listOf(UIMessagePart.Image("data:image/png;base64,${Base64.encodeToString(png, Base64.NO_WRAP)}")),
            "local" to listOf(UIMessagePart.Image(imageFile.toURI().toString())),
            "surface" to listOf(UIMessagePart.Tool("qa-surface", "render_interactive_ui", surfaceArgs.toString(), runBlocking { interactiveUiTool().execute(surfaceArgs) }, executionState = ToolExecutionState.COMPLETED)),
        )
        try {
            for ((name, answer) in scenes) {
                val response = UIMessage.assistant("").copy(parts = answer, generationState = "COMPLETED")
                // The normal generation pipeline converts Base64 to a managed file before Room persistence.
                val persisted = if (name == "base64") runBlocking {
                    koin.get<FilesManager>().convertBase64ImagePartToLocalFile(response, materials, settings.activeProfileId)
                } else response
                val conversation = Conversation(assistantId = settings.assistantId, profileId = settings.activeProfileId,
                    title = "Image QA $name", newConversation = true,
                    messageNodes = listOf(UIMessage.user("请在回答里展示图片。").toMessageNode(),
                        persisted.toMessageNode()))
                try {
                    runBlocking {
                        service.addConversationReference(conversation.id)
                        service.initializeConversation(conversation.id)
                        service.saveConversation(conversation.id, conversation)
                    }
                    ActivityScenario.launch(StudyFlutterActivity::class.java).use {
                        instrumentation.waitForIdleSync()
                        var screenshot: Bitmap? = null
                        var count = 0
                        val deadline = System.currentTimeMillis() + 15000
                        while (System.currentTimeMillis() < deadline && count < 500) {
                            Thread.sleep(500)
                            screenshot?.recycle()
                            screenshot = instrumentation.uiAutomation.takeScreenshot()
                            count = 0
                            screenshot?.let { shot ->
                                for (y in 0 until shot.height step 3) for (x in 0 until shot.width step 3) {
                                    if (shot.getPixel(x, y) == blue) count++
                                }
                            }
                        }
                        try {
                            assertTrue("No decoded image pixels in actual chat: $name", count >= 500)
                            File(context.cacheDir, "qa-chat-image-$name.png").outputStream().use { output ->
                                assertTrue(screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, output))
                            }
                        } finally { screenshot?.recycle() }
                    }
                } finally {
                    runBlocking {
                        repository.getConversationById(conversation.id)?.let { service.deleteConversation(it) }
                        service.removeConversationReference(conversation.id)
                    }
                }
            }
        } finally {
            imageFile.delete()
            imageFile.parentFile!!.resolve("${asset.id}.meta.json").delete()
        }
    }
}
