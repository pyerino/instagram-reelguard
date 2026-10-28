package dev.pier.reelguard.igpatch.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * FULL-SCREEN enforcement cover: the whole screen becomes the limit notice
 * (user request) -- an opaque near-black surface with the big pause-hand
 * glyph and one line, centered. No buttons, no counters; dismissal is the
 * system back or the surface change, and the cover absorbs touches so the
 * pause holds. Settings live on the tab long-press / adb.
 */
class ActivityOverlay(activity: Activity) {

    private val decor = activity.window.decorView as ViewGroup
    private var root: FrameLayout? = null

    val isShowing: Boolean get() = root?.parent != null

    fun show() {
        val view = root ?: createView().also { root = it }
        if (view.parent == null) {
            decor.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    fun hide() {
        val view = root ?: return
        if (view.parent != null) runCatching { decor.removeView(view) }
    }

    private fun createView(): FrameLayout {
        // IG's runtime theme breaks themed-attr resolution for foreign views;
        // ALWAYS wrap (android.R.style.Theme_DeviceDefault) -- see RemainingHud too.
        val context = android.view.ContextThemeWrapper(decor.context, android.R.style.Theme_DeviceDefault)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }

        column.addView(TextView(context).apply {
            text = PAUSE_HAND
            textSize = 110f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        })

        column.addView(TextView(context).apply {
            text = OVERLAY_LINE
            setTextColor(Palette.TEXT_PRIMARY.toInt())
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })

        return FrameLayout(context).apply {
            // Full-cover surface: a hair above opaque so the reels never read
            // through, far dark enough to kill the video.
            setBackgroundColor(COVER_BACKGROUND)
            isClickable = true
            setOnClickListener { /* absorb; the user exits via back */ }
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER,
                ),
            )
        }
    }

    private companion object {
        const val PAUSE_HAND = "\u270B\uD83C\uDFFB"
        const val OVERLAY_LINE = "Reels \u00B7 daily limit reached"
        val COVER_BACKGROUND: Int = 0xFA141419.toInt()
    }
}
