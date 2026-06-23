package com.reelkill.engine

import com.reelkill.data.db.entity.AppSettings
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class AntiScrollEngine @Inject constructor() {
    private val swipeTimes = ArrayDeque<Instant>()
    private var suppressedUntil: Instant? = null

    fun recordSwipeUp(settings: AppSettings, clock: Clock = Clock.systemUTC()): AntiScrollDecision {
        if (!settings.antiScrollEnabled) return AntiScrollDecision.NoAction

        val now = Instant.now(clock)
        val suppressed = suppressedUntil
        if (suppressed != null && suppressed > now) return AntiScrollDecision.NoAction

        swipeTimes.addLast(now)
        val cutoff = now.minus(SWIPE_WINDOW)
        while (swipeTimes.isNotEmpty() && swipeTimes.first() < cutoff) {
            swipeTimes.removeFirst()
        }

        val threshold = thresholdFor(settings.antiScrollSensitivity)
        return if (swipeTimes.size >= threshold) {
            swipeTimes.clear()
            AntiScrollDecision.ShowPopup
        } else {
            AntiScrollDecision.NoAction
        }
    }

    fun suppressForOneMinute(clock: Clock = Clock.systemUTC()) {
        suppressedUntil = Instant.now(clock).plus(Duration.ofMinutes(1))
    }

    private fun thresholdFor(sensitivity: String): Int {
        return when (sensitivity) {
            AppSettings.SENSITIVITY_STRICT -> 5
            AppSettings.SENSITIVITY_CHILL -> 15
            else -> 10
        }
    }

    private companion object {
        val SWIPE_WINDOW: Duration = Duration.ofSeconds(20)
    }
}

sealed interface AntiScrollDecision {
    data object ShowPopup : AntiScrollDecision
    data object NoAction : AntiScrollDecision
}
