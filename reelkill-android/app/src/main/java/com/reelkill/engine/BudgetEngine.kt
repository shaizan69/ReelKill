package com.reelkill.engine

import androidx.room.withTransaction
import com.reelkill.data.db.ReelKillDatabase
import com.reelkill.data.db.dao.AppStateDao
import com.reelkill.data.db.dao.UsageEventDao
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.db.entity.AppState
import com.reelkill.data.db.entity.UsageEvent
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetEngine @Inject constructor(
    private val database: ReelKillDatabase,
    private val appStateDao: AppStateDao,
    private val usageEventDao: UsageEventDao
) {
    suspend fun recordReelView(
        appId: String,
        settings: AppSettings,
        reelId: String?,
        watchDurationSeconds: Long,
        sessionId: String?,
        clock: Clock = Clock.systemUTC(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): BudgetDecision {
        require(settings.dailyLimit <= AppSettings.DAILY_LIMIT_MAX) {
            "dailyLimit cannot exceed ${AppSettings.DAILY_LIMIT_MAX}"
        }

        val now = Instant.now(clock)
        return database.withTransaction {
            val current = normalizeWindow(appStateDao.get(appId) ?: AppState.initial(appId), now)
            val activeSessionId = sessionId ?: current.currentSessionId ?: UUID.randomUUID().toString()
            val anchor = current.blockAnchorTimestamp ?: now.toString()
            val incrementedCount = current.reelsWatchedToday + 1
            val baseState = current.copy(
                reelsWatchedToday = incrementedCount,
                blockAnchorTimestamp = anchor,
                currentSessionId = activeSessionId,
                lastReelViewAt = now.toString()
            )

            usageEventDao.insert(
                usageEvent(
                    eventType = UsageEvent.TYPE_REEL_VIEWED,
                    appId = appId,
                    reelId = reelId,
                    watchDurationSeconds = watchDurationSeconds,
                    timestamp = now,
                    sessionId = activeSessionId,
                    zoneId = zoneId
                )
            )

            val shouldTriggerHardBlock = incrementedCount >= settings.dailyLimit && !baseState.isHardBlockLive(now)
            val finalState = if (shouldTriggerHardBlock) {
                val hardBlockExpires = now.plus(Duration.ofHours(24)).toString()
                usageEventDao.insert(
                    usageEvent(
                        eventType = UsageEvent.TYPE_HARD_BLOCK_TRIGGERED,
                        appId = appId,
                        reelId = null,
                        watchDurationSeconds = null,
                        timestamp = now,
                        sessionId = activeSessionId,
                        zoneId = zoneId
                    )
                )
                baseState.copy(
                    hardBlockActive = true,
                    hardBlockExpires = hardBlockExpires,
                    cooldownActive = false,
                    cooldownExpires = null
                )
            } else {
                baseState
            }

            appStateDao.upsert(finalState)
            BudgetDecision(
                state = finalState,
                hardBlockTriggered = shouldTriggerHardBlock,
                hardBlockExpires = finalState.hardBlockExpires,
                reelsWatched = finalState.reelsWatchedToday,
                dailyLimit = settings.dailyLimit
            )
        }
    }

    suspend fun refreshHardBlock(appId: String, clock: Clock = Clock.systemUTC()): HardBlockStatus {
        val now = Instant.now(clock)
        return database.withTransaction {
            val current = appStateDao.get(appId) ?: AppState.initial(appId)
            val normalized = normalizeWindow(current, now)
            if (normalized != current) appStateDao.upsert(normalized)

            if (normalized.isHardBlockLive(now)) {
                HardBlockStatus.Active(normalized)
            } else {
                HardBlockStatus.Inactive(normalized)
            }
        }
    }

    private fun normalizeWindow(state: AppState, now: Instant): AppState {
        val hardBlockExpires = state.hardBlockExpires.toInstantOrNull()
        if (state.hardBlockActive && hardBlockExpires != null && hardBlockExpires > now) {
            return state
        }

        val anchor = state.blockAnchorTimestamp.toInstantOrNull()
        val budgetWindowExpired = anchor != null && !anchor.plus(Duration.ofHours(24)).isAfter(now)
        val hardBlockExpired = state.hardBlockActive && (hardBlockExpires == null || !hardBlockExpires.isAfter(now))

        return if (budgetWindowExpired || hardBlockExpired) {
            state.copy(
                reelsWatchedToday = 0,
                hardBlockActive = false,
                hardBlockExpires = null,
                frictionShownThisSession = false,
                blockAnchorTimestamp = null
            )
        } else {
            state
        }
    }

    private fun AppState.isHardBlockLive(now: Instant): Boolean {
        val expires = hardBlockExpires.toInstantOrNull()
        return hardBlockActive && expires != null && expires > now
    }

    private fun usageEvent(
        eventType: String,
        appId: String,
        reelId: String?,
        watchDurationSeconds: Long?,
        timestamp: Instant,
        sessionId: String,
        zoneId: ZoneId
    ): UsageEvent {
        return UsageEvent(
            eventType = eventType,
            appId = appId,
            reelId = reelId,
            watchDuration = watchDurationSeconds,
            timestamp = timestamp.toString(),
            sessionId = sessionId,
            day = timestamp.atZone(zoneId).toLocalDate().toString(),
            hour = timestamp.atZone(zoneId).hour
        )
    }

    private fun String?.toInstantOrNull(): Instant? {
        return try {
            if (this == null) null else Instant.parse(this)
        } catch (_: RuntimeException) {
            null
        }
    }
}

data class BudgetDecision(
    val state: AppState,
    val hardBlockTriggered: Boolean,
    val hardBlockExpires: String?,
    val reelsWatched: Int,
    val dailyLimit: Int
)

sealed interface HardBlockStatus {
    data class Active(val state: AppState) : HardBlockStatus
    data class Inactive(val state: AppState) : HardBlockStatus
}
