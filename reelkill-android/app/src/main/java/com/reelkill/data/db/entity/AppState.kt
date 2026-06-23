package com.reelkill.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_state")
data class AppState(
    @PrimaryKey val appId: String,
    val reelsWatchedToday: Int = 0,
    val cooldownActive: Boolean = false,
    val cooldownExpires: String? = null,
    val cooldownCountToday: Int = 0,
    val cooldownCountWindowStart: String? = null,
    val hardBlockActive: Boolean = false,
    val hardBlockExpires: String? = null,
    val currentSessionId: String? = null,
    val currentSessionStart: String? = null,
    val lastReelViewAt: String? = null,
    val frictionShownThisSession: Boolean = false,
    val blockAnchorTimestamp: String? = null
) {
    init {
        require(reelsWatchedToday >= 0) { "reelsWatchedToday cannot be negative" }
        require(cooldownCountToday >= 0) { "cooldownCountToday cannot be negative" }
    }

    companion object {
        fun initial(appId: String): AppState = AppState(appId = appId)
    }
}
