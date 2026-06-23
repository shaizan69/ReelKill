package com.reelkill.data.repository

import com.reelkill.data.db.entity.PendingSetting

sealed interface SettingsWriteResult {
    data class Applied(val settingsAppId: String) : SettingsWriteResult
    data class Queued(val pendingSetting: PendingSetting) : SettingsWriteResult
}
