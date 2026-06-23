package com.reelkill.ui.overlay

import android.content.Context
import android.os.CountDownTimer
import android.view.View
import java.time.Instant

class HardBlockOverlay(
    private val context: Context,
    private val expiresAt: Instant,
    private val reelsWatched: Int,
    private val dailyLimit: Int,
    private val onExpired: () -> Unit
) {
    private var timer: CountDownTimer? = null

    fun createView(): View {
        val root = baseOverlayRoot(context)
        val card = cardContainer(context)
        val countdown = countdownText(context)

        card.addView(titleText(context, "Daily limit reached"))
        card.addView(spacer(context, 16))
        card.addView(bodyText(context, "You used today's reel budget. Come back when the timer ends."))
        card.addView(spacer(context, 22))
        card.addView(countdown)
        card.addView(spacer(context, 18))
        card.addView(bodyText(context, "$reelsWatched / $dailyLimit reels watched"))
        card.addView(spacer(context, 14))
        card.addView(bodyText(context, "No override is available during a hard block."))
        root.addView(card)

        timer = startCountdown(expiresAt, countdown, onExpired)
        return root
    }

    fun dispose() {
        timer?.cancel()
        timer = null
    }
}
