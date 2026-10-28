package dev.pier.reelguard.igpatch

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Method

/**
 * Playback control without any player access (453: SimpleVideoLayout carries
 * no play/pause methods; playback is driven through the UI grammar).
 *
 * Touch injection dispatches on the DECOR ROOT with absolute screen coords
 * taken from the video view's location on screen: an authentic stream through
 * IG's own hit-testing (GestureManagerFrameLayout children) instead of a
 * synthetic call on the view that would bypass the gesture stack.
 *
 * The controller owns reliability: after each attempt it re-probes the
 * pause marker and re-issues only while unconfirmed.
 */
object VideoControl {

    const val VIDEO_VIEW_CLASS = "com.instagram.ui.simplevideolayout.SimpleVideoLayout"

    private const val TAP_X_OFFSET_PX = 210f
    private const val TAP_Y_OFFSET_PX = 150f
    private const val TAP_INTERVAL_MS = 80L

    private val methodCache = HashMap<String, Method?>()
    private val PAUSE_METHODS = arrayOf("pause", "pauseVideo", "suspendPlayback")
    private val RESUME_METHODS = arrayOf("resume", "play", "playVideo")

    fun isReelVideoView(view: View): Boolean = view.javaClass.name == VIDEO_VIEW_CLASS

    fun pause(decor: View?, video: View): String {
        for (name in PAUSE_METHODS) {
            resolve(video, name)?.let { method ->
                runCatching { method.invoke(video) }
                return "method:$name"
            }
        }
        return if (decor != null && injectTap(decor, video)) "decor-touch" else {
            video.performClick()
            "performClick(video)"
        }
    }

    fun resume(decor: View?, video: View, pauseButton: View?): String {
        for (name in RESUME_METHODS) {
            resolve(video, name)?.let { method ->
                runCatching { method.invoke(video) }
                return "method:$name"
            }
        }
        if (pauseButton != null) {
            pauseButton.performClick()
            return "click(pause_button)"
        }
        return if (decor != null && injectTap(decor, video)) "decor-touch" else {
            video.performClick()
            "performClick(video)"
        }
    }

    /**
     * Real DOWN/UP 80ms apart, at the target's center +150px down (clears the
     * centered pause/mute overlays), in DECOR-LOCAL coordinates derived from
     * on-screen locations so IG's hit-testing sees a genuine tap.
     */
    fun injectTap(decor: View, target: View): Boolean {
        if (target.width <= 0 || target.height <= 0 || decor.width <= 0 || decor.height <= 0) return false
        val targetLoc = IntArray(2)
        target.getLocationOnScreen(targetLoc)
        val decorLoc = IntArray(2)
        decor.getLocationOnScreen(decorLoc)

        val x = (targetLoc[0] + target.width / 2f + TAP_X_OFFSET_PX - decorLoc[0])
            .coerceIn(1f, decor.width - 1f)
        val y = (targetLoc[1] + target.height / 2f + TAP_Y_OFFSET_PX - decorLoc[1])
            .coerceIn(1f, decor.height - 1f)

        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + TAP_INTERVAL_MS, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val downConsumed = decor.dispatchTouchEvent(down)
            val upConsumed = decor.dispatchTouchEvent(up)
            downConsumed || upConsumed
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun resolve(view: View, methodName: String): Method? {
        val key = "${view.javaClass.name}#$methodName"
        methodCache[key]?.let { return it }
        var clazz: Class<*>? = view.javaClass
        while (clazz != null && View::class.java != clazz) {
            for (method in clazz.declaredMethods) {
                if (method.name != methodName) continue
                if (method.parameterTypes.isNotEmpty()) continue
                method.isAccessible = true
                methodCache[key] = method
                return method
            }
            clazz = clazz.superclass
        }
        methodCache[key] = null
        return null
    }
}
