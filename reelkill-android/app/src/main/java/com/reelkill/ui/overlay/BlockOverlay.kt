package com.reelkill.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import timber.log.Timber

/**
 * Extension-style instant badge (mirrors reels-blur.js cover + badge):
 * blurs/pauses ONLY the reel node via accessibility HIDE/BACK actions,
 * shows a small top pill "Reels paused". Rest of the app stays usable.
 * Close hides the badge; enforcement continues via rules until expiry.
 */
class BlockOverlay(
    private val context: Context,
    private val appId: String,
    private val surface: String,
    private val onGoBack: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var showing = false

    fun show() {
        if (showing || view != null) return
        showing = true
        mainHandler.post {
            try {
                val root = reelsBlurBadge(
                    context = context,
                    title = "Reels paused",
                    subtitle = "$surface blurred. App stays usable.",
                    timerText = "",
                    progressText = "Tap Go to leave reels, × to hide",
                    titleColor = Color.parseColor("#F59E0B"),
                    iconText = "⏸",
                    onClose = { dismiss() }
                )
                // Make the pill tap go back (extension cover is pointer-events:auto
                // only on the blurred element). Tapping badge body leaves reels.
                root.setOnClickListener(null)
                val pill = (root as android.widget.FrameLayout).getChildAt(0)
                pill?.setOnClickListener {
                    dismiss()
                    runCatching { onGoBack() }
                        .onFailure { e -> Timber.e(e, "GoBack failed") }
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = context.dp(16)
                    setTitle("ReelKillBlock-$appId")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Subtle blur only behind the pill, not full screen.
                        blurBehindRadius = 20
                    }
                }

                windowManager.addView(root, params)
                view = root
            } catch (error: Exception) {
                showing = false
                Timber.e(error, "Failed to show BlockOverlay for $appId/$surface")
            } catch (error: Error) {
                showing = false
                Timber.e("Failed to show BlockOverlay for $appId/$surface: ${error.message}")
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            showing = false
            view?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: IllegalArgumentException) {
                    // Already removed
                } catch (error: Exception) {
                    Timber.w(error, "Failed to remove BlockOverlay")
                }
                view = null
            }
        }
    }

    fun isShowing(): Boolean = showing && view != null
}
