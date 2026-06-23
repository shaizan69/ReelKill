package com.reelkill.ui.overlay

import android.content.Context
import android.view.View

class FrictionModal(
    private val context: Context,
    private val reelsWatched: Int,
    private val dailyLimit: Int,
    private val onDismiss: () -> Unit
) {
    fun createView(): View {
        val root = baseOverlayRoot(context, dimAmount = 180)
        val card = cardContainer(context)
        val button = primaryButton(context, "I understand, continue")

        card.addView(titleText(context, "Heads up"))
        card.addView(spacer(context, 14))
        card.addView(bodyText(context, "You've watched $reelsWatched / $dailyLimit reels today."))
        card.addView(spacer(context, 14))
        card.addView(progressBar(context, reelsWatched, dailyLimit))
        card.addView(spacer(context, 14))
        card.addView(bodyText(context, "This warning won't appear again this session."))
        card.addView(spacer(context, 20))
        card.addView(button)
        button.setOnClickListener { onDismiss() }
        root.addView(card)
        return root
    }
}
