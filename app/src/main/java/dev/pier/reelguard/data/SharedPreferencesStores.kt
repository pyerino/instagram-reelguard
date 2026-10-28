package dev.pier.reelguard.data

import android.content.SharedPreferences
import dev.pier.reelguard.core.LedgerStore
import dev.pier.reelguard.core.SettingsRepository
import dev.pier.reelguard.core.SnoozeRepository
import dev.pier.reelguard.core.Surface
import dev.pier.reelguard.core.UsageState

/** SharedPreferences-backed stores. All methods are main-thread cheap and thread-safe. */

class SharedPreferencesLedgerStore(private val prefs: SharedPreferences) : LedgerStore {
    override fun load(): UsageState? {
        if (!prefs.contains(KEY_DAY)) return null
        return UsageState(
            dayEpochDay = prefs.getLong(KEY_DAY, 0L),
            usedSeconds = prefs.getInt(KEY_USED_SECONDS, 0),
        )
    }

    override fun save(state: UsageState) {
        prefs.edit()
            .putLong(KEY_DAY, state.dayEpochDay)
            .putInt(KEY_USED_SECONDS, state.usedSeconds)
            .apply()
    }

    companion object {
        private const val KEY_DAY = "budget_day_epoch_day"
        private const val KEY_USED_SECONDS = "budget_used_seconds"
    }
}

class SharedPreferencesSettings(private val prefs: SharedPreferences) : SettingsRepository {
    override fun limitMinutes(): Int = prefs.getInt(KEY_LIMIT_MINUTES, DEFAULT_LIMIT_MINUTES)

    override fun setLimitMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_LIMIT_MINUTES, minutes.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)).apply()
    }

    override fun isSurfaceBudgeted(surface: Surface): Boolean =
        prefs.getBoolean(KEY_SURFACE_PREFIX + surface.id, surface.budgetedByDefault)

    override fun setSurfaceBudgeted(surface: Surface, budgeted: Boolean) {
        prefs.edit().putBoolean(KEY_SURFACE_PREFIX + surface.id, budgeted).apply()
    }

    companion object {
        const val DEFAULT_LIMIT_MINUTES = 45

        /** The UI stepper is 5..480 step 5; the store accepts 1..1440 so the
         *  debug receiver can set a 1-minute budget for quick end-to-end runs. */
        const val MIN_LIMIT_MINUTES = 1
        const val MAX_LIMIT_MINUTES = 24 * 60

        private const val KEY_LIMIT_MINUTES = "limit_minutes"
        private const val KEY_SURFACE_PREFIX = "surface_budgeted_"
    }
}

class SharedPreferencesSnooze(private val prefs: SharedPreferences) : SnoozeRepository {
    override fun snoozeUntilMillis(): Long = prefs.getLong(KEY_SNOOZE_UNTIL, 0L)

    override fun setSnoozeUntilMillis(millis: Long) {
        prefs.edit().putLong(KEY_SNOOZE_UNTIL, millis).apply()
    }

    companion object {
        private const val KEY_SNOOZE_UNTIL = "snooze_until_millis"
    }
}
