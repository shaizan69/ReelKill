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
import com.reelkill.data.datastore.ReelKillPreferences
import com.reelkill.data.repository.SettingsRepository
import com.reelkill.engine.AntiScrollDecision
import com.reelkill.engine.AntiScrollEngine
import com.reelkill.engine.ReelTracker
import com.reelkill.ui.overlay.ReelsBlurCover
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class ReelKillAccessibilityService : AccessibilityService() {
    @Inject lateinit var blockingRuleDao: BlockingRuleDao
    @Inject lateinit var reelTracker: ReelTracker
    @Inject lateinit var antiScrollEngine: AntiScrollEngine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var reelKillPreferences: ReelKillPreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var activeRules: List<BlockingRule> = emptyList()
    @Volatile private var targetAppId: String = AppIds.INSTAGRAM
    private val foregroundPackageFlow = MutableStateFlow<String?>(null)
    private var foregroundAppPackage: String?
        get() = foregroundPackageFlow.value
        set(value) {
            foregroundPackageFlow.value = value
        }
    private val lastRuleDispatchAt = mutableMapOf<String, Long>()
    private val activeBlurCovers = ConcurrentHashMap<String, ReelsBlurCover>()
    private val cachedSettings = ConcurrentHashMap<String, com.reelkill.data.db.entity.AppSettings>()
    private val activeRulesCache = ConcurrentHashMap<String, List<BlockingRule>>()
    private var lastScrollRuleCheckAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentService = this
        Timber.d("ReelKillAccessibilityService connected")
        // Preload synchronously so first events don't see empty caches.
        serviceScope.launch {
            AppIds.V1_SUPPORTED_APPS.forEach { appId ->
                runCatching {
                    activeRulesCache[appId] = blockingRuleDao.getActiveForPackage(appId)
                    cachedSettings[appId] = settingsRepository.getOrCreateSettings(appId)
                }.onFailure { error ->
                    Timber.e(error, "Failed to preload rules/settings for $appId")
                }
            }
            foregroundPackageFlow.value?.let { pkg ->
                activeRules = activeRulesCache[pkg] ?: blockingRuleDao.getActiveForPackage(pkg)
            }
        }
        serviceScope.launch {
            foregroundPackageFlow
                .filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { pkg -> blockingRuleDao.observeActiveForPackage(pkg) }
                .catch { error -> Timber.e(error, "Failed to observe blocking rules") }
                .collect { rules ->
                    foregroundPackageFlow.value?.let { pkg -> activeRulesCache[pkg] = rules }
                    activeRules = rules
                }
        }
        // Track which app the user selected - block ONLY that app.
        serviceScope.launch {
            runCatching {
                targetAppId = reelKillPreferences.selectedAppId.first()
            }
            reelKillPreferences.selectedAppId
                .catch { error -> Timber.e(error, "Failed to observe selected app") }
                .collect { newTarget ->
                    val old = targetAppId
                    targetAppId = newTarget
                    if (old != newTarget) {
                        Timber.d("Target app changed $old -> $newTarget, clearing overlays")
                        removeBlurCover(old)
                        removeBlurCover(newTarget)
                        activeRulesCache[newTarget]?.let { activeRules = it }
                    }
                }
        }
        // Reactive settings cache for fast access in onAccessibilityEvent
        serviceScope.launch {
            AppIds.V1_SUPPORTED_APPS.forEach { appId ->
                launch {
                    settingsRepository.observeSettings(appId)
                        .catch { error -> Timber.e(error, "Failed to observe settings for $appId") }
                        .collect { settings -> cachedSettings[appId] = settings }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            handleAccessibilityEventInternal(event)
        }.onFailure { error ->
            Timber.e(error, "Error in onAccessibilityEvent (swallowed to avoid crash)")
        }
    }

    private fun handleAccessibilityEventInternal(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return

        if (!AppIds.isSupported(packageName)) {
            if (foregroundAppPackage != null) {
                reelTracker.reset()
                sendToForegroundService(ReelKillForegroundService.ACTION_APP_BACKGROUND, foregroundAppPackage!!)
                removeBlurCover(foregroundAppPackage!!)
                foregroundAppPackage = null
            }
            return
        }

        // Block ONLY the user-selected app. Other supported apps pass through.
        if (!isTargetApp(packageName)) {
            removeBlurCover(packageName)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (foregroundAppPackage != null && foregroundAppPackage != packageName) {
                reelTracker.reset()
                sendToForegroundService(ReelKillForegroundService.ACTION_APP_BACKGROUND, foregroundAppPackage!!)
                removeBlurCover(foregroundAppPackage!!)
            }
            if (foregroundAppPackage != packageName) {
                foregroundAppPackage = packageName
                sendToForegroundService(ReelKillForegroundService.ACTION_APP_FOREGROUND, packageName)
                // Refresh rules synchronously from cache, fallback to preload.
                activeRulesCache[packageName]?.let { activeRules = it }
                if (activeRules.isEmpty()) {
                    serviceScope.launch {
                        runCatching {
                            val fresh = blockingRuleDao.getActiveForPackage(packageName)
                            activeRulesCache[packageName] = fresh
                            activeRules = fresh
                        }
                    }
                }
            }
        } else if (foregroundAppPackage == null) {
            // Some OEMs batch WINDOW_STATE_CHANGED; still track foreground package.
            foregroundAppPackage = packageName
            sendToForegroundService(ReelKillForegroundService.ACTION_APP_FOREGROUND, packageName)
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(packageName)
            // Reels feeds scroll constantly - still check blocking rules (throttled),
            // otherwise blocking never fires while scrolling.
            val now = SystemClock.elapsedRealtime()
            if (now - lastScrollRuleCheckAt < 800L) return
            lastScrollRuleCheckAt = now
            checkRulesAndTrack(packageName)
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        checkRulesAndTrack(packageName)
    }

    private fun checkRulesAndTrack(packageName: String) {
        // NOTE: Do NOT call root.recycle() - deprecated in API 33 (no-op) and
        // recycling the active-window root while child nodes are in use
        // can throw IllegalStateException on some OEMs.
        //
        // Extension-equivalent (reels-blur.js _blurElement): cover ONLY the
        // matched reel node rect. Never close/back out of the app - chat,
        // feed photos and navigation outside the cover stay usable.
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val matchedRule = runCatching { findFirstMatchingRule(root, activeRules) }.getOrNull()
        if (matchedRule != null) {
            if (matchedRule.rule.action == BlockingRule.ACTION_HIDE) {
                // Blur-equivalent: dismiss/hide just this reel node, app stays usable.
                tryDismissNode(matchedRule.node)
                removeBlurCover(packageName)
            } else {
                // BACK rules become positioned blur covers, never BACK closes.
                runCatching {
                    showBlurCover(packageName, matchedRule.node, matchedRule.rule.id)
                }.onFailure { error ->
                    Timber.e(error, "showBlurCover failed")
                }
            }

            sendRuleMatched(matchedRule.rule)
        } else {
            // Dismiss stale badge when user navigated away within same app.
            removeBlurCover(packageName)
        }

        if (matchedRule?.rule?.id?.contains("reels_viewer") == true || containsReelsViewer(root)) {
            val confirmed = reelTracker.onReelCandidateVisible(extractReelId(root))
            if (confirmed != null) {
                sendReelViewed(packageName, confirmed.reelId, confirmed.watchDurationSeconds)
            }
        } else if (matchedRule == null) {
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
        if (currentService === this) currentService = null
        serviceScope.cancel()
        activeBlurCovers.values.forEach { it.dismiss() }
        activeBlurCovers.clear()
        super.onDestroy()
    }

    /**
     * Extension-equivalent blur (reels-blur.js _blurElement): cover ONLY the
     * matched reel node rect. Never close/back out of the app - chat, feed
     * photos and navigation outside the cover stay usable.
     */
    private fun showBlurCover(appId: String, node: AccessibilityNodeInfo, ruleId: String) {
        val settings = cachedSettings[appId]
        if (settings != null && !shouldApplyRule(ruleId, settings)) {
            removeBlurCover(appId)
            return
        }
        val bounds = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        if (bounds.isEmpty) return
        val cover = activeBlurCovers.getOrPut(appId) { ReelsBlurCover(this, appId) }
        cover.showOrMove(bounds)
    }

    private fun removeBlurCover(appId: String) {
        activeBlurCovers.remove(appId)?.dismiss()
    }

    private fun isTargetApp(packageName: String): Boolean {
        if (packageName == targetAppId) return true
        // Both TikTok package names count as the same user selection.
        val tiktokSet = setOf(AppIds.TIKTOK, AppIds.TIKTOK_GLOBAL)
        return packageName in tiktokSet && targetAppId in tiktokSet
    }

    private fun shouldApplyRule(ruleId: String, settings: com.reelkill.data.db.entity.AppSettings): Boolean {
        val id = ruleId.lowercase()
        return when {
            id.contains("reels_tab") || id.contains("reels_viewer") ||
                id.contains("reels_fallback") || id.contains("shorts") ||
                id.contains("foryou") || id.contains("for_you") ||
                id.contains("spotlight") || id.contains("watch") ||
                id.contains("video") || id.contains("feed") -> settings.blockReelsTab
            id.contains("explore") || id.contains("discover") -> settings.blockExplore
            id.contains("stories") || id.contains("story") -> settings.blockStories
            id.contains("suggested") || id.contains("sponsored") -> settings.blockSuggested
            else -> true
        }
    }

    private fun findFirstMatchingRule(root: AccessibilityNodeInfo, rules: List<BlockingRule>): RuleMatch? {
        if (rules.isEmpty()) return null
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()

            rules.firstOrNull { rule -> nodeMatchesRule(node, rule) && isActionableMatch() }
                ?.let { return RuleMatch(it, node) }

            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let { nodes.add(it) }
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
        return runCatching {
            if (rule.viewId != null) {
                val actual = node.viewIdResourceName
                if (actual != null) {
                    // Exact match, plus suffix match for OEM/app-version prefix changes.
                    if (actual == rule.viewId || actual.endsWith("/" + rule.viewId.substringAfterLast("/"))) {
                        return@runCatching true
                    }
                }
                // viewId-only rules must not fall through to content-desc.
                if (rule.contentDescContains == null) return@runCatching false
            }
            val needle = rule.contentDescContains?.lowercase()?.trim()
            if (needle.isNullOrEmpty()) return@runCatching rule.viewId != null
            val description = node.contentDescription?.toString()?.lowercase()?.trim()
            if (description?.contains(needle) == true) return@runCatching true
            // Also check visible text as fallback - many short-form surfaces expose
            // labels via text instead of content-description on some versions.
            val text = node.text?.toString()?.lowercase()?.trim()
            text?.contains(needle) == true
        }.getOrDefault(false)
    }

    private fun isActionableMatch(): Boolean {
        // Previous isSelected gate prevented tab rules from ever firing on
        // modern Instagram builds. Debounce in sendRuleMatched already
        // protects against spam, so treat every match as actionable.
        return true
    }

    private fun containsReelsViewer(root: AccessibilityNodeInfo): Boolean {
        return runCatching {
            findFirstMatchingRule(
                root,
                activeRules.filter { it.id.contains("reels_viewer") }
            ) != null
        }.getOrDefault(false)
    }

    private fun extractReelId(root: AccessibilityNodeInfo): String? {
        return runCatching {
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            nodes.add(root)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                val text = runCatching { node.text?.toString() }.getOrNull()
                val description = runCatching { node.contentDescription?.toString() }.getOrNull()
                val candidate = listOfNotNull(text, description).firstOrNull { value ->
                    value.contains("/reel/") || value.contains("reel", ignoreCase = true)
                }
                if (candidate != null) return@runCatching candidate.take(120)
                val childCount = runCatching { node.childCount }.getOrDefault(0)
                for (index in 0 until childCount) {
                    runCatching { node.getChild(index) }.getOrNull()?.let { nodes.add(it) }
                }
            }
            null
        }.getOrNull()
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
        @Volatile private var currentService: ReelKillAccessibilityService? = null

        fun performGlobalBack(): Boolean {
            val service = currentService ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private data class RuleMatch(
        val rule: BlockingRule,
        val node: AccessibilityNodeInfo
    )
}
