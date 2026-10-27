package me.rerere.rikkahub.feature.agent

import me.rerere.rikkahub.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class SyntheticMcpIsolationTest {
    @Test fun ordinaryApplicationCannotInstallOrExposeTheSyntheticExtension() {
        assertEquals(BuildConfig.APPLICATION_ID=="org.hxsi.agent.nativeqa",BuildConfig.SYNTHETIC_MCP)
        assertNull(SyntheticMcpRuntime.toolFor("owner"))
        if(!BuildConfig.SYNTHETIC_MCP) {
            assertTrue(runCatching { SyntheticMcpRuntime.installForQa("owner",18888,"fixture","a".repeat(64)) }.isFailure)
            assertNull(SyntheticMcpRuntime.toolFor("owner"))
        }
    }
}
