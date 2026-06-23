package com.reelkill.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.reelkill.common.AppIds
import com.reelkill.data.db.dao.BlockingRuleDao
import com.reelkill.data.db.entity.BlockingRule
import com.reelkill.data.repository.SettingsRepository
import com.reelkill.engine.AntiScrollDecision
import com.reelkill.engine.AntiScrollEngine
import com.reelkill.engine.ReelTracker
import dagger.hilt.android.AndroidEntryPoint
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

@AndroidEntryPoint
class ReelKillAccessibilityService : AccessibilityService() {
    @Inject lateinit var blockingRuleDao: BlockingRuleDao
    @Inject lateinit var reelTracker: ReelTracker
    @Inject lateinit var antiScrollEngine: AntiScrollEngine
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var activeRules: List<BlockingRule> = emptyList()
    private var foregroundAppPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = WeakReference(this)
        serviceScope.launch {
            blockingRuleDao.observeActive()
                .catch { error -> Timber.e(error, "Failed to observe blocking rules") }
                .collect { rules -> activeRules = rules }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return

        if (!AppIds.isSupported(packageName)) {
            if (foregroundAppPackage != null) {
                reelTracker.reset()
                sendToForegroundService(ReelKillForegroundService.ACTION_APP_BACKGROUND, foregroundAppPackage!!)
                foregroundAppPackage = null
            }
            return
        }

        if (foregroundAppPackage != packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (foregroundAppPackage != null) {
                reelTracker.reset()
                sendToForegroundService(ReelKillForegroundService.ACTION_APP_BACKGROUND, foregroundAppPackage!!)
            }
            foregroundAppPackage = packageName
            sendToForegroundService(ReelKillForegroundService.ACTION_APP_FOREGROUND, packageName)
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(packageName)
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow ?: return
        val matchedRule = findFirstMatchingRule(root, activeRules)
        if (matchedRule != null) {
            sendRuleMatched(matchedRule)
        }

        if (matchedRule?.id?.contains("reels_viewer") == true || containsReelsViewer(root)) {
            val confirmed = reelTracker.onReelCandidateVisible(extractReelId(root))
            if (confirmed != null) {
                sendReelViewed(packageName, confirmed.reelId, confirmed.watchDurationSeconds)
            }
        } else {
            reelTracker.reset()
        }
    }

    private fun handleScrollEvent(packageName: String) {
        serviceScope.launch {
            val settings = settingsRepository.getOrCreateSettings(packageName)
            when (antiScrollEngine.recordSwipeUp(settings)) {
                is AntiScrollDecision.ShowPopup -> {
                    sendToForegroundService(
                        action = ReelKillForegroundService.ACTION_SCROLL_HEALTH,
                        appId = packageName
                    )
                }
                is AntiScrollDecision.NoAction -> Unit
            }
        }
    }

    override fun onInterrupt() {
        reelTracker.reset()
    }

    override fun onDestroy() {
        if (currentService?.get() === this) currentService = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun findFirstMatchingRule(root: AccessibilityNodeInfo, rules: List<BlockingRule>): BlockingRule? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            val viewId = node.viewIdResourceName
            val description = node.contentDescription?.toString()?.lowercase()

            rules.firstOrNull { rule ->
                (rule.viewId != null && rule.viewId == viewId) ||
                    (rule.contentDescContains != null && description?.contains(rule.contentDescContains.lowercase()) == true)
            }?.let { return it }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { nodes.add(it) }
            }
        }
        return null
    }

    private fun containsReelsViewer(root: AccessibilityNodeInfo): Boolean {
        return findFirstMatchingRule(
            root,
            activeRules.filter { it.id.contains("reels_viewer") }
        ) != null
    }

    private fun extractReelId(root: AccessibilityNodeInfo): String? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            val candidate = listOfNotNull(text, description).firstOrNull { value ->
                value.contains("/reel/") || value.contains("reel", ignoreCase = true)
            }
            if (candidate != null) return candidate.take(120)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { nodes.add(it) }
            }
        }
        return null
    }

    private fun sendRuleMatched(rule: BlockingRule) {
        sendToForegroundService(
            action = ReelKillForegroundService.ACTION_RULE_MATCHED,
            appId = rule.appPackage,
            extras = mapOf(
                ReelKillForegroundService.EXTRA_RULE_ID to rule.id,
                ReelKillForegroundService.EXTRA_RULE_ACTION to rule.action
            )
        )
    }

    private fun sendReelViewed(appId: String, reelId: String?, watchDurationSeconds: Long) {
        val intent = Intent(this, ReelKillForegroundService::class.java).apply {
            action = ReelKillForegroundService.ACTION_REEL_VIEWED
            putExtra(ReelKillForegroundService.EXTRA_APP_ID, appId)
            putExtra(ReelKillForegroundService.EXTRA_WATCH_DURATION_SECONDS, watchDurationSeconds)
            if (reelId != null) putExtra(ReelKillForegroundService.EXTRA_REEL_ID, reelId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendToForegroundService(
        action: String,
        appId: String,
        extras: Map<String, String> = emptyMap()
    ) {
        val intent = Intent(this, ReelKillForegroundService::class.java).apply {
            this.action = action
            putExtra(ReelKillForegroundService.EXTRA_APP_ID, appId)
            extras.forEach { (key, value) -> putExtra(key, value) }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        private var currentService: WeakReference<ReelKillAccessibilityService>? = null

        fun requestGlobalBackFromForegroundService(): Boolean {
            val service = currentService?.get() ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }
}
