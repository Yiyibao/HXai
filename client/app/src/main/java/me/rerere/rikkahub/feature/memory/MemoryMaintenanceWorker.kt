package me.rerere.rikkahub.feature.memory

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** Durable wakeup; leases and per-profile budgets remain in Room. Force-stop resumes on user launch. */
class MemoryMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        withTimeout(240_000) { org.koin.core.context.GlobalContext.get().get<MemoryExtractionRunner>().drain() }
        Result.success()
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { Result.retry() }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("learning-memory-maintenance", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MemoryMaintenanceWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
