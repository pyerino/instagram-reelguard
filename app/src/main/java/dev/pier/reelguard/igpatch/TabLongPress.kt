package dev.pier.reelguard.igpatch

import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * User-requested discovery gesture: LONG-PRESS on the bottom-bar Reels tab
 * opens the settings dialog. The listener attaches lazily during the 1 Hz
 * scan, once per view instance (IG recreates the tab bar on navigation).
 * The cell is identified by its label text (contains "Reel", any locale form
 * seen so far) with an index fallback (v451..453 order: Casa, Reels, Direct,
 * Cerca, profile) — the label-less icon-only layout relies on the fallback.
 * Long-press coexists with the normal tap: only the listener is added.
 */
object TabLongPress {
    private val attached = WeakHashMap<View, Boolean>()

    fun maybeAttach(activity: Activity, onTrigger: () -> Unit) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val bar = findViewByIdName(decor, "tab_bar") as? ViewGroup ?: return
        val cell = reelsCell(bar) ?: return
        if (attached[cell] == true) return
        attached[cell] = true
        cell.setOnLongClickListener {
            cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onTrigger()
            true
        }
    }

    private fun findViewByIdName(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id != 0) root.findViewById(id) else null
    }

    private fun reelsCell(bar: ViewGroup): View? {
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            val label = findLabel(child) ?: continue
            if (label.contains("Reel", ignoreCase = true)) return child
        }
        return if (bar.childCount > 1) bar.getChildAt(1) else null
    }

    private fun findLabel(v: View): String? {
        if (v is android.widget.TextView) return v.text?.toString()
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findLabel(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
