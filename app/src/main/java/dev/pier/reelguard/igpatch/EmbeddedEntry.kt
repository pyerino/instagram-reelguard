package dev.pier.reelguard.igpatch

import android.app.Application

/**
 * The single smali call-in (insertion recipe in README):
 *
 * ```smali
 * # tail of com.instagram.app.InstagramAppShell.onCreate
 * invoke-static {p0}, Ldev/pier/reelguard/igpatch/EmbeddedEntry;->onApp(Landroid/app/Application;)V
 * ```
 *
 * Everything else (surface probe, view-based pause/resume, overlay, settings,
 * debug receiver) hangs off the controller created here.
 */
object EmbeddedEntry {

    @Volatile
    private var onAppSeen = false

    @JvmStatic
    fun onApp(app: Application) {
        val first = synchronized(this) {
            val first = !onAppSeen
            onAppSeen = true
            first
        }
        if (first) {
            EnforcementController.attachOnce(app)
        }
    }
}
