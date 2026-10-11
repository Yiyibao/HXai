package me.rerere.rikkahub.service

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.writeClipboardText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WarningReviewContextPrivacyTest {
    @Test fun clipboardDeliveryAndUrlFailureDoNotLogPrivateContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = "QA-private-${UUID.randomUUID()}"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ActivityScenario.launch(RouteActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val original = clipboard.primaryClip
                try {
                    activity.writeClipboardText(marker)
                    assertEquals(marker, activity.readClipboardForReview())
                    var urlRejected = false
                    val failingContext = object : ContextWrapper(activity) {
                        override fun startActivity(intent: Intent) {
                            urlRejected = true
                            throw ActivityNotFoundException(marker)
                        }
                        override fun startActivity(intent: Intent, options: android.os.Bundle?) {
                            urlRejected = true
                            throw ActivityNotFoundException(marker)
                        }
                    }
                    failingContext.openUrl("https://qa.invalid/example?token=$marker")
                    assertTrue("The URL failure branch must actually execute", urlRejected)
                } finally {
                    if (original != null) clipboard.setPrimaryClip(original)
                    else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                }
            }
        }
        // Read only this helper's tag, keep it in memory, and never export user/system logs.
        val logs = instrumentation.uiAutomation.executeShellCommand("logcat -d -s ContextUtil:I System.err:W '*:S'")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .bufferedReader().use { it.readText() } }
        assertFalse("The synthetic copied text/URL token must not reach ContextUtil logs", logs.contains(marker))
    }

    private fun Context.readClipboardForReview(): String {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip!!.getItemAt(0).text.toString()
    }
}
