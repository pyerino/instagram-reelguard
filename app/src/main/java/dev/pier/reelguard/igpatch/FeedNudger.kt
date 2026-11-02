package dev.pier.reelguard.igpatch

import android.app.Activity
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method

/** The most-centered video cell of a feed scan (drives the nudge gates). */
data class CenteredCell(val container: View, val cellRoot: View, val top: Int, val fraction: Float)

/**
 * Feed quiet-scroll nudge (round 8): when the reels budget is exhausted and
 * the feed's centered cell is a video cell while the user is NOT scrolling,
 * dispatch ONE vertical drag inside the feed RecyclerView
 * (android:id/list) so the reel leaves the viewport — the reel is "skipped".
 *
 * Facts honored (recon2): the drag is a view-level dispatch into coordinates
 * inside the RecyclerView (content-host discipline, never decor-wide), dy ≫ dx
 * over 300–400 ms; scroll state comes from reflected
 * RecyclerView.getScrollState() with a visible-top stability fallback; the
 * nudge is NOT assumed to stop playback (single-player unproven) — the skip
 * is the viewport change, verified by the cell's top delta on a later tick.
 */
class FeedNudger(private val policy: NudgePolicy = NudgePolicy { android.os.SystemClock.elapsedRealtime() }) {
    private var scrollStateClass: Class<*>? = null
    private var scrollStateMethod: Method? = null
    private var lastFallbackTop = Int.MIN_VALUE
    private var pendingKey = 0
    private var pendingTop = 0
    private var pendingAt = 0L

    /** Called from the quiet-hours tick with this tick's centered cell. */
    fun tick(activity: Activity?, centered: CenteredCell?) {
        if (activity == null) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val list = findFeedList(decor)
        val reflected = list?.let { scrollIdleReflected(it) }
        val topStable = scrollIdleByTop(centered)
        val idle = reflected ?: topStable
        if (idle == null) {
            gateLog("idle? refl=${reflected != null} topStable=$topStable cell=${centered != null}")
            return // no scroll reading this tick
        }
        policy.onScrollObserved(idle)
        if (!idle) {
            gateLog("scrolling list=${list != null}")
            return
        }

        verifyPending(centered)

        val cell = centered ?: return
        if (cell.fraction <= CENTER_FRACTION) {
            gateLog("fraction f=${cell.fraction}")
            return
        }
        val key = System.identityHashCode(cell.cellRoot)
        if (!policy.shouldNudge(key)) {
            gateLog("busy key=$key f=${cell.fraction}")
            return
        }

        val dragList = list ?: run { gateLog("no list at dispatch"); return }
        val dy = dispatchNudgeDrag(decor, dragList)
        if (dy == null) {
            gateLog("dispatch null")
            return
        }
        policy.onNudged(key)
        pendingKey = key
        pendingTop = cell.top
        pendingAt = now + VERIFY_DELAY_MS
        Log.i(TAG, "feed nudge: dispatched dy=$dy cell=${key.toHex()} top=${cell.top} f=${cell.fraction}")
    }

    /** Exhausted→false transition: fresh slate for the next episode. */
    fun onBudgetRestored() {
        policy.onBudgetRestored()
        pendingKey = 0
    }

    // --- scroll state ------------------------------------------------------

    /** Reflected RecyclerView.getScrollState()==IDLE; null when unavailable. */
    private fun scrollIdleReflected(list: View): Boolean? {
        val clazz = list.javaClass
        if (scrollStateClass !== clazz) {
            scrollStateClass = clazz
            scrollStateMethod = runCatching { clazz.getMethod("getScrollState") }.getOrNull()
        }
        val method = scrollStateMethod ?: return null
        val state = runInt(method, list) ?: return null
        return state == SCROLL_IDLE
    }

    /** Compile-safe fallback: centered top unchanged since the last tick. */
    private fun scrollIdleByTop(centered: CenteredCell?): Boolean? {
        val top = centered?.top
        if (top == null) {
            lastFallbackTop = Int.MIN_VALUE
            return null
        }
        val idle = lastFallbackTop != Int.MIN_VALUE && top == lastFallbackTop
        lastFallbackTop = top
        return idle
    }

    // --nudge ---------------------------------------------------------------

    /** Drag inside the list's own rect; returns dy or null when nothing dispatched. */
    private fun dispatchNudgeDrag(decor: ViewGroup, list: View): Int? {
        val content = decor.findViewById<View>(android.R.id.content)
        if (content == null || content.width <= 0) return null
        val listRect = Rect()
        if (!list.getGlobalVisibleRect(listRect) || listRect.isEmpty) return null
        val travel = (content.resources.displayMetrics.heightPixels * TRAVEL_FRACTION).toInt()

        val hostLoc = IntArray(2)
        content.getLocationOnScreen(hostLoc)
        val x = (listRect.centerX() - hostLoc[0]).coerceIn(1, content.width - 1)
        val travelClamped = travel.coerceAtMost(listRect.height() - 2 * EDGE_MARGIN_PX)
        val startY = (listRect.exactCenterY().toInt() + travelClamped / 2)
            .coerceIn(listRect.top + EDGE_MARGIN_PX, listRect.bottom - EDGE_MARGIN_PX)
        val endY = (startY - travelClamped)
            .coerceIn(listRect.top + EDGE_MARGIN_PX, listRect.bottom - EDGE_MARGIN_PX)
        if (startY - endY < MIN_TRAVEL_PX) return null
        val yDown = startY - hostLoc[1]
        val yUp = endY - hostLoc[1]

        val downTime = now
        var consumed = 0
        val events = ArrayList<MotionEvent>(DRAG_STEPS + 2)
        fun event(atMs: Long, action: Int, y: Int): MotionEvent =
            MotionEvent.obtain(downTime, downTime + atMs, action, x.toFloat(), y.toFloat(), 0)

        runCatching {
            events += event(0, MotionEvent.ACTION_DOWN, yDown)
            repeat(DRAG_STEPS) { step ->
                val at = (step + 1) * DRAG_TOTAL_MS / (DRAG_STEPS + 1)
                val y = yDown + (yUp - yDown) * (step + 1) / (DRAG_STEPS + 1)
                events += event(at, MotionEvent.ACTION_MOVE, y)
            }
            events += event(DRAG_TOTAL_MS, MotionEvent.ACTION_UP, yUp)
            for (motion in events) if (content.dispatchTouchEvent(motion)) consumed++
        }
        for (motion in events) motion.recycle()
        return if (consumed > 0) startY - endY else null
    }

    /** Cell-top delta of the nudged cell, logged once, VERIFY_DELAY_MS later. */
    private fun verifyPending(centered: CenteredCell?) {
        if (pendingKey == 0) return
        if (now < pendingAt) return
        val key = centered?.let { System.identityHashCode(it.cellRoot) } ?: 0
        if (key == pendingKey) {
            val delta = kotlin.math.abs(centered!!.top - pendingTop)
            val verdict = if (delta >= MOVE_THRESHOLD_PX) "moved" else "no-move?"
            Log.i(TAG, "feed nudge: $verdict Δtop=$delta")
        } else {
            Log.i(TAG, "feed nudge: cell replaced — skipped on")
        }
        pendingKey = 0
    }

    // --- host lookup -------------------------------------------------------

    private val now: Long get() = android.os.SystemClock.elapsedRealtime()

    /** Widest visible RecyclerView under id android.R.id.list (recon2 §3). */
    private fun findFeedList(decor: ViewGroup): View? {
        val target = android.R.id.list
        var best: View? = null
        var bestHeight = 0
        val queue = ArrayDeque<View>()
        queue.add(decor)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            seen++
            if (node.id == target && node.javaClass.simpleName.contains("RecyclerView")) {
                val rect = Rect()
                if (node.getGlobalVisibleRect(rect) && rect.height() > bestHeight) {
                    bestHeight = rect.height()
                    best = node
                }
            }
            if (node is ViewGroup) for (index in 0 until node.childCount) {
                node.getChildAt(index)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun runInt(method: Method, target: Any): Int? =
        runCatching { method.invoke(target) as? Int }.getOrNull()

    /** One-line gate telemetry; throttled hard to keep the log clean. */
    private var lastGateLog = 0L
    private fun gateLog(detail: String) {
        val nowMs = now
        if (nowMs - lastGateLog < 5000) return
        lastGateLog = nowMs
        Log.d(TAG, "feed nudge: $detail")
    }

    private fun Int.toHex() = Integer.toHexString(this)

    private companion object {
        const val TAG = "ReelGuardPatch"
        const val SCROLL_IDLE = 0
        const val CENTER_FRACTION = 0.75f
        const val TRAVEL_FRACTION = 0.62f
        const val DRAG_TOTAL_MS = 350L
        const val DRAG_STEPS = 8
        const val EDGE_MARGIN_PX = 120
        const val MIN_TRAVEL_PX = 600
        const val MAX_SCAN_NODES = 400
        const val VERIFY_DELAY_MS = 900L
        const val MOVE_THRESHOLD_PX = 40
    }
}
