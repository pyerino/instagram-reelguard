package dev.pier.reelguard.igpatch

import android.content.Context
import dev.pier.reelguard.igpatch.data.EmbeddedRules

/**
 * Module-side graph: identical stores/keys/semantics as the companion app
 * (dev.pier.reelguard.data), but the SharedPreferences file lives in the
 * Instagram process's data dir. No AndroidX; UI is programmatic by design.
 */
class ModuleGraph(context: Context) {

    val appContext: Context = context.applicationContext

    private val clock = dev.pier.reelguard.core.Clock { System.currentTimeMillis() }
    private val zone = java.time.ZoneId.systemDefault()
    private val prefs = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val settings: dev.pier.reelguard.core.SettingsRepository =
        dev.pier.reelguard.data.SharedPreferencesSettings(prefs)

    val engine: dev.pier.reelguard.core.UsageEngine =
        dev.pier.reelguard.core.UsageEngine(
            ledger = dev.pier.reelguard.core.UsageLedger(
                dev.pier.reelguard.data.SharedPreferencesLedgerStore(prefs),
                clock,
                zone,
            ),
            settings = settings,
            snooze = dev.pier.reelguard.data.SharedPreferencesSnooze(prefs),
            clock = clock,
        )

    val rules: dev.pier.reelguard.core.DetectionRules =
        dev.pier.reelguard.core.DetectionRules.fromJson(EmbeddedRules.DETECTION_JSON.trim())

    companion object {
        const val PREFERENCES_NAME = "reelguard"
    }
}
