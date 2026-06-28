package com.reelkill.ui.overlay

import android.content.Context
import android.os.CountDownTimer
import android.view.View
import java.time.Instant

class ScheduledBreakOverlay(
    private val context: Context,
    private val start: String?,
    private val end: String?,
    private val expiresAt: Instant,
    private val onExpired: () -> Unit
) {
    private var timer: CountDownTimer? = null

    fun createView(): View {
        val root = baseOverlayRoot(context)
        val card = cardContainer(context)
        val countdown = countdownText(context)
        val breakWindow = listOfNotNull(start, end).joinToString(" - ")

        card.addView(titleText(context, "Scheduled break"))
        card.addView(spacer(context, 16))
        card.addView(bodyText(context, "This app is blocked during your break window."))
        if (breakWindow.isNotBlank()) {
            card.addView(spacer(context, 10))
            card.addView(bodyText(context, breakWindow))
        }
        card.addView(spacer(context, 22))
        card.addView(countdown)
        card.addView(spacer(context, 14))
        card.addView(bodyText(context, "Access resumes when the timer ends."))
        root.addView(card)

        timer = startCountdown(expiresAt, countdown, onExpired)
        return root
    }

    fun dispose() {
        timer?.cancel()
        timer = null
    }
}
