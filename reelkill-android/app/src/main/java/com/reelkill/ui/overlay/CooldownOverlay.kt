package com.reelkill.ui.overlay

import android.content.Context
import android.os.CountDownTimer
import android.view.View
import java.time.Instant

class CooldownOverlay(
    private val context: Context,
    private val expiresAt: Instant,
    private val reelsWatched: Int,
    private val dailyLimit: Int,
    private val cooldownNumber: Int,
    private val nextDurationSeconds: Int,
    private val onExpired: () -> Unit
) {
    private var timer: CountDownTimer? = null

    fun createView(): View {
        val root = baseOverlayRoot(context)
        val card = cardContainer(context)
        val countdown = countdownText(context)

        card.addView(titleText(context, "Cooldown active"))
        card.addView(spacer(context, 16))
        card.addView(bodyText(context, "Pause here. Waiting does not consume reels."))
        card.addView(spacer(context, 22))
        card.addView(countdown)
        card.addView(spacer(context, 18))
        card.addView(bodyText(context, "$reelsWatched / $dailyLimit reels watched"))
        card.addView(spacer(context, 8))
        card.addView(bodyText(context, "Cooldown #$cooldownNumber today. Next: ${nextDurationSeconds / 60} min"))
        card.addView(spacer(context, 14))
        card.addView(bodyText(context, "No skip or snooze is available."))
        root.addView(card)

        timer = startCountdown(expiresAt, countdown, onExpired)
        return root
    }

    fun dispose() {
        timer?.cancel()
        timer = null
    }
}
