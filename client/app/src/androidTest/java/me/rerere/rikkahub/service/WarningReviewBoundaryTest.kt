package me.rerere.rikkahub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarningReviewBoundaryTest {
    @Test fun sharedWorkspaceProviderIsNotExternallyResolvable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull("Shared workspace has no learner authorization and must not be published to SAF",
            context.packageManager.resolveContentProvider("${context.packageName}.documents", 0))
    }
}
