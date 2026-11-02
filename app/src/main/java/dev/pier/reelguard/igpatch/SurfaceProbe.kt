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
        val reelsPagerIndex: Int? = null,
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
        var pagerSeen: View? = null
        var pagerNamed: View? = null

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
                            // The reels pager by its rules-listed id (recon analysis3:
                            // it is a ViewPager v1 — class checks here would miss it;
                            // the id is the stable signal, the class only disambiguates).
                            if (name.substringAfterLast('/') == PAGER_ID_HINT) {
                                pagerNamed = view
                            }
                        }
                    }
                    if (videoView == null && VideoControl.isReelVideoView(view)) videoView = view
                    if (pagerSeen == null && isReelsPager(view)) pagerSeen = view
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
        val reelsPagerIndex = (pagerNamed ?: pagerSeen)?.let { pagerIndexOf(it) }
        return ScanResult(surface, playing, videoView, pauseButton, present, reelsPagerIndex)
    }

    // Recon analysis3: clips_viewer_view_pager is ViewPager **v1** whose single
    // child is a full-size RecyclerView — the real page lane; pager.getCurrentItem()
    // is always 0. The index comes from the child layout manager via reflection
    // (module is android.jar-only). A ViewPager2 index stays the fallback.
    private const val PAGER_CLASS = "androidx.viewpager2.widget.ViewPager2"

    /** Id hint shared by the reels_tab/dm_player rules; see scan() above. */
    private const val PAGER_ID_HINT = "clips_viewer_view_pager"

    private var pagerClass: Class<*>? = null
    private var pagerGetCurrentItem: java.lang.reflect.Method? = null
    private var pagerResolveTried = false
    private var lmPositionMethods: Map<String, java.lang.reflect.Method>? = null
    private var lmClass: Class<*>? = null

    private fun isReelsPager(view: View): Boolean {
        ensurePagerReflection()
        return pagerClass?.isInstance(view) == true
    }

    private fun pagerIndexOf(view: View): Int? = firstVisibleRvPosition(view)
        ?: pagerGetCurrentItem?.let { runCatching { it.invoke(view) as Int }.getOrNull() }

    /** v1-pager recipe: single child RecyclerView -> layout manager position. */
    private fun firstVisibleRvPosition(pager: View): Int? {
        if (pager !is ViewGroup || pager.childCount != 1) return null
        val rv = pager.getChildAt(0) ?: return null
        val lm = runCatching {
            rv.javaClass.getMethod("getLayoutManager").invoke(rv)
        }.getOrNull() ?: return null
        if (lm.javaClass != lmClass) {
            lmClass = lm.javaClass
            lmPositionMethods = lm.javaClass.methods
                .filter { it.parameterTypes.isEmpty() }
                .associateBy { it.name }
                .let { methods ->
                    listOf("findFirstCompletelyVisibleItemPosition", "findFirstVisibleItemPosition")
                        .mapNotNull { methods[it] }
                        .associateBy { it.name }
                }
        }
        for ((_, method) in lmPositionMethods ?: return null) {
            val position = runCatching { method.invoke(lm) as? Int }.getOrNull() ?: continue
            if (position >= 0) return position
        }
        return null
    }

    private fun ensurePagerReflection() {
        if (pagerResolveTried) return
        pagerResolveTried = true
        val clazz = runCatching { Class.forName(PAGER_CLASS) }.getOrNull() ?: return
        pagerClass = clazz
        pagerGetCurrentItem = runCatching { clazz.getMethod("getCurrentItem") }.getOrNull()
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
