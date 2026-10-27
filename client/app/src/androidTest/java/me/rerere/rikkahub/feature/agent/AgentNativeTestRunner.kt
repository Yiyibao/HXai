package me.rerere.rikkahub.feature.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** Separate QA package, real service/Room/provider wiring, no production startup workers. */
class AgentNativeTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName == "org.hxsi.agent.nativeqa") { "Native fixtures require the isolated QA package" }
        return super.newApplication(cl, Application::class.java.name, context)
    }

    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        val notifications = app.getSystemService(NotificationManager::class.java)
        listOf("chat_live_update", "chat_completed").forEach {
            notifications.createNotificationChannel(NotificationChannel(it, "Native QA", NotificationManager.IMPORTANCE_LOW))
        }
        startKoin {
            androidContext(app)
            modules(appModule, dataSourceModule, repositoryModule)
        }
    }
}
