package com.reelkill.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelkill.data.db.ReelKillDatabase
import java.time.Instant
import java.time.temporal.ChronoUnit
import timber.log.Timber

class EventPruningWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val database = ReelKillDatabase.build(applicationContext)
            val cutoff = Instant.now().minus(30, ChronoUnit.DAYS).toString()
            val deleted = database.usageEventDao().deleteOlderThan(cutoff)
            Timber.d("Pruned $deleted old usage events")
            database.close()
            Result.success()
        }.getOrElse { error ->
            Timber.e(error, "EventPruningWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "event_pruning_worker"
    }
}
