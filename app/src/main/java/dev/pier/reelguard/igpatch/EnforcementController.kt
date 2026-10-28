package dev.pier.reelguard.igpatch

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import dev.pier.reelguard.core.Surface
import dev.pier.reelguard.igpatch.debug.DebugReceiverInstaller
import dev.pier.reelguard.igpatch.ui.ActivityOverlay
import dev.pier.reelguard.igpatch.ui.RemainingHud
import dev.pier.reelguard.igpatch.ui.SettingsDialog

/**
 * The in-process enforcement loop, hook-free: a 1 Hz ticker walks the resumed
 * activity's decor (surface + pause-marker verdict per the shared detection
 * rules), accrues budget seconds, and enforces through the reels view itself
 * (see [VideoControl]) plus the card overlay.
 *
 * Threading: everything on the main thread (lifecycle, receiver, ticker).
 */
class EnforcementController private constructor() {

    private lateinit var graph: ModuleGraph
    private lateinit var powerManager: PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentSurface: Surface? = null

    private val playingBySurface = HashMap<Surface, Boolean>()
    private var resumed: Activity? = null
    private var overlay: ActivityOverlay? = null
    private var settingsDialog: Dialog? = null
    private var hud: RemainingHud? = null
    private var hudHost: Activity? = null
    private var pausedByUs = false
    private var coldStartPromptShown = false

    // view-control state (refreshed by every scan)
    private var videoView: View? = null
    private var pauseButtonView: View? = null
    private var lastPauseAtElapsed = 0L
    private var verifyRunnable: Runnable? = null
    private var lastPresentIds: Set<String> = emptySet()
    private var lastEnforceAttemptElapsed = 0L

    // ground-truth probe window (debug broadcast, extra "probe"=true)
    private var probeUntilElapsed = 0L

    fun attach(app: Application) {
        graph = ModuleGraph(app)
        powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        // user request: settings via long-press on the Reels tab instead of a notification
        app.registerActivityLifecycleCallbacks(activityLifecycle)
        DebugReceiverInstaller.register(app, ::onDebugIntent)
        mainHandler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    // ---- sink API: probe -----------------------------------------------------------------------

    fun onSurfaceEnter(surface: Surface) {
        if (currentSurface != surface) {
            currentSurface = surface
        }
        evaluate()
    }

    fun onSurfaceExit(surface: Surface) {
        if (currentSurface != surface) return
        currentSurface = null
        hideOverlay()
        pausedByUs = false
    }

    fun onPlayingObserved(surface: Surface, playing: Boolean) {
        playingBySurface[surface] = playing
        evaluate()
    }

    // ---- ticker ---------------------------------------------------------------------------------

    private val tickRunnable = object : Runnable {
        override fun run() {
            runCatching { tick() }.onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "tick failed", it) }
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private fun tick() {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed < probeUntilElapsed) probeWindowTick(nowElapsed - (probeUntilElapsed - PROBE_WINDOW_MS))

        scanSurface()

        val surface = currentSurface
        val interactive = powerManager.isInteractive
        // Surfaces without a verdict count as playing while visible; the
        // pause-marker refines this on reels/DM (live-verified rule).
        val playing = surface?.let { playingBySurface[it] ?: DEFAULT_PLAYING_WITHOUT_MARKER } ?: false
        if (surface != null) {
            graph.engine.accrueSecond(surface, playing = playing, screenInteractive = interactive)
        }

        evaluate()
        updateHud()
    }

    /** Decor id-set scan; refreshes surface state and the view-control handles. */
    private fun scanSurface() {
        val activity = resumed ?: return
        val scan = runCatching { SurfaceProbe.scan(activity, graph.rules) }.getOrNull() ?: return
        runCatching { TabLongPress.maybeAttach(activity) { openSettings() } }
        lastPresentIds = scan.presentIds

        if (scan.surface != currentSurface) {
            currentSurface?.let { onSurfaceExit(it) }
            scan.surface?.let { onSurfaceEnter(it) }
        }
        videoView = scan.videoView
        pauseButtonView = scan.pauseButton
        if (scan.surface != null && scan.playing != null) {
            playingBySurface[scan.surface] = scan.playing
        }
    }

    /** Verify-then-retry: never blind-toggle. Re-probe the pause marker
     *  (clips_pause_button present = paused, live-verified) 250ms after every
     *  attempt; re-pause only while it is still absent; 5 attempts max. */
    private fun schedulePauseVerify(attempt: Int) {
        val task = Runnable { verifyPaused(attempt) }
        verifyRunnable = task
        mainHandler.postDelayed(task, VERIFY_DELAY_MS)
    }

    private fun verifyPaused(attempt: Int) {
        verifyRunnable = null
        val scan = resumed?.let { runCatching { SurfaceProbe.scan(it, graph.rules) }.getOrNull() }
        val paused = scan?.playing == false || scan?.pauseButton != null
        if (paused) {
            Log.i(TAG, "pause verified (attempt $attempt)")
            return
        }
        val sincePause = SystemClock.elapsedRealtime() - lastPauseAtElapsed
        if (sincePause < REPAUSE_MIN_GAP_MS) {
            // The paused-marker renders with latency: an immediate re-tap lands
            // on the just-paused video and RESUMES it (the observed double-press
            // on first trigger). Re-read only; never tap inside the gap.
            schedulePauseVerify(attempt)
            return
        }
        if (attempt >= VERIFY_MAX_ATTEMPTS) {
            Log.w(TAG, "pause NOT verified after $attempt attempts; overlay remains as fallback")
            return
        }
        Log.i(TAG, "pause unconfirmed; verify retry $attempt/$VERIFY_MAX_ATTEMPTS")
        val video = videoView ?: return
        runCatching { VideoControl.pause(touchRoot(), video) }
        schedulePauseVerify(attempt + 1)
    }

    private fun cancelPauseVerify() {
        verifyRunnable?.let { mainHandler.removeCallbacks(it) }
        verifyRunnable = null
    }

    /**
     * Touch-dispatch root for playback taps: the activity content host, not the
     * decor — the enforcement scrim lives on the decor ABOVE the content, and a
     * decor-level dispatch while it is up is absorbed by our own scrim.
     */
    private fun touchRoot(): View? = resumed?.window
        ?.findViewById<View>(android.R.id.content) ?: resumed?.window?.decorView

    private fun pausePlayback(reason: String) {
        val video = videoView
        if (video == null) {
            // Never silent: a show-transition with no player handle yet must be
            // visible in the log (the enforce-retry below relies on this).
            Log.i(TAG, "pause skipped: no video view ($reason) ids=$lastPresentIds")
            return
        }
        cancelPauseVerify()
        val root = touchRoot()
        val how = runCatching { VideoControl.pause(root, video) }
            .onFailure { Log.w(TAG, "pause error ($reason)", it) }
            .getOrNull() ?: return
        pausedByUs = true
        lastPauseAtElapsed = SystemClock.elapsedRealtime()
        lastEnforceAttemptElapsed = lastPauseAtElapsed
        Log.i(TAG, "pause via $how ($reason) on ${video.javaClass.simpleName}")
        schedulePauseVerify(1)
    }

    private fun resumePlayback() {
        cancelPauseVerify()
        val video = videoView ?: return
        val how = runCatching { VideoControl.resume(touchRoot(), video, pauseButtonView) }.getOrNull() ?: return
        lastPauseAtElapsed = 0L
        Log.i(TAG, "resume via $how")
    }

    private fun evaluate() {
        val surface = currentSurface
        val shouldEnforce = surface != null && graph.engine.shouldShowOverlay(surface)
        val overlayShowing = overlay?.isShowing == true

        if (shouldEnforce && !overlayShowing) {
            val activity = resumed ?: return
            if (!pausedByUs) pausePlayback("enforce")
            overlay = ActivityOverlay(activity).also { it.show() }
        } else if (shouldEnforce && overlayShowing && !pausedByUs) {
            // The show-transition pause can land before the player view exists
            // (or miss); keep enforcing while the card is up — the scrim blocks
            // touches, so this never fights the user.
            val now = SystemClock.elapsedRealtime()
            if (now - lastEnforceAttemptElapsed > ENFORCE_RETRY_INTERVAL_MS) {
                pausePlayback("enforce-retry")
            }
        } else if (!shouldEnforce && overlayShowing) {
            hideOverlay()
            if (pausedByUs) {
                resumePlayback()
                pausedByUs = false
            }
        }
        updateHud()
    }

    /** Live remaining budget; hidden off-budgeted-surface, screen-off, under the card. */
    private fun updateHud() {
        val surface = currentSurface
        val eligible = surface != null && surface.overlayEnforced && graph.settings.isSurfaceBudgeted(surface)
        val activity = resumed
        val show = eligible && activity != null &&
            powerManager.isInteractive &&
            overlay?.isShowing != true

        if (!show) {
            hud?.hide()
            if (!eligible || activity == null) {
                hud = null
                hudHost = null
            }
            return
        }
        if (activity !== hudHost || hud == null) {
            hud?.hide()
            hud = RemainingHud(activity).also { it.show() }
            hudHost = activity
        }
        hud?.update(graph.engine.snapshot().remainingSeconds)
    }

    private fun detachHud() {
        hud?.hide()
        hud = null
        hudHost = null
    }

    private fun hideOverlay() {
        cancelPauseVerify()
        overlay?.hide()
        overlay = null
    }

    private fun onDebugIntent(intent: Intent) {
        when (intent.action) {
            DebugReceiverInstaller.ACTION_SET_LIMIT -> {
                val minutes = intent.getIntExtra(DebugReceiverInstaller.EXTRA_MINUTES, -1)
                if (minutes > 0) graph.settings.setLimitMinutes(minutes)
            }

            DebugReceiverInstaller.ACTION_RESET_TODAY -> graph.engine.resetToday()

            DebugReceiverInstaller.ACTION_SNOOZE ->
                graph.engine.snoozeMinutes(
                    intent.getIntExtra(DebugReceiverInstaller.EXTRA_MINUTES, 5).coerceAtLeast(1),
                )

            DebugReceiverInstaller.ACTION_SHOW_SETTINGS -> openSettings()
        }
        if (intent.getBooleanExtra(DebugReceiverInstaller.EXTRA_PROBE, false)) {
            probeUntilElapsed = SystemClock.elapsedRealtime() + PROBE_WINDOW_MS
            Log.i(PROBE_TAG, "probe window opened for ${PROBE_WINDOW_MS / 1000}s")
        }
        evaluate()
    }

    /** Single guarded entry: overlay button, adb SHOW_SETTINGS, tab long-press. */
    private fun openSettings() {
        val activity = resumed
        if (activity == null || activity.isFinishing) {
            Log.i(TAG, "settings ignored: no resumed activity")
            return
        }
        if (settingsDialog?.isShowing == true) return
        settingsDialog = SettingsDialog(activity, graph, onDismissed = {
            settingsDialog = null
            evaluate()
        }).also { it.show() }
    }

    private fun dismissSettingsDialog() {
        settingsDialog?.dismiss()
        settingsDialog = null
    }

    private fun maybeColdStartPrompt(activity: Activity) {
        if (coldStartPromptShown) return
        if (!graph.engine.snapshot().budgetExhausted) return
        coldStartPromptShown = true
        SettingsDialog(activity, graph, onDismissed = { evaluate() }).show()
    }

    // ---- ground-truth probe (debug) ---------------------------------------------------------------

    private fun probeWindowTick(elapsedIntoWindow: Long) {
        val activity = resumed ?: return
        val viewNames = HashSet<String>()
        collectClassNames(activity.window?.decorView ?: return, viewNames, 0)
        val fragmentNames = fragmentNames(activity)
        Log.i(
            PROBE_TAG,
            "t+${elapsedIntoWindow / 1000}s surface=${currentSurface?.id} " +
                "video=${videoView != null} pauseBtn=${pauseButtonView != null}\n" +
                "ids=${lastPresentIds.sorted().joinToString(prefix = "[", postfix = "]")}\n" +
                "views=${viewNames.sorted().joinToString(prefix = "[", postfix = "]")}\n" +
                "fragments=${fragmentNames.joinToString(prefix = "[", postfix = "]")}",
        )
    }

    private fun collectClassNames(view: View, into: MutableSet<String>, depth: Int) {
        into.add(view.javaClass.name)
        if (depth >= PROBE_MAX_VIEW_DEPTH) return
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let { collectClassNames(it, into, depth + 1) }
            }
        }
    }

    /** androidx fragment names, two levels deep, reflection-only. */
    private fun fragmentNames(activity: Activity): List<String> {
        if (BuildConfig.DEBUG.not() && probeUntilElapsed == 0L) return emptyList()
        return runCatching {
            val fragmentActivity = Class.forName("androidx.fragment.app.FragmentActivity")
            if (!fragmentActivity.isInstance(activity)) return emptyList()
            val fm = fragmentActivity
                .getMethod("getSupportFragmentManager")
                .invoke(activity)
            val fragments = fm.javaClass.getMethod("getFragments").invoke(fm) as? List<*>
            val names = mutableListOf<String>()
            fun childNames(fragment: Any, prefix: String) {
                names += prefix + fragment.javaClass.name
                val child = runCatching {
                    fragment.javaClass.getMethod("getChildFragmentManager").invoke(fragment)
                }.getOrNull() ?: return
                val children = runCatching {
                    child.javaClass.getMethod("getFragments").invoke(child) as? List<*>
                }.getOrNull() ?: return
                children.filterNotNull().forEach { childNames(it, "$prefix  ") }
            }
            (fragments ?: emptyList<Any>()).filterNotNull().forEach { childNames(it, "") }
            names
        }.getOrDefault(emptyList())
    }

    private val activityLifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = activity
            maybeColdStartPrompt(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity === resumed) {
                hideOverlay()
                dismissSettingsDialog()
                detachHud()
                resumed = null
                currentSurface?.let { onSurfaceExit(it) }
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity === resumed) {
                hideOverlay()
                dismissSettingsDialog()
                detachHud()
                resumed = null
                currentSurface?.let { onSurfaceExit(it) }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    companion object {
        const val TAG = "ReelGuardPatch"
        const val PROBE_TAG = "ReelGuardProbe"

        const val TICK_INTERVAL_MS = 1_000L
        const val DEFAULT_PLAYING_WITHOUT_MARKER = true

        const val PROBE_WINDOW_MS = 60_000L
        const val PROBE_MAX_VIEW_DEPTH = 18

        const val VERIFY_DELAY_MS = 400L
        const val VERIFY_MAX_ATTEMPTS = 6
        const val ENFORCE_RETRY_INTERVAL_MS = 2_000L

        /** Min gap between real pause taps: covers the paused-marker render lag. */
        const val REPAUSE_MIN_GAP_MS = 1_200L

        @Volatile
        private var attached = false

        @Volatile
        var instance: EnforcementController? = null
            private set

        fun instanceOrNull(): EnforcementController? = instance

        /** Returns true when this call performed the attachment. */
        fun attachOnce(app: Application): Boolean = synchronized(this) {
            if (attached) {
                false
            } else {
                val controller = EnforcementController()
                instance = controller
                attached = true
                controller.attach(app)
                true
            }
        }
    }
}
