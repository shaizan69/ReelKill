package com.reelkill.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val appId: String,
    val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    val bingeThresholdReels: Int = DEFAULT_BINGE_THRESHOLD_REELS,
    val bingeWindowMinutes: Int = DEFAULT_BINGE_WINDOW_MINUTES,
    val cooldownBaseSeconds: Int = DEFAULT_COOLDOWN_BASE_SECONDS,
    val frictionThresholdPct: Float = DEFAULT_FRICTION_THRESHOLD_PCT,
    val blockReelsTab: Boolean = true,
    val blockExplore: Boolean = true,
    val blockStories: Boolean = true,
    val blockSuggested: Boolean = true,
    val allowReelsInDm: Boolean = false,
    val antiScrollEnabled: Boolean = true,
    val antiScrollSensitivity: String = SENSITIVITY_CHILL,
    val scheduledBreakStart: String? = null,
    val scheduledBreakEnd: String? = null,
    val strictModeEnabled: Boolean = false,
    val strictModePin: String? = null,
    val accountabilityPartnerEnabled: Boolean = false
) {
    init {
        require(dailyLimit in DAILY_LIMIT_MIN..DAILY_LIMIT_MAX) {
            "dailyLimit must be in $DAILY_LIMIT_MIN..$DAILY_LIMIT_MAX"
        }
        require(bingeThresholdReels > 0) { "bingeThresholdReels must be positive" }
        require(bingeWindowMinutes > 0) { "bingeWindowMinutes must be positive" }
        require(cooldownBaseSeconds > 0) { "cooldownBaseSeconds must be positive" }
        require(frictionThresholdPct in 0f..1f) { "frictionThresholdPct must be in 0.0..1.0" }
        require(antiScrollSensitivity in SUPPORTED_SENSITIVITIES) {
            "antiScrollSensitivity must be strict, chill, or custom"
        }
    }

    companion object {
        const val DAILY_LIMIT_MIN = 1
        const val DAILY_LIMIT_MAX = 50
        const val DEFAULT_DAILY_LIMIT = 30
        const val DEFAULT_BINGE_THRESHOLD_REELS = 15
        const val DEFAULT_BINGE_WINDOW_MINUTES = 10
        const val DEFAULT_COOLDOWN_BASE_SECONDS = 300
        const val DEFAULT_FRICTION_THRESHOLD_PCT = 0.8f

        const val SENSITIVITY_STRICT = "strict"
        const val SENSITIVITY_CHILL = "chill"
        const val SENSITIVITY_CUSTOM = "custom"

        val SUPPORTED_SENSITIVITIES = setOf(
            SENSITIVITY_STRICT,
            SENSITIVITY_CHILL,
            SENSITIVITY_CUSTOM
        )

        fun defaultFor(appId: String): AppSettings = AppSettings(appId = appId)
    }
}
