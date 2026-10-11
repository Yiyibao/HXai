package me.rerere.rikkahub.feature.material

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class DocumentSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = org.koin.core.context.GlobalContext.get().get<DocumentServiceRepository>()
        val settings = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.datastore.SettingsStore>().settingsFlow.first { !it.init }
        repository.configuredOwners().filter { owner -> settings.learnerProfiles.any { it.id == owner } }.forEach { repository.syncPending(it) }
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("document-service-sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DocumentSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
