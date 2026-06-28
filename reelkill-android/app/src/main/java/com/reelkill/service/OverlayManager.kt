package com.reelkill.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.db.entity.AppState
import com.reelkill.engine.DetectedPattern
import com.reelkill.ui.overlay.AntiScrollOverlay
import com.reelkill.ui.overlay.CooldownOverlay
import com.reelkill.ui.overlay.FrictionModal
import com.reelkill.ui.overlay.HardBlockOverlay
import com.reelkill.ui.overlay.PatternWarningOverlay
import com.reelkill.ui.overlay.ScheduledBreakOverlay
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.format.DateTimeParseException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: ManagedOverlay? = null

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    fun showHardBlock(
        state: AppState,
        settings: AppSettings,
        onExpired: () -> Unit
    ) {
        runOnMain {
            val expiresAt = state.hardBlockExpires.toInstantOrNull() ?: return@runOnMain
            if (current?.type == OverlayType.HARD_BLOCK) return@runOnMain
            removeCurrentOverlay()

            val overlay = HardBlockOverlay(
                context = context,
                expiresAt = expiresAt,
                reelsWatched = state.reelsWatchedToday,
                dailyLimit = settings.dailyLimit,
                onExpired = onExpired
            )
            addOverlay(OverlayType.HARD_BLOCK, overlay.createView(), overlay::dispose)
        }
    }

    fun showCooldown(
        state: AppState,
        settings: AppSettings,
        nextDurationSeconds: Int,
        onExpired: () -> Unit
    ) {
        runOnMain {
            val expiresAt = state.cooldownExpires.toInstantOrNull() ?: return@runOnMain
            if (current?.type == OverlayType.COOLDOWN) return@runOnMain
            removeCurrentOverlay()

            val overlay = CooldownOverlay(
                context = context,
                expiresAt = expiresAt,
                reelsWatched = state.reelsWatchedToday,
                dailyLimit = settings.dailyLimit,
                cooldownNumber = state.cooldownCountToday,
                nextDurationSeconds = nextDurationSeconds,
                onExpired = onExpired
            )
            addOverlay(OverlayType.COOLDOWN, overlay.createView(), overlay::dispose)
        }
    }

    fun showFriction(state: AppState, settings: AppSettings, onDismiss: () -> Unit) {
        runOnMain {
            if (current != null) return@runOnMain
            val overlay = FrictionModal(
                context = context,
                reelsWatched = state.reelsWatchedToday,
                dailyLimit = settings.dailyLimit,
                onDismiss = onDismiss
            )
            addOverlay(OverlayType.FRICTION, overlay.createView())
        }
    }

    fun showPattern(pattern: DetectedPattern, onDismiss: () -> Unit) {
        runOnMain {
            if (current != null) return@runOnMain
            val overlay = PatternWarningOverlay(context, pattern, onDismiss)
            addOverlay(OverlayType.PATTERN, overlay.createView())
        }
    }

    fun showAntiScroll(onDismiss: () -> Unit) {
        runOnMain {
            if (current != null) return@runOnMain
            val overlay = AntiScrollOverlay(context, onDismiss)
            addOverlay(OverlayType.ANTI_SCROLL, overlay.createView())
        }
    }

    fun showScheduledBreak(
        settings: AppSettings,
        expiresAt: Instant,
        onExpired: () -> Unit
    ) {
        runOnMain {
            if (current?.type == OverlayType.SCHEDULED_BREAK) return@runOnMain
            removeCurrentOverlay()
            val overlay = ScheduledBreakOverlay(
                context = context,
                start = settings.scheduledBreakStart,
                end = settings.scheduledBreakEnd,
                expiresAt = expiresAt,
                onExpired = onExpired
            )
            addOverlay(OverlayType.SCHEDULED_BREAK, overlay.createView(), overlay::dispose)
        }
    }

    fun removeCurrentOverlay() {
        runOnMain {
            val overlay = current ?: return@runOnMain
            runCatching {
                overlay.dispose()
                windowManager.removeView(overlay.view)
            }.onFailure { error ->
                Timber.w(error, "Failed to remove overlay")
            }
            current = null
        }
    }

    private fun addOverlay(type: OverlayType, view: View, dispose: () -> Unit = {}) {
        if (!hasOverlayPermission()) {
            Timber.w("SYSTEM_ALERT_WINDOW not granted; cannot show $type overlay")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching {
            windowManager.addView(view, params)
            current = ManagedOverlay(type, view, dispose)
        }.onFailure { error ->
            Timber.e(error, "Failed to add $type overlay")
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun String?.toInstantOrNull(): Instant? {
        if (isNullOrBlank()) return null
        return try {
            Instant.parse(this)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private data class ManagedOverlay(
        val type: OverlayType,
        val view: View,
        val dispose: () -> Unit
    )

    private enum class OverlayType {
        HARD_BLOCK,
        COOLDOWN,
        FRICTION,
        PATTERN,
        ANTI_SCROLL,
        SCHEDULED_BREAK
    }
}
