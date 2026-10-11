package me.rerere.rikkahub.service

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import me.rerere.rikkahub.feature.memory.*
import me.rerere.rikkahub.data.db.AppDatabase

@RunWith(AndroidJUnit4::class)
class FrontendDeliveryRoutesTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()

    private fun navigate(screen: Screen) {
        compose.waitUntil(20_000) {
            RouteActivity::class.java.getDeclaredField("navStack").apply { isAccessible = true }.get(compose.activity) != null
        }
        compose.runOnUiThread {
            @Suppress("UNCHECKED_CAST")
            val stack = RouteActivity::class.java.getDeclaredField("navStack").apply { isAccessible = true }
                .get(compose.activity) as MutableList<NavKey>
            stack.add(screen)
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun providerRoutesUseRealModelRequestAndProtectUnsavedDraft() {
        val store = GlobalContext.get().get<SettingsStore>()
        val original = runBlocking { store.settingsFlow.first { !it.init } }
        val server = ServerSocket(0)
        val request = AtomicReference<String>()
        val worker = Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                request.set(input.readLine())
                while (!input.readLine().isNullOrEmpty()) { }
                val body = "{\"data\":[{\"id\":\"qa-math\"}]}"
                val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                socket.getOutputStream().write(response.toByteArray())
            }
        }.apply { isDaemon = true; start() }
        val provider = ProviderSetting.OpenAI(name = "QA路由提供商", baseUrl = "http://127.0.0.1:${server.localPort}/v1", enabled = false)
        try {
            runBlocking { store.update { it.copy(providers = listOf(provider) + it.providers) } }
            navigate(Screen.SettingProvider)
            compose.onNodeWithText("服务提供商管理").assertIsDisplayed()
            capture("frontend-P12-runtime.png")
            compose.onAllNodesWithText("编辑配置").onFirst().performClick()
            compose.onNodeWithText("编辑提供商配置").assertIsDisplayed()
            compose.onNodeWithText("检查连接").performScrollTo().performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("模型列表请求成功", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("GET /v1/models HTTP/1.1", request.get())
            capture("frontend-P13-real-request.png")
            val nameLabel = compose.activity.getString(R.string.setting_provider_page_name)
            compose.onNodeWithText(nameLabel).performScrollTo().performTextReplacement("QA未保存草稿")
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("有未保存的修改").assertIsDisplayed()
            compose.onNodeWithText("继续编辑").performClick()
            compose.onNodeWithText("QA未保存草稿").assertExists()
            compose.onNodeWithText("保存设置").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("服务提供商管理").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("QA未保存草稿", store.settingsFlow.value.providers.first { it.id == provider.id }.name)
            compose.onAllNodesWithText("管理模型 (0)").onFirst().performClick()
            compose.onNodeWithText("QA未保存草稿 · 模型管理").assertIsDisplayed()
            capture("frontend-P14-runtime.png")
        } finally {
            server.close()
            worker.join(1000)
            runBlocking { store.update(original) }
        }
    }

    @Test fun memoryEditorKeepsDraftAndRejectsConcurrentRevision() {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val store = koin.get<LearningMemoryStore>()
        val original = runBlocking { settings.settingsFlow.first { !it.init } }
        val profile = runBlocking { settings.createLearnerProfile("QA前端版本冲突") }
        val source = MemorySource("qa-source", "qa-chat", "qa-node", "qa-message",
            store.fingerprint(profile, "qa-source"), 1)
        val memory = LearningMemory(type = MemoryType.PREFERENCE, topicKey = "教学", factKey = "顺序",
            content = "原始事实", evidenceType = EvidenceType.SELF_REPORTED, sourceIds = listOf(source.id),
            status = "ACTIVE", createdAt = 1, updatedAt = 1)
        try {
            // Leave the chat before switching profiles; its access guard legitimately redirects old chats.
            navigate(Screen.SettingProvider)
            runBlocking {
                withTimeout(20_000) { settings.settingsFlow.first { it.learnerProfiles.any { p -> p.id == profile } } }
                store.change(profile) { it.copy(sources = listOf(source), items = listOf(memory)) to Unit }
                settings.selectLearnerProfile(profile)
            }
            navigate(Screen.StudyMemoryDetail(memory.id, "qa-chat"))
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("原始事实").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("更正记忆").performScrollTo().performClick()
            compose.onNodeWithText("更正后的事实描述").performTextReplacement("保留用户草稿")
            runBlocking { store.edit(profile, memory.id, memory.revision, "后台已更新事实") }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("后台已更新事实").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("保留用户草稿").assertExists()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("保留用户草稿").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("保存更正").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("草稿已保留", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("保留用户草稿").assertExists()
            capture("frontend-P06-revision-conflict.png")
            assertEquals("后台已更新事实", runBlocking { store.read(profile).items.single().content })
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("更正记忆").performScrollTo().performClick()
            compose.onNodeWithText("更正后的事实描述").performTextReplacement("重新读取版本后保存")
            compose.onNodeWithText("保存更正").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("更正记忆内容").fetchSemanticsNodes().isEmpty()
            }
            assertEquals("重新读取版本后保存", runBlocking { store.read(profile).items.single().content })
        } finally {
            runBlocking {
                restoreMemoryProfileFixture(original, settings)
                withContext(Dispatchers.IO) {
                    koin.get<AppDatabase>().openHelper.writableDatabase.execSQL(
                        "DELETE FROM learning_memory_state WHERE profile_id = ?", arrayOf(profile))
                }
            }
        }
    }
}
