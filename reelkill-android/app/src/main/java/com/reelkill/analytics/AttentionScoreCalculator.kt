package com.reelkill.analytics

import javax.inject.Inject
import kotlin.math.roundToInt

class AttentionScoreCalculator @Inject constructor() {
    fun calculate(
        hardBlockHit: Boolean,
        cooldownsTriggered: Int,
        lateNightReels: Int,
        weekOverWeekImproved: Boolean,
        frictionDismissedImproved: Boolean
    ): Int {
        var score = 0f
        if (!hardBlockHit) score += 30f
        if (cooldownsTriggered == 0) score += 20f
        if (lateNightReels == 0) score += 20f
        if (weekOverWeekImproved) score += 20f
        if (frictionDismissedImproved) score += 10f
        return score.coerceIn(0f, 100f).roundToInt()
    }
}
