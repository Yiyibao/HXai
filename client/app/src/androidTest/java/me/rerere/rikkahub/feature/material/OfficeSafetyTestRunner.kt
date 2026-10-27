package me.rerere.rikkahub.feature.material

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Keeps local safety tests from starting real memory/document background jobs or restores. */
class OfficeSafetyTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
