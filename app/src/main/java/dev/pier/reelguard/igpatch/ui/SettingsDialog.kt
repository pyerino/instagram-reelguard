package dev.pier.reelguard.igpatch.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.ScrollView
import android.widget.FrameLayout
import dev.pier.reelguard.core.Surface
import dev.pier.reelguard.igpatch.ModuleGraph
import dev.pier.reelguard.igpatch.Palette

/**
 * In-Instagram settings: dark programmatic dialog (no theme/resource machinery),
 * writing straight to the same SharedPreferences the engine reads.
 */
class SettingsDialog(
    activity: Activity,
    private val graph: ModuleGraph,
    private val onDismissed: () -> Unit,
) : Dialog(activity) {

    private lateinit var limitLabel: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val themed = android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
        val context = themed
        fun dp(v: Int) = Palette.dp(context, v)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
        }

        content.addView(TextView(context).apply {
            text = Str.SETTINGS_TITLE
            setTextColor(Palette.TEXT_PRIMARY.toInt())
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        content.addView(TextView(context).apply {
            text = Str.SETTINGS_LIMIT_LABEL
            setTextColor(Palette.TEXT_SECONDARY.toInt())
            textSize = 13f
            setPadding(0, dp(14), 0, dp(6))
        })

        val stepper = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        limitLabel = TextView(context).apply {
            setTextColor(Palette.TEXT_PRIMARY.toInt())
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        fun stepperButton(label: String, delta: Int): Button = Button(context).apply {
            text = label
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Palette.BUTTON_SECONDARY_BACKGROUND)
            setTextColor(Palette.TEXT_PRIMARY)
            setOnClickListener {
                // 1-min steps under 5 (user: "before 5 min add 1 min"), 5-min above.
                val current = graph.settings.limitMinutes()
                val step = if (delta > 0) {
                    if (current < FINE_LIMIT) 1 else 5
                } else {
                    if (current <= FINE_LIMIT) 1 else 5
                }
                val next = current + delta * step
                if (next in MIN_LIMIT..MAX_LIMIT) {
                    graph.settings.setLimitMinutes(next)
                    refreshLimitLabel()
                }
            }
        }
        val stepperParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        stepper.addView(stepperButton("\u2212", -1), stepperParams)
        stepper.addView(limitLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stepper.addView(stepperButton("+", 1), stepperParams)
        content.addView(stepper)

        content.addView(TextView(context).apply {
            text = Str.SETTINGS_SURFACES_LABEL
            setTextColor(Palette.TEXT_SECONDARY)
            textSize = 13f
            setPadding(0, dp(18), 0, dp(6))
        } as TextView)

        Surface.entries.forEach { surface ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
                addView(TextView(context).apply {
                    text = surfaceTitle(surface)
                    setTextColor(Palette.TEXT_PRIMARY.toInt())
                    textSize = 15f
                })
                addView(TextView(context).apply {
                    text = surfaceHint(surface)
                    setTextColor(Palette.TEXT_SECONDARY.toInt())
                    textSize = 11f
                })
            }
            row.addView(texts)
            row.addView(Switch(context).apply {
                isChecked = graph.settings.isSurfaceBudgeted(surface)
                setOnCheckedChangeListener { _, checked ->
                    graph.settings.setSurfaceBudgeted(surface, checked)
                }
            })
            content.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }

        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(Button(context).apply {
            text = Str.SETTINGS_RESET
            isAllCaps = false
            setOnClickListener {
                graph.engine.resetToday()
                onDismissed()
            }
        })
        footer.addView(Button(context).apply {
            text = Str.SETTINGS_DONE
            isAllCaps = false
            setOnClickListener { dismiss() }
        })
        content.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        val scroll = ScrollView(context).apply { addView(content) }
        val root = FrameLayout(context).apply { addView(scroll) }
        window?.setBackgroundDrawable(ColorDrawable(Palette.CARD_BACKGROUND.toInt()))
        setContentView(root)
        setOnDismissListener { onDismissed() }
        refreshLimitLabel()
    }

    private fun refreshLimitLabel() {
        limitLabel.text = Str.minutes(graph.settings.limitMinutes())
    }

    private fun surfaceTitle(surface: Surface): String = when (surface) {
        Surface.REELS_TAB -> Str.SURFACE_REELS_TAB_TITLE
        Surface.DM_PLAYER -> Str.SURFACE_DM_PLAYER_TITLE
        Surface.FEED_REEL -> Str.SURFACE_FEED_REEL_TITLE
    }

    private fun surfaceHint(surface: Surface): String = when (surface) {
        Surface.REELS_TAB,
        Surface.DM_PLAYER,
        -> Str.SETTINGS_SURFACE_OVERLAY_HINT

        Surface.FEED_REEL -> Str.SETTINGS_FEED_HINT
    }

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 480
        const val FINE_LIMIT = 5
    }
}
