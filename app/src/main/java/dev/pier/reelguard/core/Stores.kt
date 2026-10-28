package dev.pier.reelguard.core

/** Persisted per-day usage. [dayEpochDay] is a local-date epoch day. */
data class UsageState(val dayEpochDay: Long, val usedSeconds: Int)

/** Persistence port for the usage ledger. Implementations must be safe for concurrent use. */
interface LedgerStore {
    fun load(): UsageState?
    fun save(state: UsageState)
}

/** Read/write port for user settings. */
interface SettingsRepository {
    fun limitMinutes(): Int
    fun setLimitMinutes(minutes: Int)

    fun isSurfaceBudgeted(surface: Surface): Boolean
    fun setSurfaceBudgeted(surface: Surface, budgeted: Boolean)
}

/** Persistence port for the snooze expiry timestamp (epoch millis; 0 = none). */
interface SnoozeRepository {
    fun snoozeUntilMillis(): Long
    fun setSnoozeUntilMillis(millis: Long)
}

/** Value of a snooze grant; [untilMillis] is an absolute epoch timestamp. */
data class SnoozeState(val untilMillis: Long) {
    fun isActiveAt(nowMillis: Long): Boolean = untilMillis > nowMillis

    companion object {
        val NONE = SnoozeState(0L)
    }
}
