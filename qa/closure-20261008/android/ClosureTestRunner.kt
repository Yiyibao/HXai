package me.rerere.rikkahub.service

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.util.UUID
import me.rerere.rikkahub.feature.diagnostics.Diagnostics

/** Isolate lifecycle and production-operation events before application initialization. */
class ClosureTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        val directory = File(app.cacheDir, "qa-closure-diagnostics-${UUID.randomUUID()}")
        check(directory.mkdirs())
        Diagnostics.instrumentationDirectory = directory
        super.callApplicationOnCreate(app)
    }
}
