package com.reelkill.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelkill.common.AppIds
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.repository.SettingsRepository
import com.reelkill.data.repository.SettingsWriteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val appId = AppIds.INSTAGRAM
    private val _settings = MutableStateFlow(AppSettings.defaultFor(appId))
    val settings: StateFlow<AppSettings> = _settings

    private val _lastWriteMessage = MutableStateFlow<String?>(null)
    val lastWriteMessage: StateFlow<String?> = _lastWriteMessage

    init {
        viewModelScope.launch {
            settingsRepository.observeSettings(appId).collect { currentSettings ->
                _settings.update { currentSettings }
            }
        }
    }

    fun updateDailyLimit(limit: Int) {
        write(_settings.value.copy(dailyLimit = limit.coerceIn(1, 50)))
    }

    fun updateFrictionThreshold(pct: Float) {
        write(_settings.value.copy(frictionThresholdPct = pct.coerceIn(0.5f, 1f)))
    }

    fun toggleBlockReelsTab(block: Boolean) {
        write(_settings.value.copy(blockReelsTab = block))
    }

    fun toggleBlockExplore(block: Boolean) {
        write(_settings.value.copy(blockExplore = block))
    }

    fun toggleBlockStories(block: Boolean) {
        write(_settings.value.copy(blockStories = block))
    }

    fun toggleBlockSuggested(block: Boolean) {
        write(_settings.value.copy(blockSuggested = block))
    }

    fun toggleAllowReelsInDm(allow: Boolean) {
        write(_settings.value.copy(allowReelsInDm = allow))
    }

    fun updateBingeThreshold(reels: Int) {
        write(_settings.value.copy(bingeThresholdReels = reels.coerceIn(3, 50)))
    }

    fun updateBingeWindow(minutes: Int) {
        write(_settings.value.copy(bingeWindowMinutes = minutes.coerceIn(3, 60)))
    }

    fun updateCooldownBase(seconds: Int) {
        write(_settings.value.copy(cooldownBaseSeconds = seconds.coerceIn(60, 3600)))
    }

    fun toggleAntiScroll(enabled: Boolean) {
        write(_settings.value.copy(antiScrollEnabled = enabled))
    }

    fun updateAntiScrollSensitivity(sensitivity: String) {
        if (sensitivity in AppSettings.SUPPORTED_SENSITIVITIES) {
            write(_settings.value.copy(antiScrollSensitivity = sensitivity))
        }
    }

    fun toggleStrictMode(enabled: Boolean) {
        write(_settings.value.copy(strictModeEnabled = enabled))
    }

    fun updateStrictModePin(pin: String) {
        write(_settings.value.copy(strictModePin = pin.take(12)))
    }

    fun toggleAccountabilityPartner(enabled: Boolean) {
        write(_settings.value.copy(accountabilityPartnerEnabled = enabled))
    }

    fun updateScheduledBreak(start: String?, end: String?) {
        write(_settings.value.copy(scheduledBreakStart = start, scheduledBreakEnd = end))
    }

    fun clearMessage() {
        _lastWriteMessage.value = null
    }

    private fun write(proposed: AppSettings) {
        viewModelScope.launch {
            when (val result = settingsRepository.writeSettings(proposed)) {
                is SettingsWriteResult.Applied -> {
                    _lastWriteMessage.value = "Settings applied."
                }
                is SettingsWriteResult.Queued -> {
                    _lastWriteMessage.value = "Looser change queued for 24 hours. Applies at ${result.pendingSetting.appliesAt}."
                }
            }
        }
    }
}
