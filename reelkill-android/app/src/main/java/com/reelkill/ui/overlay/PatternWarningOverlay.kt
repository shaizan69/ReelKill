package com.reelkill.ui.overlay

import android.content.Context
import android.view.View
import com.reelkill.engine.DetectedPattern
import com.reelkill.engine.PatternType

class PatternWarningOverlay(
    private val context: Context,
    private val pattern: DetectedPattern,
    private val onDismiss: () -> Unit
) {
    fun createView(): View {
        val root = baseOverlayRoot(context, dimAmount = 190)
        val card = cardContainer(context)
        val button = primaryButton(context, "Okay")
        val message = when (pattern.type) {
            PatternType.RAPID_BINGE -> "You are moving through reels very quickly. Take a short break."
            PatternType.FREQUENT_OPENS -> "Instagram has been opened often today. Check whether this was intentional."
            PatternType.BUDGET_CRITICAL -> "You are close to today's reel limit."
            PatternType.COOLDOWN_CLUSTER -> "Multiple cooldowns fired today. Consider putting the phone down."
            PatternType.LATE_NIGHT -> "Scrolling now can affect sleep quality."
        }

        card.addView(titleText(context, pattern.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }))
        card.addView(spacer(context, 14))
        card.addView(bodyText(context, message))
        card.addView(spacer(context, 20))
        card.addView(button)
        button.setOnClickListener { onDismiss() }
        root.addView(card)
        return root
    }
}
