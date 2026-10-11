package me.rerere.rikkahub.service

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.feature.material.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Synthetic HTTP interception: no request can reach any remote service. */
class DocumentRevocationAuditTest {
    @Test fun memoryToggleColdRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val fixture = UUID.fromString(requireNotNull(args.getString("fixtureId"))).toString()
        val file = File(app.cacheDir, "qa-memory-cold-$fixture")
        val koin = org.koin.core.context.GlobalContext.get()
        val settings = koin.get<me.rerere.rikkahub.data.datastore.SettingsStore>()
        val loaded = kotlinx.coroutines.withTimeout(20_000) {
            settings.settingsFlow.first { !it.init }
        }
        val facade = me.rerere.rikkahub.flutter.StudyHostFacade(app, settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("qa_process_pid", android.os.Process.myPid().toString()) })
        try {
            if (args.getString("phase") == "seed") {
                val profile = loaded.learnerProfiles.single { it.id == loaded.activeProfileId }
                file.writeText(profile.id + "\n" + profile.memoryEnabled)
                assertTrue(facade.toggleAutoMemory(profile.id, false).success)
                assertFalse(settings.settingsFlow.value.learnerProfiles.single { it.id == profile.id }.memoryEnabled)
            } else {
                assertEquals("verify", args.getString("phase"))
                val lines = file.readLines(); val owner = lines[0]; val original = lines[1].toBooleanStrict()
                try {
                    assertFalse(loaded.learnerProfiles.single { it.id == owner }.memoryEnabled)
                    assertFalse(facade.getBootstrap(owner, 1).profiles.single { it.id == owner }.memoryEnabled)
                } finally { settings.setLearnerMemoryEnabled(owner, original); file.delete() }
            }
        } finally { facade.close() }
    }
    @Test fun restartSkipsUnconfirmedUploadAndRevokedToolsBlockApprovedDownload() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "qa-re-review-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        try {
            val owner = UUID.randomUUID().toString(); val files = MaterialFiles(context)
            val source = files.runTask(MaterialFile(UUID.randomUUID().toString(), owner, "synthetic.png", "png")) {
                it.writeBytes(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aH1UAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)); "Synthetic only"
            }
            val methods = mutableListOf<String>(); var lose = true; var expired = false
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                methods += chain.request().method
                if (lose && chain.request().method == "POST") throw IOException("synthetic lost receipt")
                if (chain.request().method == "DELETE") expired = true
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{\"taskId\":\"${UUID.randomUUID()}\",\"state\":\"${if (expired) "EXPIRED" else "QUEUED"}\"}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            fun repository() = DocumentServiceRepository(context, files).also {
                DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(it, client)
            }
            var repo = repository(); repo.configure(owner, "https://qa.example.org", "a".repeat(32), true)
            assertTrue(runCatching { repo.submit(owner, source.id, 1, 1) }.isFailure)
            val intent = repo.tasks(owner).single(); repo = repository(); lose = false
            assertFalse(DocumentDispatchPolicy.allowsRemote(listOf(UIMessage.user("解析文件"), UIMessage.user("不要上传，仅本地处理"))))
            repo.syncPending(owner); assertEquals(listOf("POST"), methods)
            assertEquals(intent.id, repo.tasks(owner).single().id)
            // Explicit approval restores this intent; all subsequent calls use synthetic responses.
            repo.resumeSubmission(owner, intent.id)
            val revoked = documentServiceTools(repo, owner, false, allowRemote = false)
            assertFalse(revoked.any { it.name == "parse_material_enhanced" })
            // Defect assertions: these legitimate operations are currently also removed.
            assertFalse(revoked.any { it.name == "get_material_parse_status" })
            assertFalse(revoked.any { it.name == "cancel_material_parse" })
            val before = methods.toList()
            val result = runCatching { revoked.single { it.name == "read_material_parse_blocks" }.execute(buildJsonObject {
                put("task_id", intent.id); put("offset", "0"); put("count", "1")
            }) }
            assertTrue(result.isFailure); assertTrue(result.exceptionOrNull()!!.message!!.contains("禁止远端"))
            assertEquals(before, methods)
            // Background status and explicit cleanup remain available at repository level.
            repo.syncPending(owner); repo.cleanupRemote(owner, intent.id)
            assertEquals(listOf("POST", "POST", "GET", "DELETE", "GET"), methods)
        } finally { root.deleteRecursively() }
    }
}
