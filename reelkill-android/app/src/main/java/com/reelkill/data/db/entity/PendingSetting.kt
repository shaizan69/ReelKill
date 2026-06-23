package com.reelkill.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_settings",
    indices = [
        Index(value = ["appId"]),
        Index(value = ["appliesAt"])
    ]
)
data class PendingSetting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appId: String,
    val changesJson: String,
    val appliesAt: String
)
