package me.rerere.rikkahub.feature.material

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Throwaway actual UI driver; deliberately incorrect synthetic cached parse, no recognizer. */
class MathCandidateSurfaceSmoke {
    @Test fun operateCandidateConfirmation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.first { !it.init }
        val fixture = MathParseCandidateFixture(context, settings.activeProfileId); fixture.prepare()
        File(context.cacheDir, "math-candidate-ui-setup.json").writeText(buildJsonObject {
            put("owner", fixture.owner); put("source_id", fixture.source.id); put("sha256", fixture.source.sha256); put("task_id", fixture.taskId)
        }.toString())
        instrumentation.uiAutomation.rootInActiveWindow
        val scenario = ActivityScenario.launch(StudyFlutterActivity::class.java)
        val command = File(context.cacheDir, "math-candidate-ui-command.json"); command.delete()
        var last = ""
        fun tree(node: AccessibilityNodeInfo?, rows: MutableList<JsonElement>, depth: Int = 0) {
            if (node == null || depth > 32 || rows.size >= 1500) return
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            rows += buildJsonObject {
                put("text", node.text?.toString() ?: ""); put("description", node.contentDescription?.toString() ?: "")
                put("editable", node.isEditable); put("clickable", node.isClickable)
                put("bounds", buildJsonArray { add(bounds.left); add(bounds.top); add(bounds.right); add(bounds.bottom) })
            }
            for (i in 0 until node.childCount) tree(node.getChild(i), rows, depth + 1)
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "CANDIDATE_UI_READY\n") })
        val end = System.currentTimeMillis() + 900000
        try {
            while (System.currentTimeMillis() < end) {
                val args = runCatching { Json.parseToJsonElement(command.readText()).jsonObject }.getOrNull()
                val id = args?.get("id")?.jsonPrimitive?.content
                if (args != null && id != null && id != last) {
                    last = id
                    val response = try {
                        val value: JsonElement = when (args.getValue("operation").jsonPrimitive.content) {
                            "semantics" -> { val rows = ArrayList<JsonElement>(); tree(instrumentation.uiAutomation.rootInActiveWindow, rows); JsonArray(rows) }
                            "records" -> fixture.files.mathWorkInputs.list(fixture.owner, 0, 50)
                            "read" -> fixture.files.mathWorkInputs.read(fixture.owner, args.getValue("work_id").jsonPrimitive.content)
                            "appearance" -> {
                                context.getSharedPreferences("study_appearance", 0).edit().putString("theme", args.getValue("theme").jsonPrimitive.content)
                                    .putFloat("fontScale", args.getValue("scale").jsonPrimitive.float).commit()
                                scenario.recreate(); JsonPrimitive(true)
                            }
                            "done" -> JsonPrimitive("DONE")
                            else -> error("unknown smoke command")
                        }
                        buildJsonObject { put("id", id); put("success", true); put("value", value) }
                    } catch (error: Exception) { buildJsonObject { put("id", id); put("success", false); put("error", error.toString()) } }
                    File(context.cacheDir, "math-candidate-ui-response.json").writeText(response.toString())
                    if (args["operation"] == JsonPrimitive("done")) break
                }
                delay(100)
            }
        } finally { scenario.close() }
        // Preserve synthetic originals, candidate and confirmed input for independent readback.
    }
}
