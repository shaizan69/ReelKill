package com.reelkill.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "daily_summaries",
    primaryKeys = ["day", "appId"],
    indices = [
        Index(value = ["appId"]),
        Index(value = ["day"])
    ]
)
data class DailySummary(
    val day: String,
    val appId: String,
    val reelsWatched: Int,
    val timeSpentSeconds: Long,
    val sessions: Int,
    val cooldownsTriggered: Int,
    val hardBlockHit: Boolean,
    val frictionShown: Int,
    val frictionDismissed: Int,
    val attentionScore: Int
) {
    init {
        require(reelsWatched >= 0) { "reelsWatched cannot be negative" }
        require(timeSpentSeconds >= 0) { "timeSpentSeconds cannot be negative" }
        require(sessions >= 0) { "sessions cannot be negative" }
        require(cooldownsTriggered >= 0) { "cooldownsTriggered cannot be negative" }
        require(frictionShown >= 0) { "frictionShown cannot be negative" }
        require(frictionDismissed >= 0) { "frictionDismissed cannot be negative" }
        require(attentionScore in 0..100) { "attentionScore must be in 0..100" }
    }
}
