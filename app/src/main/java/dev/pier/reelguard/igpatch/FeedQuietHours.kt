package dev.pier.reelguard.igpatch

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.pier.reelguard.igpatch.ui.Palette
import dev.pier.reelguard.igpatch.ui.Str
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Feed quiet-hours (recon analysis3 + lead rulings): when the REELS_TAB
 * budget is exhausted and a reel-post is visible on the HOME FEED,
 *
 *  1. pause the cell's autoplay ONCE per cell-view instance. Feed taps
 *     navigate to the reels viewer, so the pause is programmatic only:
 *     reflective no-arg candidates on the cell's views, cached per class.
 *     Cells recycle and any rebind restarts playback (indistinguishable
 *     from manual play), so a re-pause after a rebind is accepted — the
 *     pause is best-effort; the ribbon carries the UX.
 *  2. pin a slim NON-INTERACTIVE ribbon above the bottom tab bar
 *     (`✋ Reels limit reached`) — no cover, no click interception; the
 *     feed scrolls freely. Never on reels-tab/DM (the full cover runs
 *     there).
 *
 * Feed pause state has no marker id — verification is external (frame diff).
 */
class FeedQuietHours {

    /** On since the recon; flip off only if device verify disproves it. */
    var enabled: Boolean = true

    /**
     * Quiet-scroll nudge: user preference (2026-11-02) is pause-first, cover
     * as fallback — NOT scrolling. Machinery kept + tested; flip to enable.
     */
    var nudgeEnabled: Boolean = false

    /** Cell root → pause attempted. Weak: recycled cells vanish with views. */
    private val attemptedCells = WeakHashMap<View, Boolean>()

    /** No-arg pause candidates per concrete class; resolved once. */
    private val pauseMethods = HashMap<String, List<Method>>()

    private var ribbon: TextView? = null
    private var ribbonParent: ViewGroup? = null
    private var cover: ViewGroup? = null
    private var coverParent: ViewGroup? = null
    private var videoContainerId: Int = 0
    private var tabBarId: Int = 0
    private val lastLogByKey = HashMap<String, Long>()
    private var lastQuietReassertElapsed = 0L
    private var streamVolAtCover = -1
    private var streamVolContext: android.content.Context? = null
    private var lastStreamPinAttempt = 0L
    private val nudger = FeedNudger()
    private var wasExhausted = false

    /** Called from the enforcement tick; host-safe, main thread. */
    fun tick(activity: Activity?, feedReelVisible: Boolean, reelsExhausted: Boolean) {
        if (!reelsExhausted && wasExhausted) nudger.onBudgetRestored()
        wasExhausted = reelsExhausted
        val decor = activity?.window?.decorView as? ViewGroup
        if (!enabled || !reelsExhausted || !feedReelVisible || decor == null) {
            removeRibbon()
            removeCover()
            return
        }
        val centered = scanFeedCells(decor)
        showRibbon(decor)
        // Cover always on the CENTERED cell while exhausted (>=0.75 of itself
        // visible, mirroring the nudge gate): hides while scrolling so it
        // never swims over half-passed cells. The pause walk is best-effort
        // (hollow successes are unverifiable in-proc); the opaque ✋ is the
        // deterministic quieting the user asked for.
        centered?.takeIf { it.fraction >= 0.75f }
            ?.let {
                showCover(decor, it.container)
                maybeReassertQuiet(it.container)
            } ?: removeCover()
        if (nudgeEnabled) nudger.tick(activity, centered)
    }

    // --- pause -------------------------------------------------------------

    /**
     * Single decor walk: best-effort pause per cell instance, and the
     * most-centered video container returned for the nudge gates.
     */
    private fun scanFeedCells(decor: ViewGroup): CenteredCell? {
        val containerId = resolveVideoContainerId(decor) ?: return null
        var best: CenteredCell? = null
        forEachVisible(decor) { node ->
            if (node.id != containerId) return@forEachVisible
            val cellRoot = cellRootOf(node) ?: return@forEachVisible
            if (attemptedCells.put(cellRoot, true) == null) attemptPause(cellRoot)
            val bounds = android.graphics.Rect()
            node.getGlobalVisibleRect(bounds)
            if (node.height <= 0) return@forEachVisible
            val fraction = bounds.height().toFloat() / node.height
            if (best == null || fraction > best!!.fraction) {
                best = CenteredCell(node, cellRoot, bounds.top, fraction)
            }
        }
        return best
    }

    private fun attemptPause(cellRoot: View): Boolean {
        // Bounded walk from the cell root; EVERY candidate fires (the first
        // hit proved hollow: ObjectAnimator.pause on a spinner while the video
        // kept moving), so the player controller is caught too if present.
        val queue = ArrayDeque<View>()
        queue.add(cellRoot)
        val visited = ArrayList<View>()
        val visitedClasses = LinkedHashSet<String>()
        var seen = 0
        var hits = 0
        while (queue.isNotEmpty() && seen < MAX_PAUSE_WALK) {
            val node = queue.removeFirst()
            seen++
            visited.add(node)
            visitedClasses.add(node.javaClass.simpleName)
            hits += invokeQuiet(node, null)
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) node.getChildAt(index)?.let { queue.add(it) }
            }
        }
        // Pass 2: the player's controller is not the view itself — it lives in
        // a field (or tag) of one of them. One level deep, name-filtered.
        val depth2Classes = LinkedHashSet<String>()
        var census = 0
        for (node in visited) {
            val holder = fieldObjectsOf(node) ?: continue
            for ((label, obj) in holder) {
                if (obj is View) continue // pass 1 covered the view tree
                census++
                depth2Classes.add(obj.javaClass.simpleName)
                hits += invokeQuiet(obj, "${node.javaClass.simpleName}.${label}")
                // Pass 2b: one more level — the player manager sits behind the
                // controller object. Identity-capped, depth-limited.
                if (obj.javaClass.name.startsWith("com.instagram") ||
                    obj.javaClass.name.startsWith("X.")
                ) {
                    depth2Classes.add(obj.javaClass.simpleName)
                    val inner = fieldObjectsOf2(obj) ?: continue
                    for ((label2, obj2) in inner) {
                        if (obj2 is View) continue
                        hits += invokeQuiet(obj2, "${node.javaClass.simpleName}.${label}.${label2}")
                    }
                }
            }
        }
        if (depth2Classes.isNotEmpty()) {
            throttledLog("feed quiet: field census $census ${depth2Classes.take(12).joinToString(",")}")
        }
        if (hits == 0) {
            throttledLog(
                "feed quiet: no pause on ${cellRoot.javaClass.simpleName} " +
                    "($seen nodes: ${visitedClasses.take(10).joinToString(",")})",
            )
        }
        return hits > 0
    }

    /** No-arg pause/mute-named methods, then boolean setters, then volume. */
    private fun invokeQuiet(target: Any, via: String?): Int {
        val owner = target.javaClass.simpleName
        var hits = 0
        for (method in quietCandidatesFor(target.javaClass)) {
            if (runCatching { method.invoke(target) }.isSuccess) {
                hits++
                throttledLog("feed quiet: pause via $owner.${method.name}()${via?.let { " (field $it)" } ?: ""}")
            }
        }
        for ((name, arg) in BOOLEAN_QUIET_ARGS) {
            val method = runCatching { target.javaClass.getMethod(name, Boolean::class.java) }
                .getOrNull() ?: continue
            if (runCatching { method.invoke(target, arg) }.isSuccess) {
                hits++
                throttledLog("feed quiet: mute via $owner.$name($arg)${via?.let { " (field $it)" } ?: ""}")
            }
        }
        // Player-style stereo volume: the mute path of last resort.
        runCatching {
            target.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        }.getOrNull()?.let { method ->
            if (runCatching { method.invoke(target, 0f, 0f) }.isSuccess) {
                hits++
                throttledLog("feed quiet: mute via $owner.setVolume(0.0,0.0)${via?.let { " (field $it)" } ?: ""}")
            }
        }
        // ExoPlayer-style single-float volume.
        runCatching {
            target.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType)
        }.getOrNull()?.let { method ->
            if (runCatching { method.invoke(target, 0f) }.isSuccess) {
                hits++
                throttledLog("feed quiet: mute via $owner.setVolume(0.0)${via?.let { " (field $it)" } ?: ""}")
            }
        }
        return hits
    }

    /** Public-field values + tag, capped; the pause controller hides here. */
    private fun fieldObjectsOf(node: View): List<Pair<String, Any>>? {
        val found = ArrayList<Pair<String, Any>>()
        node.tag?.let { found.add("tag" to it) }
        // declaredFields, not fields: IG's obfuscated holders keep everything
        // private; the player controller is invisible to the public view.
        val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: return null
        var taken = 0
        for (field in fields) {
            if (taken >= MAX_FIELDS_PER_NODE) break
            if (Modifier.isStatic(field.modifiers)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(node)
            }.getOrNull() ?: continue
            if (value is View || value is Context || value is Resources || value is String) continue
            taken++
            found.add(field.name to value)
        }
        return found
    }

    private fun quietCandidatesFor(clazz: Class<*>): List<Method> {
        pauseMethods[clazz.name]?.let { return it }
        val found = runCatching {
            clazz.methods.filter { it.parameterTypes.isEmpty() && it.name in PAUSE_METHOD_NAMES }
        }.getOrNull().orEmpty()
        pauseMethods[clazz.name] = found
        return found
    }

    /** Depth-2 probe: declared fields of a non-view object, capped, skip views. */
    private fun fieldObjectsOf2(host: Any): List<Pair<String, Any>>? {
        val fields = runCatching { host.javaClass.declaredFields }.getOrNull() ?: return null
        val found = ArrayList<Pair<String, Any>>()
        var taken = 0
        for (field in fields) {
            if (taken >= MAX_FIELDS_PER_NODE) break
            if (Modifier.isStatic(field.modifiers)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(host)
            }.getOrNull() ?: continue
            if (value is View || value is Context || value is Resources || value is String) continue
            if (value !is android.view.accessibility.AccessibilityNodeInfo && value === host) continue
            taken++
            found.add(field.name to value)
        }
        return found.ifEmpty { null }
    }

    /** Lowest ancestor whose parent is a RecyclerView = the cell root. */
    private fun cellRootOf(view: View): View? {
        var node: View = view
        repeat(CELL_WALK_DEPTH) {
            val parent = node.parent as? View ?: return null
            if (parent.javaClass.name.contains("RecyclerView")) return node
            node = parent
        }
        return node
    }

    private fun forEachVisible(root: View, action: (View) -> Unit) {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            seen++
            val bounds = android.graphics.Rect()
            node.getGlobalVisibleRect(bounds)
            if (node.isShown && node.visibility == View.VISIBLE && !bounds.isEmpty) {
                action(node)
            }
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) node.getChildAt(index)?.let { queue.add(it) }
            }
        }
    }

    private fun throttledLog(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Per-message-key gap: a global slot swallowed every second line
        // (mute/census died in the pause line's 5 s shadow).
        val key = message.substringBefore('(').substringBefore(" on ")
        synchronized(lastLogByKey) {
            val last = lastLogByKey[key]
            if (last != null && now - last < PAUSE_LOG_GAP_MS) return
            lastLogByKey[key] = now
            if (lastLogByKey.size > 32) lastLogByKey.clear()
        }
        Log.i(TAG, message)
    }

    // --- ids ---------------------------------------------------------------

    private fun resolveVideoContainerId(decor: ViewGroup): Int? {
        if (videoContainerId != 0) return videoContainerId
        val id = runCatching {
            decor.resources.getIdentifier("video_container", "id", "com.instagram.android")
        }.getOrDefault(0)
        return if (id != 0) id.also { videoContainerId = it } else null
    }

    // --- ribbon ------------------------------------------------------------

    private fun showRibbon(decor: ViewGroup) {
        val existing = ribbon
        if (existing != null && existing.parent === decor) {
            existing.visibility = View.VISIBLE
            return
        }
        removeRibbon()

        val ctx = android.view.ContextThemeWrapper(decor.context, android.R.style.Theme_DeviceDefault)
        val density = decor.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val view = TextView(ctx).apply {
            text = Str.FEED_QUIET_RIBBON
            setTextColor(Palette.TEXT_PRIMARY.toInt())
            textSize = 12f
            isClickable = false
            isFocusable = false
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                setColor(Palette.CARD_BACKGROUND.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Palette.TEXT_SECONDARY.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = marginAboveTabBar(decor, ::dp)
            }
            elevation = dp(6).toFloat()
        }
        runCatching { decor.addView(view) }.onFailure { return }
        ribbon = view
        ribbonParent = decor
        Log.i(TAG, "feed quiet: ribbon shown")
    }

    /** Distance from the decor bottom to just above the tab bar. */
    private fun marginAboveTabBar(decor: ViewGroup, dp: (Int) -> Int): Int {
        if (tabBarId == 0) {
            tabBarId = runCatching {
                decor.resources.getIdentifier("tab_bar", "id", "com.instagram.android")
            }.getOrDefault(0)
        }
        val tab = if (tabBarId != 0) decor.findViewById<View>(tabBarId) else null
        val loc = IntArray(2)
        if (tab != null && tab.isShown) {
            tab.getLocationOnScreen(loc)
            val tabTopScreen = loc[1]
            decor.getLocationOnScreen(loc)
            val decorTop = loc[1]
            return (decor.height - (tabTopScreen - decorTop) + dp(6)).coerceAtLeast(dp(48))
        }
        return dp(84)
    }

    private fun removeRibbon() {
        val view = ribbon ?: run {
            ribbonParent = null
            return
        }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        ribbon = null
        ribbonParent = null
    }

    // --- cover (pause fallback) ---------------------------------------------

    /**
     * Opaque ✋ cover over the centered video cell only — the user-visible
     * fallback when no pause hook exists. Never over the stories tray or the
     * tab bar (bounds = the container rect clipped above the tab bar);
     * non-clickable so scrolls and taps pass through; hidden while scrolling
     * (only the centered cell is covered) so it never swims mid-scroll.
     */
    /** Litho hosts reject manual children; remember once, stop retrying per tick. */
    private var hostAddFailed = false
    private var hostSkipLogged = false

    /** While covered: per-frame reposition so the cover tracks scrolls at display rate. */
    private var frameDecor: java.lang.ref.WeakReference<ViewGroup>? = null
    private var frameContainer: java.lang.ref.WeakReference<View>? = null
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val box = cover ?: return
            val decor = frameDecor?.get() ?: return
            val cont = frameContainer?.get() ?: return
            if (box.parent == null) return
            positionCover(box, decor, cont) ?: run {
                box.visibility = View.INVISIBLE
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun showCover(decor: ViewGroup, container: View) {
        val host = if (hostAddFailed) null else container as? ViewGroup
        val existing = cover
        if (existing != null && host != null) {
            // Ride the cell: re-parent only when the centered cell changes; the
            // cover then scrolls natively with the content (no 1 Hz reposition
            // lag). If Litho strips it on rebind the parent check re-adds.
            if (existing.parent !== host) {
                (existing.parent as? ViewGroup)?.removeView(existing)
                runCatching {
                    host.addView(existing, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }.onFailure { removeCover() }
            }
            if (cover != null) {
                existing.visibility = View.VISIBLE
                coverParent = host
                return
            }
        }
        if (host == null && !hostSkipLogged) {
            hostSkipLogged = true
            Log.i(TAG, "feed quiet: cover on decor; container not a ViewGroup: ${container.javaClass.name}")
        }
        if (existing != null && host == null && existing.parent === decor) {
            positionCover(existing, decor, container) ?: run { removeCover(); return }
            frameDecor = java.lang.ref.WeakReference(decor)
            frameContainer = java.lang.ref.WeakReference(container)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            Choreographer.getInstance().postFrameCallback(frameCallback)
            return
        }
        removeCover()

        val ctx = android.view.ContextThemeWrapper(decor.context, android.R.style.Theme_DeviceDefault)
        val density = decor.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val label = TextView(ctx).apply {
            text = Str.FEED_QUIET_RIBBON
            gravity = Gravity.CENTER
            setTextColor(Palette.TEXT_PRIMARY.toInt())
            textSize = 14f
            isClickable = false
            isFocusable = false
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            elevation = dp(5).toFloat()
            background = GradientDrawable().apply {
                // User asked for OPAQUE: force full alpha on the palette color.
                setColor((Palette.CARD_BACKGROUND or 0xFF000000).toInt())
                cornerRadius = 0f
            }
            addView(label)
        }
        if (host != null) {
            val added = runCatching {
                host.addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }.onFailure {
                hostAddFailed = true
                Log.i(TAG, "feed quiet: in-cell add failed on ${host.javaClass.simpleName}: ${it.message}")
            }.isSuccess
            if (added) {
                cover = box
                coverParent = host
                box.visibility = View.VISIBLE
                Log.i(TAG, "feed quiet: cover mounted in cell (native scroll)")
                return
            }
        }
        box.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        runCatching { decor.addView(box) }.onFailure { return }
        cover = box
        coverParent = decor
        positionCover(box, decor, container)
        frameDecor = java.lang.ref.WeakReference(decor)
        frameContainer = java.lang.ref.WeakReference(container)
        Choreographer.getInstance().postFrameCallback(frameCallback)
        Log.i(TAG, "feed quiet: cover shown over centered video cell")
    }

    private fun positionCover(box: ViewGroup, decor: ViewGroup, container: View): android.graphics.Rect? {
        val rect = android.graphics.Rect()
        if (!container.getGlobalVisibleRect(rect) || rect.isEmpty) return null
        tabBarId = tabBarId.ifZero {
            runCatching {
                decor.resources.getIdentifier("tab_bar", "id", decor.context.packageName)
            }.getOrDefault(0)
        }
        val tab = if (tabBarId != 0) decor.findViewById<View>(tabBarId) else null
        if (tab != null && tab.isShown) {
            val loc = IntArray(2)
            tab.getLocationOnScreen(loc)
            val bottom = rect.bottom
            rect.bottom = minOf(rect.bottom, loc[1] - (8 * decor.resources.displayMetrics.density).toInt())
            if (rect.isEmpty && bottom > loc[1]) rect.set(rect.left, loc[1] - 1, rect.right, loc[1] - 1)
        }
        val decorLoc = IntArray(2)
        decor.getLocationOnScreen(decorLoc)
        val params = box.layoutParams as FrameLayout.LayoutParams
        params.width = rect.width()
        params.height = rect.height()
        params.leftMargin = rect.left - decorLoc[0]
        params.topMargin = rect.top - decorLoc[1]
        params.gravity = Gravity.TOP or Gravity.START
        box.layoutParams = params
        box.requestLayout()
        box.visibility = View.VISIBLE
        return rect
    }

    private fun removeCover() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameDecor = null
        frameContainer = null
        val view = cover ?: run {
            coverParent = null
            restoreStreamVolume()
            return
        }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        restoreStreamVolume()
        cover = null
        coverParent = null
    }

    /**
     * The quiet state is maintained, not one-shot: the walk re-runs on a 2 s
     * cadence (player gain drifts back: the user's volume keys multiply the
     * stream, and IG rebinds players), and the media stream is pinned to 0
     * while covered, restored to its pre-cover level on uncover.
     */
    private fun maybeReassertQuiet(cellRoot: View) {
        val now = android.os.SystemClock.elapsedRealtime()
        pinStreamVolume()
        if (now - lastQuietReassertElapsed < QUIET_REASSERT_MS) return
        lastQuietReassertElapsed = now
        attemptPause(cellRoot)
    }

    private fun pinStreamVolume() {
        if (streamVolContext == null) streamVolContext = coverParent?.context?.applicationContext
        val audio = streamVolContext?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        if (streamVolAtCover < 0) {
            streamVolAtCover = runCatching { audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStreamPinAttempt < STREAM_PIN_ATTEMPT_MS) return
        lastStreamPinAttempt = now
        val current = runCatching { audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (current != 0) {
            runCatching { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0) }
            throttledLog("feed quiet: stream pinned 0 (was $current)")
        }
    }

    private fun restoreStreamVolume() {
        val level = streamVolAtCover
        if (level < 0) return
        streamVolAtCover = -1
        streamVolContext?.let { ctx ->
            val audio = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            audio?.let { runCatching { it.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level, 0) } }
        }
        streamVolContext = null
        lastQuietReassertElapsed = 0L
    }

    private companion object {
        const val TAG = "ReelGuardPatch"
        const val PAUSE_LOG_GAP_MS = 5000L
        const val QUIET_REASSERT_MS = 2000L
        const val STREAM_PIN_ATTEMPT_MS = 1000L
        const val MAX_SCAN_NODES = 400
        const val MAX_PAUSE_WALK = 60
        const val MAX_FIELDS_PER_NODE = 12
        const val CELL_WALK_DEPTH = 10
        val PAUSE_METHOD_NAMES = setOf(
            "pause", "pauseVideo", "pausePlayback", "stop", "suspend",
            // audio half of the quiet walk (user ask: no sound under the cover)
            "mute", "muteAudio", "pauseAudio", "silence",
        )

        /** Single-boolean fallbacks: arg to pause / resume / mute by name. */
        val BOOLEAN_QUIET_ARGS = listOf(
            "setPlaying" to false, "setPaused" to true,
            "setMuted" to true, "setMute" to true,
            "setSoundEnabled" to false, "setAudioEnabled" to false,
        )
    }
}

private fun Int.ifZero(block: () -> Int): Int = if (this != 0) this else block()
