package dev.pier.reelguard.igpatch.debug

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import dev.pier.reelguard.igpatch.BuildConfig

/**
 * Runtime-registered control surface (no manifest of our own once embedded).
 * The receiver lives on the application context for the process lifetime and
 * is exported on 33+ so adb can reach it:
 *
 *   adb shell am broadcast -p com.instagram.android -a dev.pier.reelguard.SET_LIMIT --ei minutes 1
 *   adb shell am broadcast -p com.instagram.android -a dev.pier.reelguard.RESET_TODAY
 *   adb shell am broadcast -p com.instagram.android -a dev.pier.reelguard.SNOOZE [--ei minutes 5]
 *   adb shell am broadcast -p com.instagram.android -a dev.pier.reelguard.SHOW_SETTINGS
 *   (any of the above: --ez probe true -> 60s ground-truth log window)
 */
object DebugReceiverInstaller {

    const val ACTION_SET_LIMIT = "dev.pier.reelguard.SET_LIMIT"
    const val ACTION_RESET_TODAY = "dev.pier.reelguard.RESET_TODAY"
    const val ACTION_SNOOZE = "dev.pier.reelguard.SNOOZE"
    const val ACTION_SHOW_SETTINGS = "dev.pier.reelguard.SHOW_SETTINGS"

    const val EXTRA_MINUTES = "minutes"
    const val EXTRA_PROBE = "probe"

    fun register(app: Application, onAction: (Intent) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                if (BuildConfig.DEBUG) {
                    Log.d("ReelGuardPatch", "debug intent action=${intent.action} extras=${intent.extras}")
                }
                onAction(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SET_LIMIT)
            addAction(ACTION_RESET_TODAY)
            addAction(ACTION_SNOOZE)
            addAction(ACTION_SHOW_SETTINGS)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }
}
