package com.reelkill.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelkill.service.ReelKillForegroundService
import timber.log.Timber

class ServiceHealthWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            if (!ReelKillForegroundService.isRunning) {
                Timber.d("Foreground service not running; restarting")
                ReelKillForegroundService.start(applicationContext)
            }
            Result.success()
        }.getOrElse { error ->
            Timber.e(error, "ServiceHealthWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "service_health_worker"
    }
}
