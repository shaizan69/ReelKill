package com.reelkill.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelkill.analytics.AnalyticsAggregator
import com.reelkill.analytics.AttentionScoreCalculator
import com.reelkill.common.AppIds
import com.reelkill.data.db.ReelKillDatabase
import java.time.LocalDate
import timber.log.Timber

class MidnightCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val database = ReelKillDatabase.build(applicationContext)
            val aggregator = AnalyticsAggregator(
                usageEventDao = database.usageEventDao(),
                dailySummaryDao = database.dailySummaryDao(),
                attentionScoreCalculator = AttentionScoreCalculator()
            )
            val yesterday = LocalDate.now().minusDays(1).toString()
            AppIds.V1_SUPPORTED_APPS.forEach { appId ->
                aggregator.deriveAndStore(appId, yesterday)
            }
            Timber.d("Derived daily summaries for $yesterday")
            database.close()
            Result.success()
        }.getOrElse { error ->
            Timber.e(error, "MidnightCleanupWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "midnight_cleanup_worker"
    }
}
