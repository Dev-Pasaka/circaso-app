package com.circaso.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit

/** One WorkManager job, run only with a connection, that sends the outbox (see [BackgroundSync]). */
class AndroidBackgroundSync(private val context: Context) : BackgroundSync {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: one job is enough; it sends everything queued, in order.
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(UniqueName, ExistingWorkPolicy.KEEP, request) }
    }

    companion object {
        const val UniqueName = "circaso-outbox"
    }
}

/**
 * Sends the queue in order with each item's own idempotency key, so nothing applies twice. A network failure part
 * way asks WorkManager to try again later (backing off); a refusal leaves that item for Needs attention.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val processor = runCatching { KoinPlatform.getKoin().get<OutboxProcessor>() }.getOrNull() ?: return Result.retry()
        return when (runCatching { processor.drain() }.getOrNull()) {
            is DrainResult.Drained -> Result.success()
            else -> if (runAttemptCount < MaxAttempts) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MaxAttempts = 20
    }
}
