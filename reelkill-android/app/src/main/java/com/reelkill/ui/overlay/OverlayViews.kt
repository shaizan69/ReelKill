package com.reelkill.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.time.Duration
import java.time.Instant
import kotlin.math.max

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun baseOverlayRoot(context: Context, dimAmount: Int = 238): FrameLayout {
    return FrameLayout(context).apply {
        setBackgroundColor(Color.argb(dimAmount, 244, 237, 224)) // BgPage #f4ede0
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
}

internal fun cardContainer(context: Context): LinearLayout {
    val background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(4).toFloat() // Match extension border-radius
        setColor(Color.WHITE) // BgSurface #ffffff
        setStroke(context.dp(1), Color.parseColor("#DDD2BB")) // BorderHairline
    }
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(24), context.dp(28), context.dp(24), context.dp(28))
        this.background = background
        elevation = 0f // No box shadows
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            leftMargin = context.dp(24)
            rightMargin = context.dp(24)
        }
    }
}

internal fun titleText(context: Context, text: String): TextView {
    return TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#1F1A13")) // TextPrimary
        textSize = 28f
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        gravity = Gravity.CENTER
    }
}

internal fun bodyText(context: Context, text: String): TextView {
    return TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#6B6258")) // TextSecondary
        textSize = 16f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
    }
}

internal fun countdownText(context: Context): TextView {
    return TextView(context).apply {
        setTextColor(Color.parseColor("#B8763A")) // Accent (Terracotta)
        textSize = 42f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
    }
}

internal fun primaryButton(context: Context, text: String): Button {
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(4).toFloat()
        setColor(Color.parseColor("#B8763A")) // Accent
    }
    return Button(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        background = bg
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
}

internal fun spacer(context: Context, heightDp: Int): View {
    return View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, context.dp(heightDp))
    }
}

internal fun formatRemaining(target: Instant): String {
    val remaining = max(0, Duration.between(Instant.now(), target).seconds)
    val hours = remaining / 3600
    val minutes = (remaining % 3600) / 60
    val seconds = remaining % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

internal fun startCountdown(target: Instant, label: TextView, onFinished: () -> Unit): CountDownTimer {
    val millis = max(0, Duration.between(Instant.now(), target).toMillis())
    label.text = formatRemaining(target)
    return object : CountDownTimer(millis, 1_000L) {
        override fun onTick(millisUntilFinished: Long) {
            label.text = formatRemaining(target)
        }

        override fun onFinish() {
            label.text = "00:00"
            onFinished()
        }
    }.also { it.start() }
}

internal fun progressBar(context: Context, progress: Int, max: Int): ProgressBar {
    return ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        this.max = max.coerceAtLeast(1)
        this.progress = progress.coerceIn(0, this.max)
    }
}
