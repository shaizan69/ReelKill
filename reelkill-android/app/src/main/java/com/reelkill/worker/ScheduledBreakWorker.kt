package com.reelkill.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelkill.service.ReelKillForegroundService
import timber.log.Timber

class ScheduledBreakWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            ReelKillForegroundService.start(applicationContext)
            Timber.d("Scheduled break check requested foreground enforcement")
            Result.success()
        }.getOrElse { error ->
            Timber.e(error, "ScheduledBreakWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "scheduled_break_worker"
    }
}
