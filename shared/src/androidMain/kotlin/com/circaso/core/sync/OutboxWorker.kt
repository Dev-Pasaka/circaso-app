package com.circaso.core.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.circaso.core.platform.AndroidBackgroundUploads
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
        const val ResumeName = "circaso-outbox-resume"

        /**
         * The safety net under every upload: a job a little later that sends the outbox again, so an upload cut off
         * by the process dying resumes by itself with a connection (WorkManager outlives the process and reboots).
         * If the upload is still going in this process when it runs, it waits its turn ([OutboxWorker]); if the
         * upload finished, it finds nothing to send.
         */
        fun scheduleResume(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setInitialDelay(ResumeAfterSeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(ResumeName, ExistingWorkPolicy.KEEP, request) }
        }

        private const val ResumeAfterSeconds = 30L
    }
}

/**
 * Put in the coroutine context while the outbox job drains, so an upload started inside it asks this job to go to
 * the foreground (WorkManager's own `dataSync` foreground service) instead of starting [com.circaso.core.platform.UploadService]:
 * the job is what keeps running when Circaso is closed.
 */
class OutboxWorkerRun(val promote: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OutboxWorkerRun>
}

/**
 * Sends the queue in order with each item's own idempotency key, so nothing applies twice. A network failure part
 * way asks WorkManager to try again later (backing off); a refusal leaves that item for Needs attention.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val processor = runCatching { KoinPlatform.getKoin().get<OutboxProcessor>() }.getOrNull() ?: return Result.retry()
        // Circaso's own run is sending right now (this is the safety net under an upload still going): look again
        // later rather than hold a job slot while it finishes.
        if (processor.busy) return Result.retry()
        val result = coroutineScope {
            var promoted = false
            val run = OutboxWorkerRun {
                if (!promoted) {
                    // Refused from the background on Android 12+: then this stays an ordinary job (about ten minutes,
                    // retried after that), which is still enough for a moment's 64 MiB on a connection of a megabit or more.
                    promoted = runCatching { setForeground(foregroundInfo()) }
                        .onFailure { Log.w(AndroidBackgroundUploads.Tag, "outbox job: foreground refused (${it.javaClass.simpleName}); runs as a plain job") }
                        .isSuccess
                    if (promoted) {
                        Log.i(AndroidBackgroundUploads.Tag, "outbox job: foreground (dataSync)")
                        launch { com.circaso.core.platform.UploadsInFlight.active.drop(1).collect { runCatching { setForeground(foregroundInfo()) } } }
                    }
                }
            }
            val drained = runCatching { withContext(run) { processor.drain() } }.getOrNull()
            coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
            drained
        }
        return when (result) {
            is DrainResult.Drained -> Result.success()
            else -> if (runAttemptCount < MaxAttempts) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val n = AndroidBackgroundUploads.sendingNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(AndroidBackgroundUploads.WorkerNotificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(AndroidBackgroundUploads.WorkerNotificationId, n)
    }

    private companion object {
        const val MaxAttempts = 20
    }
}
