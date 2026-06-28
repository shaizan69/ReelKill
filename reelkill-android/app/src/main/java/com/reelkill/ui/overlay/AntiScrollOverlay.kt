package com.reelkill.ui.overlay

import android.content.Context
import android.view.View

class AntiScrollOverlay(
    private val context: Context,
    private val onDismiss: () -> Unit
) {
    fun createView(): View {
        val root = baseOverlayRoot(context, dimAmount = 180)
        val card = cardContainer(context)
        val button = primaryButton(context, "Slow down")

        card.addView(titleText(context, "Fast scrolling detected"))
        card.addView(spacer(context, 14))
        card.addView(bodyText(context, "You are moving through the feed quickly. Pause before continuing."))
        card.addView(spacer(context, 20))
        card.addView(button)
        button.setOnClickListener { onDismiss() }
        root.addView(card)
        return root
    }
}
