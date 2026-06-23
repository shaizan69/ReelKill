package com.reelkill.engine

import com.reelkill.data.db.entity.AppSettings

object SettingsGuard {
    fun isLooserThan(current: AppSettings, proposed: AppSettings): Boolean {
        return proposed.dailyLimit > current.dailyLimit ||
            proposed.bingeThresholdReels > current.bingeThresholdReels ||
            proposed.bingeWindowMinutes > current.bingeWindowMinutes ||
            proposed.cooldownBaseSeconds < current.cooldownBaseSeconds ||
            proposed.frictionThresholdPct > current.frictionThresholdPct
    }
}
