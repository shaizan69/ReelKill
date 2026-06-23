package com.reelkill.engine

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
class PatternDetector @Inject constructor(
    private val usageEventDao: UsageEventDao
) {
    suspend fun detectAfterEvent(
        appId: String,
        settings: AppSettings,
        state: AppState,
        clock: Clock = Clock.systemUTC(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<DetectedPattern> {
        val now = Instant.now(clock)
        val day = now.atZone(zoneId).toLocalDate().toString()
        val patterns = mutableListOf<DetectedPattern>()

        val rapidBingeCount = usageEventDao.countSince(
            appId = appId,
            eventType = UsageEvent.TYPE_REEL_VIEWED,
            fromInclusiveUtc = now.minus(Duration.ofMinutes(15)).toString()
        )
        if (rapidBingeCount >= 20) {
            patterns += DetectedPattern(PatternType.RAPID_BINGE, PatternSeverity.WARNING)
        }

        val sessionsToday = usageEventDao.countForDay(appId, UsageEvent.TYPE_SESSION_START, day)
        if (sessionsToday >= 10) {
            patterns += DetectedPattern(PatternType.FREQUENT_OPENS, PatternSeverity.INFO)
        }

        if (state.reelsWatchedToday >= settings.dailyLimit * 0.9f) {
            patterns += DetectedPattern(PatternType.BUDGET_CRITICAL, PatternSeverity.CRITICAL)
        }

        if (state.cooldownCountToday >= 3) {
            patterns += DetectedPattern(PatternType.COOLDOWN_CLUSTER, PatternSeverity.WARNING)
        }

        val localHour = now.atZone(zoneId).hour
        if (localHour >= 23 || localHour < 5) {
            patterns += DetectedPattern(PatternType.LATE_NIGHT, PatternSeverity.INFO)
        }

        return patterns.distinctBy { it.type }
    }
}

data class DetectedPattern(
    val type: PatternType,
    val severity: PatternSeverity
)

enum class PatternType(val eventKey: String) {
    RAPID_BINGE("rapid_binge"),
    FREQUENT_OPENS("frequent_opens"),
    BUDGET_CRITICAL("budget_critical"),
    COOLDOWN_CLUSTER("cooldown_cluster"),
    LATE_NIGHT("late_night")
}

enum class PatternSeverity {
    INFO,
    WARNING,
    CRITICAL
}
