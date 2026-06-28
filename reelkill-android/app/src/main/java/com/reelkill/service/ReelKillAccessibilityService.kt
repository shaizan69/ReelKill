package com.reelkill.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class ReelKillAccessibilityService : AccessibilityService() {
    @Inject lateinit var blockingRuleDao: BlockingRuleDao
    @Inject lateinit var reelTracker: ReelTracker
    @Inject lateinit var antiScrollEngine: AntiScrollEngine
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var activeRules: List<BlockingRule> = emptyList()
    private val foregroundPackageFlow = MutableStateFlow<String?>(null)
    private var foregroundAppPackage: String?
        get() = foregroundPackageFlow.value
        set(value) {
            foregroundPackageFlow.value = value
        }
    private val lastRuleDispatchAt = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = WeakReference(this)
        serviceScope.launch {
            foregroundPackageFlow
                .filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { pkg -> blockingRuleDao.observeActiveForPackage(pkg) }
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
            reelTracker.reset()
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
            if (matchedRule.rule.action == BlockingRule.ACTION_HIDE) {
                tryDismissNode(matchedRule.node)
            }
            sendRuleMatched(matchedRule.rule)
        }

        if (matchedRule?.rule?.id?.contains("reels_viewer") == true || containsReelsViewer(root)) {
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
                    antiScrollEngine.suppressForOneMinute()
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

    private fun findFirstMatchingRule(root: AccessibilityNodeInfo, rules: List<BlockingRule>): RuleMatch? {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()

            rules.firstOrNull { rule -> nodeMatchesRule(node, rule) && isActionableMatch(node, rule) }
                ?.let { return RuleMatch(it, node) }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { nodes.add(it) }
            }
        }
        return null
    }

    private fun tryDismissNode(node: AccessibilityNodeInfo) {
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
        }.onFailure { error ->
            Timber.d(error, "Unable to dismiss matched accessibility node")
        }
    }

    private fun nodeMatchesRule(node: AccessibilityNodeInfo, rule: BlockingRule): Boolean {
        val viewId = node.viewIdResourceName
        val description = node.contentDescription?.toString()?.lowercase()
        return (rule.viewId != null && rule.viewId == viewId) ||
            (rule.contentDescContains != null && description?.contains(rule.contentDescContains.lowercase()) == true)
    }

    private fun isActionableMatch(node: AccessibilityNodeInfo, rule: BlockingRule): Boolean {
        if (rule.action != BlockingRule.ACTION_BACK) return true
        if (rule.id.contains("reels_viewer")) return true
        if (rule.id.contains("reels_tab") || rule.id.contains("explore_tab")) {
            return node.isSelected
        }
        return true
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
        if (!shouldDispatchRule(rule.id)) return
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
        dispatchToForegroundService(intent)
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
        dispatchToForegroundService(intent)
    }

    private fun dispatchToForegroundService(intent: Intent) {
        runCatching {
            if (ReelKillForegroundService.isRunning) {
                startService(intent)
            } else {
                ContextCompat.startForegroundService(this, intent)
            }
        }.onFailure { error ->
            Timber.e(error, "Failed to dispatch ${intent.action} to foreground service")
        }
    }

    private fun shouldDispatchRule(ruleId: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastRuleDispatchAt[ruleId] ?: 0L
        if (now - last < RULE_DISPATCH_DEBOUNCE_MS) return false
        lastRuleDispatchAt[ruleId] = now
        return true
    }

    companion object {
        private const val RULE_DISPATCH_DEBOUNCE_MS = 1_200L
        private var currentService: WeakReference<ReelKillAccessibilityService>? = null

        fun requestGlobalBackFromForegroundService(): Boolean {
            val service = currentService?.get() ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private data class RuleMatch(
        val rule: BlockingRule,
        val node: AccessibilityNodeInfo
    )
}
