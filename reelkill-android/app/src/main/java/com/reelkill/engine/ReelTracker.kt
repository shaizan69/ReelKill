package com.reelkill.engine

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class ReelTracker @Inject constructor() {
    private var focusedReelId: String? = null
    private var focusedAt: Instant? = null
    private var countedReelId: String? = null

    fun onReelCandidateVisible(
        reelId: String?,
        clock: Clock = Clock.systemUTC()
    ): ConfirmedReelView? {
        val now = Instant.now(clock)
        val normalizedId = reelId ?: UNKNOWN_REEL_ID

        if (focusedReelId != normalizedId) {
            focusedReelId = normalizedId
            focusedAt = now
            countedReelId = null
            return null
        }

        val startedAt = focusedAt ?: return null
        val visibleFor = Duration.between(startedAt, now).seconds
        if (visibleFor >= MIN_VIEW_SECONDS && countedReelId != normalizedId) {
            countedReelId = normalizedId
            return ConfirmedReelView(
                reelId = reelId,
                watchDurationSeconds = visibleFor
            )
        }

        return null
    }

    fun reset() {
        focusedReelId = null
        focusedAt = null
        countedReelId = null
    }

    companion object {
        const val MIN_VIEW_SECONDS = 2L
        private const val UNKNOWN_REEL_ID = "unknown"
    }
}

data class ConfirmedReelView(
    val reelId: String?,
    val watchDurationSeconds: Long
)
