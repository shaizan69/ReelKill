package com.reelkill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import com.reelkill.MainActivity
import com.reelkill.R
import com.reelkill.common.AppIds
import com.reelkill.data.db.ReelKillDatabase
import com.reelkill.data.db.dao.AppStateDao
import com.reelkill.data.db.dao.UsageEventDao
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.db.entity.AppState
import com.reelkill.data.db.entity.BlockingRule
import com.reelkill.data.db.entity.UsageEvent
import com.reelkill.data.repository.SettingsRepository
import com.reelkill.data.repository.UsageRepository
import com.reelkill.engine.BudgetEngine
import com.reelkill.engine.CooldownDecision
import com.reelkill.engine.CooldownEngine
import com.reelkill.engine.CooldownStatus
import com.reelkill.engine.FrictionDecision
import com.reelkill.engine.FrictionEngine
import com.reelkill.engine.HardBlockStatus
import com.reelkill.engine.PatternDetector
import dagger.hilt.android.AndroidEntryPoint
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class ReelKillForegroundService : Service() {
    @Inject lateinit var database: ReelKillDatabase
    @Inject lateinit var appStateDao: AppStateDao
    @Inject lateinit var usageEventDao: UsageEventDao
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usageRepository: UsageRepository
    @Inject lateinit var budgetEngine: BudgetEngine
    @Inject lateinit var cooldownEngine: CooldownEngine
    @Inject lateinit var frictionEngine: FrictionEngine
    @Inject lateinit var patternDetector: PatternDetector
    @Inject lateinit var overlayManager: OverlayManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appId = intent?.getStringExtra(EXTRA_APP_ID) ?: AppIds.INSTAGRAM
        when (intent?.action) {
            ACTION_INSTAGRAM_FOREGROUND, ACTION_APP_FOREGROUND -> handleAppForeground(appId)
            ACTION_INSTAGRAM_BACKGROUND, ACTION_APP_BACKGROUND -> handleAppBackground(appId)
            ACTION_RULE_MATCHED -> handleRuleMatched(intent)
            ACTION_REEL_VIEWED -> handleReelViewed(intent)
            ACTION_FRICTION_DISMISSED -> handleFrictionDismissed(intent)
            ACTION_CHECK_ENFORCEMENT -> handleCheckEnforcement(appId)
            ACTION_SCROLL_HEALTH -> handleScrollHealth(appId)
            ACTION_START_MONITORING, null -> Timber.d("ReelKill foreground service monitoring")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        overlayManager.removeCurrentOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleAppForeground(appId: String) {
        serviceScope.launch {
            val sessionId = startSessionIfNeeded(appId)
            Timber.d("Foreground session active for $appId: $sessionId")
            enforceOnOpen(appId)
        }
    }

    private fun handleAppBackground(appId: String) {
        serviceScope.launch {
            endSessionIfNeeded(appId)
            overlayManager.removeCurrentOverlay()
        }
    }

    private fun handleRuleMatched(intent: Intent) {
        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: AppIds.INSTAGRAM
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        val action = intent.getStringExtra(EXTRA_RULE_ACTION) ?: return

        serviceScope.launch {
            val settings = settingsRepository.getOrCreateSettings(appId)
            if (enforceOnOpen(appId)) return@launch
            if (!shouldApplyRule(ruleId, settings)) return@launch

            when (action) {
                BlockingRule.ACTION_BACK -> ReelKillAccessibilityService.requestGlobalBackFromForegroundService()
                BlockingRule.ACTION_HIDE -> Timber.d("HIDE rule matched: $ruleId")
                BlockingRule.ACTION_LOG -> Timber.d("LOG rule matched: $ruleId")
            }
        }
    }

    private fun handleReelViewed(intent: Intent) {
        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: AppIds.INSTAGRAM
        val reelId = intent.getStringExtra(EXTRA_REEL_ID)
        val watchDuration = intent.getLongExtra(EXTRA_WATCH_DURATION_SECONDS, 0L).coerceAtLeast(0L)

        serviceScope.launch {
            val settings = settingsRepository.getOrCreateSettings(appId)
            val sessionId = startSessionIfNeeded(appId)
            val budget = budgetEngine.recordReelView(
                appId = appId,
                settings = settings,
                reelId = reelId,
                watchDurationSeconds = watchDuration,
                sessionId = sessionId
            )

            if (budget.hardBlockTriggered) {
                overlayManager.showHardBlock(budget.state, settings) {
                    handleCheckEnforcement(appId)
                }
                return@launch
            }

            when (val cooldown = cooldownEngine.evaluateAfterReelView(appId, settings, sessionId)) {
                is CooldownDecision.Triggered -> {
                    overlayManager.showCooldown(cooldown.state, settings, cooldown.nextDurationSeconds) {
                        handleCheckEnforcement(appId)
                    }
                    return@launch
                }
                is CooldownDecision.NotTriggered -> Unit
            }

            when (val friction = frictionEngine.evaluate(appId, settings, sessionId)) {
                is FrictionDecision.Show -> overlayManager.showFriction(friction.state, settings) {
                    sendSelf(ACTION_FRICTION_DISMISSED, appId)
                }
                is FrictionDecision.DoNotShow -> Unit
            }

            val latestState = appStateDao.get(appId) ?: budget.state
            val patterns = patternDetector.detectAfterEvent(appId, settings, latestState)
            patterns.forEach { pattern ->
                usageEventDao.insert(
                    usageRepository.createEvent(
                        eventType = UsageEvent.TYPE_PATTERN_DETECTED,
                        appId = appId,
                        sessionId = sessionId,
                        reelId = pattern.type.eventKey
                    )
                )
            }
            patterns.firstOrNull { it.severity.name != "INFO" }?.let { pattern ->
                overlayManager.showPattern(pattern) { overlayManager.removeCurrentOverlay() }
            }
        }
    }

    private fun handleFrictionDismissed(intent: Intent) {
        val appId = intent.getStringExtra(EXTRA_APP_ID) ?: AppIds.INSTAGRAM
        serviceScope.launch {
            val state = appStateDao.get(appId)
            val sessionId = state?.currentSessionId ?: UUID.randomUUID().toString()
            frictionEngine.recordDismissed(appId, sessionId)
            overlayManager.removeCurrentOverlay()
        }
    }

    private fun handleCheckEnforcement(appId: String) {
        serviceScope.launch { enforceOnOpen(appId) }
    }

    private suspend fun enforceOnOpen(appId: String): Boolean {
        val settings = settingsRepository.getOrCreateSettings(appId)
        return when (val hardBlock = budgetEngine.refreshHardBlock(appId)) {
            is HardBlockStatus.Active -> {
                overlayManager.showHardBlock(hardBlock.state, settings) { handleCheckEnforcement(appId) }
                true
            }
            is HardBlockStatus.Inactive -> {
                when (val cooldown = cooldownEngine.refreshCooldown(appId)) {
                    is CooldownStatus.Active -> {
                        val nextDuration = cooldownEngine.escalatedDurationSeconds(
                            settings.cooldownBaseSeconds,
                            cooldown.state.cooldownCountToday + 1
                        )
                        overlayManager.showCooldown(cooldown.state, settings, nextDuration) {
                            handleCheckEnforcement(appId)
                        }
                        true
                    }
                    is CooldownStatus.ExpiredIntoHardBlock -> {
                        overlayManager.showHardBlock(cooldown.state, settings) { handleCheckEnforcement(appId) }
                        true
                    }
                    is CooldownStatus.Inactive -> {
                        overlayManager.removeCurrentOverlay()
                        false
                    }
                }
            }
        }
    }

    private suspend fun startSessionIfNeeded(appId: String): String {
        val now = Instant.now(Clock.systemUTC())
        val zoneId = ZoneId.systemDefault()
        return database.withTransaction {
            val current = appStateDao.get(appId) ?: AppState.initial(appId)
            if (current.currentSessionId != null) {
                current.currentSessionId
            } else {
                val sessionId = UUID.randomUUID().toString()
                appStateDao.upsert(
                    current.copy(
                        currentSessionId = sessionId,
                        currentSessionStart = now.toString(),
                        frictionShownThisSession = false
                    )
                )
                usageEventDao.insert(
                    UsageEvent(
                        eventType = UsageEvent.TYPE_SESSION_START,
                        appId = appId,
                        reelId = null,
                        watchDuration = null,
                        timestamp = now.toString(),
                        sessionId = sessionId,
                        day = now.atZone(zoneId).toLocalDate().toString(),
                        hour = now.atZone(zoneId).hour
                    )
                )
                sessionId
            }
        }
    }

    private suspend fun endSessionIfNeeded(appId: String) {
        val now = Instant.now(Clock.systemUTC())
        val zoneId = ZoneId.systemDefault()
        database.withTransaction {
            val current = appStateDao.get(appId) ?: return@withTransaction
            val sessionId = current.currentSessionId ?: return@withTransaction
            usageEventDao.insert(
                UsageEvent(
                    eventType = UsageEvent.TYPE_SESSION_END,
                    appId = appId,
                    reelId = null,
                    watchDuration = null,
                    timestamp = now.toString(),
                    sessionId = sessionId,
                    day = now.atZone(zoneId).toLocalDate().toString(),
                    hour = now.atZone(zoneId).hour
                )
            )
            appStateDao.upsert(
                current.copy(
                    currentSessionId = null,
                    currentSessionStart = null,
                    frictionShownThisSession = false
                )
            )
        }
    }

    private fun shouldApplyRule(ruleId: String, settings: AppSettings): Boolean {
        return when {
            ruleId.contains("reels_tab") || ruleId.contains("reels_viewer") -> settings.blockReelsTab
            ruleId.contains("explore") -> settings.blockExplore
            ruleId.contains("stories") -> settings.blockStories
            else -> true
        }
    }

    private fun sendSelf(action: String, appId: String) {
        startService(Intent(this, ReelKillForegroundService::class.java).apply {
            this.action = action
            putExtra(EXTRA_APP_ID, appId)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ReelKill monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps ReelKill enforcement active."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ReelKill is active")
            .setContentText("Monitoring short-form feeds locally on this device.")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START_MONITORING = "com.reelkill.action.START_MONITORING"
        const val ACTION_INSTAGRAM_FOREGROUND = "com.reelkill.action.INSTAGRAM_FOREGROUND"
        const val ACTION_INSTAGRAM_BACKGROUND = "com.reelkill.action.INSTAGRAM_BACKGROUND"
        const val ACTION_RULE_MATCHED = "com.reelkill.action.RULE_MATCHED"
        const val ACTION_REEL_VIEWED = "com.reelkill.action.REEL_VIEWED"
        const val ACTION_FRICTION_DISMISSED = "com.reelkill.action.FRICTION_DISMISSED"
        const val ACTION_CHECK_ENFORCEMENT = "com.reelkill.action.CHECK_ENFORCEMENT"

        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_RULE_ID = "extra_rule_id"
        const val EXTRA_RULE_ACTION = "extra_rule_action"
        const val EXTRA_REEL_ID = "extra_reel_id"
        const val EXTRA_WATCH_DURATION_SECONDS = "extra_watch_duration_seconds"

        private const val CHANNEL_ID = "reelkill_monitoring"
        private const val NOTIFICATION_ID = 1001

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ReelKillForegroundService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
