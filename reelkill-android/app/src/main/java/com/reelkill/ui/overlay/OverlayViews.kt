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

internal fun baseOverlayRoot(context: Context, dimAmount: Int = 140): FrameLayout {
    return FrameLayout(context).apply {
        // Transparent host - never a touch wall. Badge windows are WRAP_CONTENT
        // top-centered; touches everywhere else pass to the app.
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
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

/**
 * Extension-style floating badge (mirrors reels-blur.js):
 * dark pill, icon + title + subtitle + mono timer + progress + close X.
 * Container is touch-transparent; only the pill itself is clickable.
 * Close hides the badge only - enforcement continues until expiry.
 */
internal fun reelsBlurBadge(
    context: Context,
    title: String,
    subtitle: String,
    timerText: String,
    progressText: String,
    titleColor: Int,
    iconText: String,
    onClose: () -> Unit
): View {
    val (root, _) = reelsBlurBadgeLive(
        context, title, subtitle, timerText, progressText, titleColor, iconText, onClose
    )
    return root
}

/**
 * Same badge but exposes the timer TextView for live countdown updates.
 * Returns Pair(view, timerLabel). Caller must cancel its own timer on dispose.
 */
internal fun reelsBlurBadgeLive(
    context: Context,
    title: String,
    subtitle: String,
    timerText: String,
    progressText: String,
    titleColor: Int,
    iconText: String,
    onClose: () -> Unit
): Pair<View, android.widget.TextView> {
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(16).toFloat()
        setColor(Color.parseColor("#EB0F0F0F")) // rgba(15,15,15,0.92)
        setStroke(context.dp(1), Color.parseColor("#1FFFFFFF"))
    }
    val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(20), context.dp(14), context.dp(12), context.dp(14))
        background = bg
        isClickable = true
    }

    val icon = TextView(context).apply {
        text = iconText
        textSize = 22f
        gravity = Gravity.CENTER
        setPadding(0, 0, context.dp(14), 0)
    }

    val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    val titleView = TextView(context).apply {
        text = title.uppercase()
        setTextColor(titleColor)
        textSize = 11f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val subtitleView = TextView(context).apply {
        text = subtitle
        setTextColor(Color.parseColor("#8CFFFFFF"))
        textSize = 11f
    }
    val timerView = TextView(context).apply {
        text = timerText
        setTextColor(titleColor)
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }
    val progressView = TextView(context).apply {
        text = progressText
        setTextColor(Color.parseColor("#73FFFFFF"))
        textSize = 10f
    }
    body.addView(titleView)
    body.addView(subtitleView)
    body.addView(timerView)
    body.addView(progressView)

    val close = TextView(context).apply {
        text = "×"
        setTextColor(Color.parseColor("#66FFFFFF"))
        textSize = 22f
        gravity = Gravity.CENTER
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        isClickable = true
        setOnClickListener { onClose() }
    }

    pill.addView(icon)
    pill.addView(body)
    pill.addView(close)

    // Transparent wrapper so only the pill intercepts touches.
    val root = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        addView(
            pill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = context.dp(16)
            }
        )
    }
    return Pair(root, timerView)
}
