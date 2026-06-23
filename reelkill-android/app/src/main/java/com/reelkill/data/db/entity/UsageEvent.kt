package com.reelkill.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_events",
    indices = [
        Index(value = ["appId", "timestamp"]),
        Index(value = ["appId", "day"]),
        Index(value = ["eventType", "timestamp"]),
        Index(value = ["sessionId"])
    ]
)
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val appId: String,
    val reelId: String?,
    val watchDuration: Long?,
    val timestamp: String,
    val sessionId: String,
    val day: String,
    val hour: Int
) {
    init {
        require(hour in 0..23) { "hour must be in 0..23" }
    }

    companion object {
        const val TYPE_REEL_VIEWED = "reel_viewed"
        const val TYPE_SESSION_START = "session_start"
        const val TYPE_SESSION_END = "session_end"
        const val TYPE_COOLDOWN_TRIGGERED = "cooldown_triggered"
        const val TYPE_HARD_BLOCK_TRIGGERED = "hard_block_triggered"
        const val TYPE_FRICTION_SHOWN = "friction_shown"
        const val TYPE_FRICTION_DISMISSED = "friction_dismissed"
        const val TYPE_PATTERN_DETECTED = "pattern_detected"
    }
}
