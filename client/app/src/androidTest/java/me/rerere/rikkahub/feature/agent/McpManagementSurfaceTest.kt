package me.rerere.rikkahub.feature.agent

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class McpManagementSurfaceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun trustedSurfacePersistsConsentAndIsolatesProfileChanges(): Unit=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val settings=GlobalContext.get().get<SettingsStore>();val original=settings.settingsFlow.first { !it.init }
        val alice=settings.createLearnerProfile("QA MCP Alice");val bob=settings.createLearnerProfile("QA MCP Bob")
        try {
            settings.selectLearnerProfile(alice);settings.settingsFlow.first { it.activeProfileId==alice }
            ActivityScenario.launch<ComponentActivity>(Intent().setClassName(context,"me.rerere.rikkahub.feature.agent.McpFixtureManagementActivity")).use { scenario ->
                compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp_fixture_port").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("mcp_fixture_port").performTextReplacement("18889")
                compose.onNodeWithTag("mcp_fixture_hash").performTextReplacement(AgentTaskStore.hash("synthetic"))
                compose.onNodeWithTag("mcp_fixture_install").performScrollTo().performClick()
                compose.onNodeWithTag("mcp_fixture_status").assertTextContains("DISABLED",substring=true)
                assertNull(SyntheticMcpRuntime.toolFor(alice))
                compose.onNodeWithTag("mcp_fixture_enable").performScrollTo().performClick()
                compose.onNodeWithTag("mcp_fixture_status").assertTextContains("ENABLED",substring=true)
                val enabled=McpInstallationStore(context).use { it.get(alice)!! };assertNotNull(SyntheticMcpRuntime.toolFor(alice))
                compose.onNodeWithTag("mcp_fixture_status").performScrollTo()
                val folder=context.cacheDir.resolve("mcp-management-surface").apply { mkdirs() }
                inst.uiAutomation.takeScreenshot().let { bitmap ->
                    folder.resolve("enabled.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                }
                scenario.recreate()
                compose.waitUntil(10000) { McpInstallationStore(context).use { it.get(alice)==enabled } && compose.onAllNodesWithTag("mcp_fixture_status").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("mcp_fixture_revoke").performScrollTo().performClick()
                compose.onNodeWithTag("mcp_fixture_enable").assertIsNotEnabled()
                assertNull(SyntheticMcpRuntime.toolFor(alice))
                val revoked=McpInstallationStore(context).use { it.get(alice)!! };assertEquals("REVOKED",revoked.state)
                settings.selectLearnerProfile(bob);settings.settingsFlow.first { it.activeProfileId==bob }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("mcp_fixture_status").fetchSemanticsNodes().any { it.config.toString().contains("未安装") } }
                compose.onNodeWithTag("mcp_fixture_enable").assertIsNotEnabled()
                McpInstallationStore(context).use { assertNull(it.get(bob));assertEquals(revoked,it.get(alice)) }
                inst.uiAutomation.takeScreenshot().let { bitmap ->
                    folder.resolve("profile-isolation.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                }
            }
        } finally {
            SyntheticMcpRuntime.uninstallForQa(alice);SyntheticMcpRuntime.uninstallForQa(bob)
            settings.selectLearnerProfile(original.activeProfileId)
            me.rerere.rikkahub.service.restoreMemoryProfileFixture(original,settings)
        }
    }
}
