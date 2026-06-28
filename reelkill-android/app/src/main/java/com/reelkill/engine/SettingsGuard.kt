package com.reelkill.engine

import com.reelkill.data.db.entity.AppSettings

object SettingsGuard {
    fun isLooserThan(current: AppSettings, proposed: AppSettings): Boolean {
        return proposed.dailyLimit > current.dailyLimit ||
            proposed.bingeThresholdReels > current.bingeThresholdReels ||
            proposed.bingeWindowMinutes > current.bingeWindowMinutes ||
            proposed.cooldownBaseSeconds < current.cooldownBaseSeconds ||
            proposed.frictionThresholdPct > current.frictionThresholdPct ||
            (current.blockReelsTab && !proposed.blockReelsTab) ||
            (current.blockExplore && !proposed.blockExplore) ||
            (current.blockStories && !proposed.blockStories) ||
            (current.blockSuggested && !proposed.blockSuggested) ||
            (!current.allowReelsInDm && proposed.allowReelsInDm) ||
            (current.antiScrollEnabled && !proposed.antiScrollEnabled) ||
            sensitivityRank(proposed.antiScrollSensitivity) < sensitivityRank(current.antiScrollSensitivity) ||
            (current.strictModeEnabled && !proposed.strictModeEnabled) ||
            (current.accountabilityPartnerEnabled && !proposed.accountabilityPartnerEnabled) ||
            (hasScheduledBreak(current) && !hasScheduledBreak(proposed))
    }

    private fun sensitivityRank(sensitivity: String): Int {
        return when (sensitivity) {
            AppSettings.SENSITIVITY_STRICT -> 3
            AppSettings.SENSITIVITY_CUSTOM -> 2
            else -> 1
        }
    }

    private fun hasScheduledBreak(settings: AppSettings): Boolean {
        return !settings.scheduledBreakStart.isNullOrBlank() && !settings.scheduledBreakEnd.isNullOrBlank()
    }
}
