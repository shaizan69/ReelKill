package com.reelkill.analytics

import com.reelkill.data.db.dao.DailySummaryDao
import com.reelkill.data.db.dao.UsageEventDao
import com.reelkill.data.db.entity.DailySummary
import com.reelkill.data.db.entity.UsageEvent
import javax.inject.Inject

class AnalyticsAggregator @Inject constructor(
    private val usageEventDao: UsageEventDao,
    private val dailySummaryDao: DailySummaryDao,
    private val attentionScoreCalculator: AttentionScoreCalculator = AttentionScoreCalculator()
) {
    suspend fun deriveAndStore(appId: String, day: String): DailySummary {
        val events = usageEventDao.getForDay(appId, day)
        val reelEvents = events.filter { it.eventType == UsageEvent.TYPE_REEL_VIEWED }
        val cooldowns = events.count { it.eventType == UsageEvent.TYPE_COOLDOWN_TRIGGERED }
        val hardBlockHit = events.any { it.eventType == UsageEvent.TYPE_HARD_BLOCK_TRIGGERED }
        val frictionShown = events.count { it.eventType == UsageEvent.TYPE_FRICTION_SHOWN }
        val frictionDismissed = events.count { it.eventType == UsageEvent.TYPE_FRICTION_DISMISSED }
        val lateNightReels = reelEvents.count { it.hour >= 23 || it.hour < 5 }
        val sessions = events.count { it.eventType == UsageEvent.TYPE_SESSION_START }
        val timeSpentSeconds = reelEvents.sumOf { it.watchDuration ?: 0L }

        val summary = DailySummary(
            day = day,
            appId = appId,
            reelsWatched = reelEvents.size,
            timeSpentSeconds = timeSpentSeconds,
            sessions = sessions,
            cooldownsTriggered = cooldowns,
            hardBlockHit = hardBlockHit,
            frictionShown = frictionShown,
            frictionDismissed = frictionDismissed,
            attentionScore = attentionScoreCalculator.calculate(
                hardBlockHit = hardBlockHit,
                cooldownsTriggered = cooldowns,
                lateNightReels = lateNightReels,
                weekOverWeekImproved = true,
                frictionDismissedImproved = frictionDismissed <= frictionShown
            )
        )
        dailySummaryDao.upsert(summary)
        return summary
    }
}
