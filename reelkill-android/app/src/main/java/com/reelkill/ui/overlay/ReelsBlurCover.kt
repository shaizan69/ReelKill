package com.reelkill.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import timber.log.Timber

/**
 * Extension-equivalent blur cover (mirrors reels-blur.js COVER_CLASS):
 * a small window positioned EXACTLY over the matched reel node rect -
 * dark rgba(0,0,0,0.55) + "Reels paused". It consumes touches ONLY inside
 * the reels rect. Chat, nav, photos outside the rect stay fully usable.
 * The app is never closed or backed out of.
 */
class ReelsBlurCover(
    private val context: Context,
    private val appId: String
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var lastRect: Rect? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showOrMove(bounds: Rect) {
        // Ignore tiny/hidden nodes and no-op moves.
        if (bounds.width() < 50 || bounds.height() < 50) return
        if (bounds == lastRect && view != null) return
        lastRect = Rect(bounds)
        mainHandler.post {
            try {
                if (view == null) {
                    val cover = buildView()
                    windowManager.addView(cover, paramsFor(bounds))
                    view = cover
                } else {
                    windowManager.updateViewLayout(view, paramsFor(bounds))
                }
            } catch (error: Exception) {
                Timber.e(error, "Failed to show blur cover for $appId")
            } catch (error: Error) {
                Timber.e("Failed to show blur cover for $appId: ${error.message}")
            }
        }
    }

    fun dismiss() {
        lastRect = null
        mainHandler.post {
            view?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: IllegalArgumentException) {
                    // Already removed
                } catch (error: Exception) {
                    Timber.w(error, "Failed to remove blur cover")
                }
                view = null
            }
        }
    }

    fun isShowing(): Boolean = view != null

    private fun paramsFor(bounds: Rect): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Consume touches inside the reels rect only; everything outside
            // this small window passes to the app automatically.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            setTitle("ReelKillBlur-$appId")
        }
    }

    private fun buildView(): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        return FrameLayout(context).apply {
            // Extension cover: rgba(0,0,0,0.55). Clickable so reels taps are
            // swallowed (pointer-events:auto on cover, none on blurred node).
            setBackgroundColor(Color.parseColor("#8C000000"))
            isClickable = true
            isFocusable = false

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val icon = TextView(context).apply {
                text = "⏸"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            val label = TextView(context).apply {
                text = "REELS PAUSED"
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            col.addView(icon)
            col.addView(label)
            addView(
                col,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply {
                    setMargins(dp(12), dp(12), dp(12), dp(12))
                }
            )
        }
    }
}
