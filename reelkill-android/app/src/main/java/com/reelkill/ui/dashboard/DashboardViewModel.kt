package com.reelkill.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reelkill.analytics.AttentionScoreCalculator
import com.reelkill.common.AppIds
import com.reelkill.data.db.dao.DailySummaryDao
import com.reelkill.data.db.dao.UsageEventDao
import com.reelkill.data.db.entity.DailySummary
import com.reelkill.data.db.entity.UsageEvent
import com.reelkill.data.repository.SettingsRepository
import com.reelkill.data.repository.StateRepository
import com.reelkill.engine.StreakTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val stateRepository: StateRepository,
    private val settingsRepository: SettingsRepository,
    private val dailySummaryDao: DailySummaryDao,
    private val usageEventDao: UsageEventDao,
    private val streakTracker: StreakTracker,
    private val reelKillPreferences: com.reelkill.data.datastore.ReelKillPreferences
) : ViewModel() {

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState

    init {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val weekStart = LocalDate.now().minusDays(6).toString()

            reelKillPreferences.selectedAppId.flatMapLatest { appId ->
                combine(
                    stateRepository.observeState(appId),
                    settingsRepository.observeSettings(appId),
                    streakTracker.observeStreak(appId),
                    dailySummaryDao.observeRange(appId, weekStart, today),
                    usageEventDao.observeForDay(appId, today)
                ) { state, settings, streak, storedWeekSummaries, eventsToday ->
                    DashboardInputs(appId, state, settings, streak, storedWeekSummaries, eventsToday)
                }
            }.collect { inputs ->
                val appId = inputs.appId
                val state = inputs.state
                val settings = inputs.settings
                val todaySummary = deriveSummaryFromEvents(inputs.eventsToday, appId, today)
                val weekSummaries = (inputs.storedWeekSummaries.filterNot { it.day == today } + todaySummary)
                    .sortedBy { it.day }
                    .takeLast(7)
                val hourlyBuckets = IntArray(24)
                inputs.eventsToday.filter { it.eventType == UsageEvent.TYPE_REEL_VIEWED }
                    .forEach { if (it.hour in 0..23) hourlyBuckets[it.hour]++ }

                _dashboardState.update { current ->
                    current.copy(
                        selectedAppId = appId,
                        reelsWatchedToday = state.reelsWatchedToday,
                        dailyLimit = settings.dailyLimit,
                        cooldownsToday = state.cooldownCountToday,
                        hardBlockActive = state.hardBlockActive,
                        hardBlockExpires = state.hardBlockExpires,
                        cooldownActive = state.cooldownActive,
                        cooldownExpires = state.cooldownExpires,
                        streak = inputs.streak,
                        todayAttentionScore = todaySummary.attentionScore,
                        todayTimeSpentSeconds = todaySummary.timeSpentSeconds,
                        todaySessions = todaySummary.sessions,
                        hourlyHeatmap = hourlyBuckets.toList(),
                        weekSummaries = weekSummaries,
                        weekTotalReels = weekSummaries.sumOf { it.reelsWatched },
                        weekTotalCooldowns = weekSummaries.sumOf { it.cooldownsTriggered },
                        weekAvgScore = if (weekSummaries.isNotEmpty()) weekSummaries.map { it.attentionScore }.average().toInt() else 0
                    )
                }
            }
        }
    }

    fun selectApp(appId: String) {
        viewModelScope.launch {
            reelKillPreferences.setSelectedAppId(appId)
        }
    }

    private fun deriveSummaryFromEvents(events: List<UsageEvent>, appId: String, day: String): DailySummary {
        val reelEvents = events.filter { it.eventType == UsageEvent.TYPE_REEL_VIEWED }
        val cooldowns = events.count { it.eventType == UsageEvent.TYPE_COOLDOWN_TRIGGERED }
        val hardBlockToDate = events.any { it.eventType == UsageEvent.TYPE_HARD_BLOCK_TRIGGERED }
        val frictionShown = events.count { it.eventType == UsageEvent.TYPE_FRICTION_SHOWN }
        val frictionDismissed = events.count { it.eventType == UsageEvent.TYPE_FRICTION_DISMISSED }
        val lateNightReels = reelEvents.count { it.hour >= 23 || it.hour < 5 }
        val sessions = events.count { it.eventType == UsageEvent.TYPE_SESSION_START }
        val timeSpentSeconds = reelEvents.sumOf { it.watchDuration ?: 0L }

        return DailySummary(
            day = day,
            appId = appId,
            reelsWatched = reelEvents.size,
            timeSpentSeconds = timeSpentSeconds,
            sessions = sessions,
            cooldownsTriggered = cooldowns,
            hardBlockHit = hardBlockToDate,
            frictionShown = frictionShown,
            frictionDismissed = frictionDismissed,
            attentionScore = AttentionScoreCalculator().calculate(
                hardBlockHit = hardBlockToDate,
                cooldownsTriggered = cooldowns,
                lateNightReels = lateNightReels,
                weekOverWeekImproved = true,
                frictionDismissedImproved = frictionDismissed <= frictionShown
            )
        )
    }
}

private data class DashboardInputs(
    val appId: String,
    val state: com.reelkill.data.db.entity.AppState,
    val settings: com.reelkill.data.db.entity.AppSettings,
    val streak: com.reelkill.engine.StreakState,
    val storedWeekSummaries: List<DailySummary>,
    val eventsToday: List<UsageEvent>
)

data class DashboardState(
    val selectedAppId: String = AppIds.INSTAGRAM,
    val reelsWatchedToday: Int = 0,
    val dailyLimit: Int = 30,
    val cooldownsToday: Int = 0,
    val hardBlockActive: Boolean = false,
    val hardBlockExpires: String? = null,
    val cooldownActive: Boolean = false,
    val cooldownExpires: String? = null,
    val streak: com.reelkill.engine.StreakState? = null,
    val todayAttentionScore: Int = 100,
    val todayTimeSpentSeconds: Long = 0,
    val todaySessions: Int = 0,
    val hourlyHeatmap: List<Int> = List(24) { 0 },
    val weekSummaries: List<com.reelkill.data.db.entity.DailySummary> = emptyList(),
    val weekTotalReels: Int = 0,
    val weekTotalCooldowns: Int = 0,
    val weekAvgScore: Int = 0
)
