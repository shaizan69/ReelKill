package com.reelkill.engine

import com.reelkill.data.db.dao.DailySummaryDao
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

class StreakTracker @Inject constructor(
    private val dailySummaryDao: DailySummaryDao
) {
    fun observeStreak(appId: String): Flow<StreakState> {
        return dailySummaryDao.observeRange(
            appId = appId,
            startDayInclusive = LocalDate.now().minusDays(90).toString(),
            endDayInclusive = LocalDate.now().toString()
        ).map { summaries ->
            computeStreak(summaries)
        }
    }

    private fun computeStreak(summaries: List<com.reelkill.data.db.entity.DailySummary>): StreakState {
        val byDay = summaries.associateBy { it.day }
        val today = LocalDate.now()
        var streak = 0
        var bestStreak = 0
        var brokenReason: String? = null

        for (offset in 0..90) {
            val day = today.minusDays(offset.toLong()).toString()
            val summary = byDay[day]
            if (summary == null) {
                if (offset == 0) {
                    continue
                }
                break
            }
            val intact = !summary.hardBlockHit && summary.cooldownsTriggered == 0
            if (intact) {
                streak++
                if (streak > bestStreak) bestStreak = streak
            } else {
                if (offset == 0) {
                    brokenReason = when {
                        summary.hardBlockHit -> "hard_block"
                        summary.cooldownsTriggered > 0 -> "cooldown"
                        else -> "other"
                    }
                    break
                }
                break
            }
        }

        return StreakState(
            currentStreak = streak.coerceAtMost(90),
            bestStreak = bestStreak,
            brokenReason = brokenReason
        )
    }
}

data class StreakState(
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val brokenReason: String? = null
) {
    val isIntact: Boolean get() = brokenReason == null && currentStreak > 0
}