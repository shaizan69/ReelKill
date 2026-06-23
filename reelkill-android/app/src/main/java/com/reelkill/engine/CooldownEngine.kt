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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CooldownEngine @Inject constructor(
    private val database: ReelKillDatabase,
    private val appStateDao: AppStateDao,
    private val usageEventDao: UsageEventDao
) {
    suspend fun evaluateAfterReelView(
        appId: String,
        settings: AppSettings,
        sessionId: String,
        clock: Clock = Clock.systemUTC(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): CooldownDecision {
        val now = Instant.now(clock)
        val windowStart = now.minus(Duration.ofMinutes(settings.bingeWindowMinutes.toLong()))

        return database.withTransaction {
            val current = normalizeCooldownWindow(appStateDao.get(appId) ?: AppState.initial(appId), now)
            val hardBlockLive = current.hardBlockActive && current.hardBlockExpires.toInstantOrNull()?.isAfter(now) == true
            val cooldownLive = current.cooldownActive && current.cooldownExpires.toInstantOrNull()?.isAfter(now) == true

            if (hardBlockLive || cooldownLive) {
                if (current != appStateDao.get(appId)) appStateDao.upsert(current)
                CooldownDecision.NotTriggered(current, recentViews = 0)
            } else {
                val recentViews = usageEventDao.countSince(
                    appId = appId,
                    eventType = UsageEvent.TYPE_REEL_VIEWED,
                    fromInclusiveUtc = windowStart.toString()
                )

                if (recentViews >= settings.bingeThresholdReels) {
                    val nextCount = current.cooldownCountToday + 1
                    val durationSeconds = escalatedDurationSeconds(settings.cooldownBaseSeconds, nextCount)
                    val expires = now.plusSeconds(durationSeconds.toLong()).toString()
                    val updated = current.copy(
                        cooldownActive = true,
                        cooldownExpires = expires,
                        cooldownCountToday = nextCount,
                        cooldownCountWindowStart = current.cooldownCountWindowStart ?: now.toString()
                    )
                    appStateDao.upsert(updated)
                    usageEventDao.insert(
                        UsageEvent(
                            eventType = UsageEvent.TYPE_COOLDOWN_TRIGGERED,
                            appId = appId,
                            reelId = null,
                            watchDuration = null,
                            timestamp = now.toString(),
                            sessionId = sessionId,
                            day = now.atZone(zoneId).toLocalDate().toString(),
                            hour = now.atZone(zoneId).hour
                        )
                    )
                    CooldownDecision.Triggered(
                        state = updated,
                        recentViews = recentViews,
                        cooldownNumber = nextCount,
                        durationSeconds = durationSeconds,
                        expiresAt = expires,
                        nextDurationSeconds = escalatedDurationSeconds(settings.cooldownBaseSeconds, nextCount + 1)
                    )
                } else {
                    if (current != appStateDao.get(appId)) appStateDao.upsert(current)
                    CooldownDecision.NotTriggered(current, recentViews)
                }
            }
        }
    }

    suspend fun refreshCooldown(appId: String, clock: Clock = Clock.systemUTC()): CooldownStatus {
        val now = Instant.now(clock)
        return database.withTransaction {
            val current = normalizeCooldownWindow(appStateDao.get(appId) ?: AppState.initial(appId), now)
            val cooldownExpires = current.cooldownExpires.toInstantOrNull()
            val hardBlockLive = current.hardBlockActive && current.hardBlockExpires.toInstantOrNull()?.isAfter(now) == true

            when {
                current.cooldownActive && cooldownExpires != null && cooldownExpires > now -> {
                    if (current != appStateDao.get(appId)) appStateDao.upsert(current)
                    CooldownStatus.Active(current)
                }
                current.cooldownActive -> {
                    val updated = current.copy(cooldownActive = false, cooldownExpires = null)
                    appStateDao.upsert(updated)
                    if (hardBlockLive) CooldownStatus.ExpiredIntoHardBlock(updated) else CooldownStatus.Inactive(updated)
                }
                else -> {
                    if (current != appStateDao.get(appId)) appStateDao.upsert(current)
                    CooldownStatus.Inactive(current)
                }
            }
        }
    }

    fun escalatedDurationSeconds(baseSeconds: Int, cooldownNumber: Int): Int {
        val multiplier = when (cooldownNumber) {
            1 -> 1
            2 -> 2
            3 -> 4
            else -> 6
        }
        return baseSeconds * multiplier
    }

    private fun normalizeCooldownWindow(state: AppState, now: Instant): AppState {
        val windowStart = state.cooldownCountWindowStart.toInstantOrNull()
        return if (windowStart != null && !windowStart.plus(Duration.ofHours(24)).isAfter(now)) {
            state.copy(cooldownCountToday = 0, cooldownCountWindowStart = null)
        } else {
            state
        }
    }

    private fun String?.toInstantOrNull(): Instant? {
        return try {
            if (this == null) null else Instant.parse(this)
        } catch (_: RuntimeException) {
            null
        }
    }
}

sealed interface CooldownDecision {
    val state: AppState
    val recentViews: Int

    data class Triggered(
        override val state: AppState,
        override val recentViews: Int,
        val cooldownNumber: Int,
        val durationSeconds: Int,
        val expiresAt: String,
        val nextDurationSeconds: Int
    ) : CooldownDecision

    data class NotTriggered(
        override val state: AppState,
        override val recentViews: Int
    ) : CooldownDecision
}

sealed interface CooldownStatus {
    data class Active(val state: AppState) : CooldownStatus
    data class Inactive(val state: AppState) : CooldownStatus
    data class ExpiredIntoHardBlock(val state: AppState) : CooldownStatus
}
