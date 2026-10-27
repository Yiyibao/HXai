package me.rerere.rikkahub.feature.agent

import android.app.Activity
import android.os.Bundle

/** Exported only for the OS OAuth redirect. No UI permissions, network, tokens or task execution. */
class ExtensionOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { intent?.data?.let { uri -> ExtensionOAuth(this).use { it.callback(uri) } } }
        catch (_: Exception) { /* Reject invalid callbacks without logging code/state or issuer errors. */ }
        finish()
    }
}
