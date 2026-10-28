package dev.pier.reelguard.igpatch

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import java.util.ArrayDeque
import java.util.WeakHashMap
import dev.pier.reelguard.core.DetectionRules
import dev.pier.reelguard.core.Surface

/**
 * In-process surface detection: a breadth-first walk of the resumed activity's
 * decor collecting which rule view-ids are present (same rules and
 * pause-marker semantics as the companion app), plus the handles the view
 * control path needs (reels video surface, visible pause button).
 *
 * Only VISIBLE, non-fully-clipped views count: IG keeps tab fragments (the
 * reels pager, the feed) attached in the same decor after navigation, and
 * their leftover ids would otherwise win the verdict on the wrong screen
 * (observed: back from reels leaves the pager page mounted -> modal follows
 * onto the feed). Fully-clipped = empty globalVisibleRect.
 */
object SurfaceProbe {

    class ScanResult(
        val surface: Surface?,
        val playing: Boolean?,
        val videoView: View?,
        val pauseButton: View?,
        val presentIds: Set<String> = emptySet(),
    )

    private class ActivityIds(val idToName: Map<Int, String>)

    private val cache = WeakHashMap<Activity, ActivityIds>()
    private var markerNames: Set<String>? = null
    private val globalRect = android.graphics.Rect()

    private fun effectivelyVisible(view: View): Boolean =
        view.isShown && view.visibility == View.VISIBLE &&
            view.getGlobalVisibleRect(globalRect)

    fun scan(activity: Activity, rules: DetectionRules): ScanResult {
        val ids = cache[activity] ?: buildIds(activity, rules).also { cache[activity] = it }
        val markers = markerNames ?: rulesMarkerNames(rules).also { markerNames = it }
        val root = activity.window?.decorView

        val present = HashSet<String>()
        var videoView: View? = null
        var pauseButton: View? = null

        if (root != null) {
            val queue = ArrayDeque<View>()
            if (effectivelyVisible(root)) queue.add(root)
            while (queue.isNotEmpty()) {
                val view: View = queue.poll()
                val visible = effectivelyVisible(view)
                if (visible) {
                    view.id.takeIf { it != View.NO_ID }?.let { id ->
                        ids.idToName[id]?.let { name ->
                            present.add(name)
                            if (pauseButton == null && name in markers) pauseButton = view
                        }
                    }
                    if (videoView == null && VideoControl.isReelVideoView(view)) videoView = view
                }
                if (view is ViewGroup && (visible || view === root)) {
                    for (index in 0 until view.childCount) {
                        val child = view.getChildAt(index) ?: continue
                        if (child.visibility == View.GONE) continue
                        queue.add(child)
                    }
                }
            }
        }

        val surface = rules.detect(present)
        val playing = surface?.let { rules.playingFrom(it, present) }
        return ScanResult(surface, playing, videoView, pauseButton, present)
    }

    private fun rulesMarkerNames(rules: DetectionRules): Set<String> = rules.orderedRules()
        .mapNotNull { (_, rule) -> rule.pausedWhenIdPresent }
        .toSet()

    private fun buildIds(activity: Activity, rules: DetectionRules): ActivityIds {
        val resources = activity.resources
        val packageName = activity.packageName
        val idToName = HashMap<Int, String>()
        for (name in rules.referencedViewIds()) {
            val bare = name.substringAfterLast('/')
            val id = resources.getIdentifier(bare, "id", packageName)
            if (id != 0) idToName[id] = name
        }
        return ActivityIds(idToName)
    }
}
