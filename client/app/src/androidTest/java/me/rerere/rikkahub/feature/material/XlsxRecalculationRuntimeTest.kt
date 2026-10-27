package me.rerere.rikkahub.feature.material

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.flutter.StudyFlutterActivity
import me.rerere.rikkahub.flutter.StudyHostFacade
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

/** Actual localhost gateway -> real UNO worker -> actual native download/readback/versions/UI. */
class XlsxRecalculationRuntimeTest {
    @Test fun realGatewayRecalculationPublishesImmutableVersionAndActualArtifactSurface() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "org.hxsi.agent.nativeqa")
        val fixture = Json.parseToJsonElement(File(context.cacheDir, "b1b-xlsx-fixture.json").readText()).jsonObject
        val port = fixture.getValue("port").jsonPrimitive.int
        val token = fixture.getValue("token").jsonPrimitive.content
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>(); val original = settings.settingsFlow.first { !it.init }
        val materials = koin.get<MaterialFiles>(); val docs = koin.get<DocumentServiceRepository>(); val office = docs.officeConversions
        val owner = settings.createLearnerProfile("B1b XLSX synthetic QA")
        val outputDir = File(context.cacheDir, "b1b-xlsx-evidence").apply { mkdirs() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "b1b-qa.example.org")
            // Test-only loopback transport, not fabricated HTTP replies or production configuration.
            val target = request.url.newBuilder().scheme("http").host("127.0.0.1").port(port).build()
            chain.proceed(request.newBuilder().url(target).build())
        }.build()
        val docHttp = DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }
        val officeHttp = OfficeConversionRepository::class.java.getDeclaredField("http").apply { isAccessible = true }
        val originalDocHttp = docHttp.get(docs); val originalOfficeHttp = officeHttp.get(office)
        docHttp.set(docs, client); officeHttp.set(office, client)
        var scenario: ActivityScenario<StudyFlutterActivity>? = null
        var facade: StudyHostFacade? = null
        var acceptedTask: OfficeConversionTask? = null
        fun text(node: AccessibilityNodeInfo?): String = if (node == null) "" else buildString {
            node.text?.let { append(it).append('\n') }; node.contentDescription?.let { append(it).append('\n') }
            for (index in 0 until node.childCount) append(text(node.getChild(index)))
        }
        fun click(label: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): Boolean {
            if (node == null) return false
            if (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString()?.contains(label) == true) {
                var candidate: AccessibilityNodeInfo = node
                while (!candidate.isClickable) candidate = candidate.parent ?: return false
                return candidate.isEnabled && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            for (index in 0 until node.childCount) if (click(label, node.getChild(index))) return true
            return false
        }
        fun waitFor(label: String, predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 40000
            while (System.currentTimeMillis() < deadline) {
                if (predicate()) return
                Thread.sleep(100)
            }
            File(outputDir, "surface-failure.txt").writeText(text(instrumentation.uiAutomation.rootInActiveWindow))
            fail(label)
        }
        try {
            val current = settings.settingsFlow.first { value -> value.learnerProfiles.any { it.id == owner } }
            val model = Model(modelId = "b1b-no-model-call", displayName = "B1b本机文件验证")
            val provider = ProviderSetting.OpenAI(name = "B1b synthetic", baseUrl = "http://127.0.0.1:9/v1", apiKey = "synthetic", models = listOf(model))
            settings.update(current.copy(providers = listOf(provider), chatModelId = model.id, enableSuggestion = false,
                assistants = current.assistants.map { it.copy(chatModelId = model.id) },
                learnerProfiles = current.learnerProfiles.map { it.copy(memoryEnabled = false) }))
            settings.selectLearnerProfile(owner); settings.settingsFlow.first { it.activeProfileId == owner }
            docs.configure(owner, "https://b1b-qa.example.org", token, true)
            val fixtureSource = File(context.cacheDir, "b1b-edited-source.xlsx")
            val source = materials.import(owner, fixtureSource.toUri(), "B1b edited source.xlsx")
            val sourceBytes = materials.verifiedFile(owner, source.id).readBytes()
            val task = office.submit(owner, source.id, source.sha256, "B1b true recalculated workbook", operation = XLSX_RECALCULATE)
            acceptedTask = task
            assertEquals(XLSX_RECALCULATE, task.operation)
            assertEquals(task.id, office.submit(owner, source.id, source.sha256, task.title, operation = XLSX_RECALCULATE).id)
            withTimeout(180000) {
                while (office.refresh(owner, task.id).state != "SUCCEEDED") {
                    check(office.task(owner, task.id).state !in setOf("FAILED", "CANCELLED"))
                    delay(250)
                }
            }
            val artifact = office.download(owner, task.id)
            assertEquals("xlsx", artifact.extension); assertEquals(source.id, artifact.sourceId)
            assertEquals(task.outputId, artifact.id); assertNotEquals(source.id, artifact.id)
            assertArrayEquals(sourceBytes, materials.verifiedFile(owner, source.id).readBytes())
            assertEquals(setOf(source.id, artifact.id), MaterialVersions.family(materials.list(owner), artifact.id).map { it.id }.toSet())
            val content = MaterialReader.read(materials.verifiedFile(owner, artifact.id), "xlsx").text
            assertTrue(content.contains("21") && content.contains("sum=22"))
            val recreated = DocumentServiceRepository(context, materials).officeConversions
            assertEquals(artifact.id, recreated.artifact(owner, task.id).id)
            assertTrue(runCatching { office.artifact(UUID.randomUUID().toString(), task.id) }.isFailure)
            val witness = File(context.noBackupFilesDir, "document-service/$owner/${task.id}.office-calculation.json")
            val witnessBytes = witness.readBytes()
            witness.appendText("corrupt")
            assertTrue(runCatching { office.artifact(owner, task.id) }.isFailure)
            witness.writeBytes(witnessBytes)
            materials.file(source).appendText("source changed")
            assertTrue(runCatching { office.artifact(owner, task.id) }.isFailure)
            materials.file(source).writeBytes(sourceBytes)
            val cached = xlsxRecalculationTool(materials, owner, docs, true).execute(buildJsonObject {
                put("id", source.id); put("expected_sha256", source.sha256); put("title", task.title)
            })
            assertEquals(artifact.name, cached.filterIsInstance<UIMessagePart.Document>().single().fileName)
            val unavailable = xlsxRecalculationTool(materials, owner, docs, false).execute(buildJsonObject {
                put("id", source.id); put("expected_sha256", source.sha256); put("title", "must not upload")
            })
            assertEquals("FAILED", Json.parseToJsonElement(unavailable.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject["status"]!!.jsonPrimitive.content)
            facade = StudyHostFacade(context, settings, koin.get(), koin.get(), koin.get(), materials, koin.get(), pickFiles = { emptyList() }, documents = docs)
            val bootstrap = facade.getBootstrap(owner, 1L)
            val preview = facade.getArtifactPreview(owner, artifact.id, bootstrap.contextEpoch)
            assertEquals(artifact.sha256, preview.sha256)
            assertTrue(preview.statusDetail!!.contains("真实重算"))
            assertTrue(preview.previewContent!!.contains("sum=22"))
            materials.verifiedFile(owner, artifact.id).copyTo(File(outputDir, "recalculated.xlsx"), overwrite = true)
            witness.copyTo(File(outputDir, "calculation.json"), overwrite = true)
            File(context.noBackupFilesDir, "document-service/$owner/${task.id}.office-manifest.json").copyTo(File(outputDir, "manifest.json"), overwrite = true)
            scenario = ActivityScenario.launch(Intent(context, StudyFlutterActivity::class.java).putExtra("route", "/artifacts"))
            waitFor("real XLSX artifact absent") { text(instrumentation.uiAutomation.rootInActiveWindow).contains(artifact.name) }
            waitFor("real XLSX artifact cannot open") { click(artifact.name) }
            waitFor("real preview lost the host verification receipt") { text(instrumentation.uiAutomation.rootInActiveWindow).contains("真实重算") }
            File(outputDir, "surface.txt").writeText(text(instrumentation.uiAutomation.rootInActiveWindow))
            File(outputDir, "surface.png").outputStream().use {
                assertTrue(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            File(outputDir, "result.json").writeText(buildJsonObject {
                put("status", "PASS"); put("owner", owner); put("taskId", task.id); put("sourceId", source.id); put("artifactId", artifact.id)
                put("sourceSha256", source.sha256); put("artifactSha256", artifact.sha256); put("rendererVersion", task.rendererVersion)
                put("realGateway", true); put("realUnoWorker", true); put("nativeIndependentReadback", true); put("actualArtifactSurface", true)
                put("crossSoftware", "NOT_RUN"); put("containerIsolation", "NOT_RUN"); put("realModel", "NOT_RUN")
            }.toString())
            println("B1B_XLSX_EVIDENCE=${outputDir.name}")
        } finally {
            scenario?.close(); facade?.close()
            acceptedTask?.let { if (!office.task(owner, it.id).downloaded) runCatching { office.cancel(owner, it.id) } }
            docHttp.set(docs, originalDocHttp); officeHttp.set(office, originalOfficeHttp)
            me.rerere.rikkahub.service.restoreMemoryProfileFixture(original, settings)
            File(context.filesDir, "materials/$owner").deleteRecursively()
            File(context.noBackupFilesDir, "document-service/$owner").deleteRecursively()
        }
    }
}
