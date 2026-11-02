package dev.pier.reelguard.igpatch.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.pier.reelguard.core.ReelCounter

/**
 * Live remaining-budget pill, top-right of the decor: dark rounded plate, 2dp
 * white stroke, bold `mm:ss` (with a quiet "left" lead) plus today's reel
 * count. Visible only while a budgeted overlay-capable surface is up, screen
 * on, and no enforcement card.
 */
class RemainingHud(private val activity: Activity) {

    private val decor = activity.window.decorView as ViewGroup
    private var view: TextView? = null

    val isShowing: Boolean get() = view?.parent != null

    fun show() {
        val view = this.view ?: create().also { this.view = it }
        if (view.parent == null) {
            val density = decor.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            decor.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    topMargin = dp(110)
                    marginEnd = dp(12)
                },
            )
        }
    }

    fun update(remainingSeconds: Int, reelsToday: Int = 0) {
        val view = this.view ?: return
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val reels = ReelCounter.displayCount(reelsToday)
        view.text = Str.hudRemaining(minutes, seconds, reels)
        view.contentDescription = Str.hudRemainingA11y(minutes, seconds, reels)
    }

    fun hide() {
        val view = this.view ?: return
        if (view.parent != null) runCatching { decor.removeView(view) }
    }

    private fun create(): TextView {
        val context = android.view.ContextThemeWrapper(decor.context, android.R.style.Theme_DeviceDefault)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        return TextView(context).apply {
            background = GradientDrawable().apply {
                setColor(HUD_BACKGROUND)
                cornerRadius = 14f * density
                setStroke(dp(2), HUD_STROKE)
            }
            setTextColor(HUD_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            text = Str.hudRemaining(0, 0, "0")
        }
    }

    private companion object {
        val HUD_BACKGROUND = 0xD9111214.toInt()
        val HUD_STROKE = 0xFFFFFFFF.toInt()
        val HUD_TEXT = 0xFFFFFFFF.toInt()
    }
}
