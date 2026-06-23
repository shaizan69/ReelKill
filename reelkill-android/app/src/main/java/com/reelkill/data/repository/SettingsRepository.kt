package com.reelkill.data.repository

import androidx.room.withTransaction
import com.reelkill.data.db.ReelKillDatabase
import com.reelkill.data.db.dao.AppSettingsDao
import com.reelkill.data.db.dao.PendingSettingDao
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.db.entity.PendingSetting
import com.reelkill.engine.SettingsGuard
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONException

@Singleton
class SettingsRepository @Inject constructor(
    private val database: ReelKillDatabase,
    private val appSettingsDao: AppSettingsDao,
    private val pendingSettingDao: PendingSettingDao
) {
    fun observeSettings(appId: String): Flow<AppSettings> {
        return appSettingsDao.observe(appId).map { it ?: AppSettings.defaultFor(appId) }
    }

    suspend fun getOrCreateSettings(appId: String): AppSettings {
        return database.withTransaction {
            val existing = appSettingsDao.get(appId)
            if (existing != null) {
                existing
            } else {
                val defaults = AppSettings.defaultFor(appId)
                appSettingsDao.upsert(defaults)
                defaults
            }
        }
    }

    suspend fun writeSettings(
        proposed: AppSettings,
        clock: Clock = Clock.systemUTC()
    ): SettingsWriteResult {
        require(proposed.dailyLimit <= AppSettings.DAILY_LIMIT_MAX) {
            "dailyLimit cannot exceed ${AppSettings.DAILY_LIMIT_MAX}"
        }

        return database.withTransaction {
            val current = appSettingsDao.get(proposed.appId) ?: AppSettings.defaultFor(proposed.appId)
            if (SettingsGuard.isLooserThan(current, proposed)) {
                val pending = PendingSetting(
                    appId = proposed.appId,
                    changesJson = AppSettingsJson.toJson(proposed),
                    appliesAt = Instant.now(clock).plus(Duration.ofHours(24)).toString()
                )
                val id = pendingSettingDao.insert(pending)
                SettingsWriteResult.Queued(pending.copy(id = id))
            } else {
                appSettingsDao.upsert(proposed)
                SettingsWriteResult.Applied(proposed.appId)
            }
        }
    }

    suspend fun applyDuePendingSettings(clock: Clock = Clock.systemUTC()): Int {
        val nowUtc = Instant.now(clock).toString()
        return database.withTransaction {
            pendingSettingDao.getDue(nowUtc).sumOf { pending ->
                val contribution: Int = try {
                    val proposed = AppSettingsJson.fromJson(pending.changesJson)
                    appSettingsDao.upsert(proposed)
                    pendingSettingDao.deleteById(pending.id)
                    1
                } catch (_: JSONException) {
                    pendingSettingDao.deleteById(pending.id)
                    0
                } catch (_: IllegalArgumentException) {
                    pendingSettingDao.deleteById(pending.id)
                    0
                }
                contribution
            }
        }
    }
}
