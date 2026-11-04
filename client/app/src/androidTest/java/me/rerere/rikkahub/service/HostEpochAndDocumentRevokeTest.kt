package me.rerere.rikkahub.service

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.feature.material.DocumentServiceRepository
import me.rerere.rikkahub.feature.material.DocumentTask
import me.rerere.rikkahub.feature.material.MaterialFiles
import me.rerere.rikkahub.flutter.StudyHostFacade
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class HostEpochAndDocumentRevokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun staleEpochRejectsStopAndApproveBeforeSideEffect(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val repository = koin.get<ConversationRepository>()
        val service = koin.get<me.rerere.rikkahub.service.ChatService>()
        val owner = settings.settingsFlow.first { !it.init }.activeProfileId
        val host = StudyHostFacade(context, settings, repository, service, koin.get(), koin.get(), koin.get(), pickFiles = { emptyList() })
        val conversation = Conversation(assistantId = settings.settingsFlow.value.assistantId, profileId = owner, title = "QA stale epoch", messageNodes = emptyList())
        try {
            repository.insertConversation(conversation)
            val epoch = host.getBootstrap(owner, 1L).contextEpoch
            val id = conversation.id.toString()
            val staleStop = runCatching { host.stopGeneration(owner, id, "stale-stop", epoch - 1) }
            assertTrue(staleStop.exceptionOrNull()?.message?.contains("请求已过期") == true)
            val staleApprove = runCatching { host.approveToolCall(owner, id, "missing-tool", false, "stale-approve", epoch - 1) }
            assertTrue(staleApprove.exceptionOrNull()?.message?.contains("请求已过期") == true)
            assertTrue(service.getGenerationJobStateFlow(conversation.id).firstOrNull()?.isActive != true)
            assertTrue(host.stopGeneration(owner, id, "current-stop", epoch).success)
            assertTrue(host.approveToolCall(owner, id, "missing-tool", false, "current-approve", epoch).success)
            assertTrue(service.getGenerationJobStateFlow(conversation.id).firstOrNull()?.isActive != true)
        } finally {
            repository.getConversationById(conversation.id)?.let { repository.deleteConversation(it) }
            host.close()
        }
    }

    @Test fun documentRevokeKeepsCredentialUntilServerConfirms() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "qa-revoke-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        try {
            val owner = UUID.randomUUID().toString()
            val token = "b".repeat(32)
            val files = MaterialFiles(context)
            val repository = DocumentServiceRepository(context, files)
            repository.configure(owner, "https://qa.example.org", token, true)
            val before = repository.config(owner)
            assertTrue(before.enabled)
            assertTrue(before.credentialReference.isNotBlank())
            val taskId = UUID.randomUUID().toString()
            File(context.noBackupFilesDir, "document-service/$owner/$taskId.task.json").writeText(Json.encodeToString(DocumentTask(
                id = taskId, sourceId = UUID.randomUUID().toString(), sourceSha256 = "a".repeat(64),
                start = 1, end = 1, configurationKey = "synthetic", state = "QUEUED")))
            var confirm = false
            val paths = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                paths += chain.request().url.encodedPath
                val body = if (confirm) "{\"revoked\":true}" else "{\"detail\":\"missing\"}"
                val code = if (confirm) 200 else 404
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            DocumentServiceRepository::class.java.getDeclaredField("http").apply { isAccessible = true }.set(repository, client)
            assertTrue(runCatching { repository.revokeIdentity(owner) }.isFailure)
            assertEquals(before, repository.config(owner))
            assertEquals("QUEUED", repository.tasks(owner).single().state)
            confirm = true
            repository.revokeIdentity(owner)
            val after = repository.config(owner)
            assertFalse(after.enabled)
            assertEquals("", after.credentialReference)
            assertEquals("CANCELLED", repository.tasks(owner).single().state)
            assertEquals("authorization_revoked", repository.tasks(owner).single().errorCode)
            assertEquals(listOf("/api/v1/identity/revoke", "/api/v1/identity/revoke"), paths)
        } finally { root.deleteRecursively() }
    }
}
