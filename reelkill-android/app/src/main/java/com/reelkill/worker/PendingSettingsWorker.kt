package com.reelkill.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelkill.data.db.ReelKillDatabase
import com.reelkill.data.repository.SettingsRepository
import timber.log.Timber

class PendingSettingsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val database = ReelKillDatabase.build(applicationContext)
            val repository = SettingsRepository(
                database = database,
                appSettingsDao = database.appSettingsDao(),
                pendingSettingDao = database.pendingSettingDao()
            )
            val applied = repository.applyDuePendingSettings()
            Timber.d("Applied $applied pending settings")
            database.close()
            Result.success()
        }.getOrElse { error ->
            Timber.e(error, "PendingSettingsWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "pending_settings_worker"
    }
}
