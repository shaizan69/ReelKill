package com.reelkill.engine

import androidx.room.withTransaction
import com.reelkill.data.db.ReelKillDatabase
import com.reelkill.data.db.dao.AppStateDao
import com.reelkill.data.db.dao.UsageEventDao
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.db.entity.AppState
import com.reelkill.data.db.entity.UsageEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

@Singleton
class FrictionEngine @Inject constructor(
    private val database: ReelKillDatabase,
    private val appStateDao: AppStateDao,
    private val usageEventDao: UsageEventDao
) {
    suspend fun evaluate(
        appId: String,
        settings: AppSettings,
        sessionId: String,
        clock: Clock = Clock.systemUTC(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): FrictionDecision {
        val now = Instant.now(clock)
        return database.withTransaction {
            val state = appStateDao.get(appId) ?: AppState.initial(appId)
            val threshold = ceil(settings.dailyLimit * settings.frictionThresholdPct).toInt().coerceAtLeast(1)
            val hardBlockLive = state.hardBlockActive && state.hardBlockExpires?.let { Instant.parse(it) }?.isAfter(now) == true
            val shouldShow = !hardBlockLive &&
                !state.frictionShownThisSession &&
                state.reelsWatchedToday >= threshold

            if (shouldShow) {
                val updated = state.copy(frictionShownThisSession = true)
                appStateDao.upsert(updated)
                usageEventDao.insert(
                    frictionEvent(UsageEvent.TYPE_FRICTION_SHOWN, appId, sessionId, now, zoneId)
                )
                FrictionDecision.Show(updated, threshold)
            } else {
                FrictionDecision.DoNotShow(state, threshold)
            }
        }
    }

    suspend fun recordDismissed(
        appId: String,
        sessionId: String,
        clock: Clock = Clock.systemUTC(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ) {
        val now = Instant.now(clock)
        usageEventDao.insert(frictionEvent(UsageEvent.TYPE_FRICTION_DISMISSED, appId, sessionId, now, zoneId))
    }

    private fun frictionEvent(
        eventType: String,
        appId: String,
        sessionId: String,
        now: Instant,
        zoneId: ZoneId
    ): UsageEvent {
        return UsageEvent(
            eventType = eventType,
            appId = appId,
            reelId = null,
            watchDuration = null,
            timestamp = now.toString(),
            sessionId = sessionId,
            day = now.atZone(zoneId).toLocalDate().toString(),
            hour = now.atZone(zoneId).hour
        )
    }
}

sealed interface FrictionDecision {
    val state: AppState
    val thresholdReels: Int

    data class Show(
        override val state: AppState,
        override val thresholdReels: Int
    ) : FrictionDecision

    data class DoNotShow(
        override val state: AppState,
        override val thresholdReels: Int
    ) : FrictionDecision
}
