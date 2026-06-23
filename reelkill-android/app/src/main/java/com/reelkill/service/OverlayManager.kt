package com.reelkill.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.reelkill.data.db.entity.AppSettings
import com.reelkill.data.db.entity.AppState
import com.reelkill.engine.DetectedPattern
import com.reelkill.ui.overlay.CooldownOverlay
import com.reelkill.ui.overlay.FrictionModal
import com.reelkill.ui.overlay.HardBlockOverlay
import com.reelkill.ui.overlay.PatternWarningOverlay
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var current: ManagedOverlay? = null

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    fun showHardBlock(
        state: AppState,
        settings: AppSettings,
        onExpired: () -> Unit
    ) {
        val expiresAt = state.hardBlockExpires?.let { Instant.parse(it) } ?: return
        if (current?.type == OverlayType.HARD_BLOCK) return
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

    fun showCooldown(
        state: AppState,
        settings: AppSettings,
        nextDurationSeconds: Int,
        onExpired: () -> Unit
    ) {
        val expiresAt = state.cooldownExpires?.let { Instant.parse(it) } ?: return
        if (current?.type == OverlayType.COOLDOWN) return
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

    fun showFriction(state: AppState, settings: AppSettings, onDismiss: () -> Unit) {
        if (current != null) return
        val overlay = FrictionModal(
            context = context,
            reelsWatched = state.reelsWatchedToday,
            dailyLimit = settings.dailyLimit,
            onDismiss = onDismiss
        )
        addOverlay(OverlayType.FRICTION, overlay.createView())
    }

    fun showPattern(pattern: DetectedPattern, onDismiss: () -> Unit) {
        if (current != null) return
        val overlay = PatternWarningOverlay(context, pattern, onDismiss)
        addOverlay(OverlayType.PATTERN, overlay.createView())
    }

    fun removeCurrentOverlay() {
        val overlay = current ?: return
        runCatching {
            overlay.dispose()
            windowManager.removeView(overlay.view)
        }.onFailure { error ->
            Timber.w(error, "Failed to remove overlay")
        }
        current = null
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

    private data class ManagedOverlay(
        val type: OverlayType,
        val view: View,
        val dispose: () -> Unit
    )

    private enum class OverlayType {
        HARD_BLOCK,
        COOLDOWN,
        FRICTION,
        PATTERN
    }
}
