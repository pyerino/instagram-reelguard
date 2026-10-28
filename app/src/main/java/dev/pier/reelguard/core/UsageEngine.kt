package dev.pier.reelguard.core

/** Immutable view of the budget state, for UI and enforcement decisions. */
data class UsageSnapshot(
    val atMillis: Long,
    val dayEpochDay: Long,
    val usedSeconds: Int,
    val limitSeconds: Int,
    val snoozeUntilMillis: Long,
) {
    val remainingSeconds: Int get() = (limitSeconds - usedSeconds).coerceAtLeast(0)

    /** True once today's usage reached the limit (limits are >= 1 minute). */
    val budgetExhausted: Boolean get() = usedSeconds >= limitSeconds

    val snoozeActive: Boolean get() = snoozeUntilMillis > atMillis

    /** Minutes for display, rounded up so partial minutes are visible. */
    val usedMinutesDisplay: Int get() = if (usedSeconds <= 0) 0 else (usedSeconds + 59) / 60

    val limitMinutes: Int get() = limitSeconds / 60
}

/**
 * Pure budget logic: accrual gating, snooze grants, resets and the overlay
 * decision. No Android types; persistence happens through the injected ports.
 */
class UsageEngine(
    private val ledger: UsageLedger,
    private val settings: SettingsRepository,
    private val snooze: SnoozeRepository,
    private val clock: Clock,
) {
    /**
     * Counts one tick against [surface]'s budget. Time accrues only when the
     * surface is budgeted, the video is actually playing and the screen is on.
     */
    fun accrueSecond(surface: Surface, playing: Boolean, screenInteractive: Boolean) {
        if (!settings.isSurfaceBudgeted(surface)) return
        if (!playing || !screenInteractive) return
        ledger.accrue(seconds = 1, limitSeconds = settings.limitMinutes() * 60)
    }

    fun snapshot(): UsageSnapshot {
        val now = clock.nowMillis()
        return UsageSnapshot(
            atMillis = now,
            dayEpochDay = ledger.todayEpochDay(now),
            usedSeconds = ledger.usedSeconds(),
            limitSeconds = settings.limitMinutes() * 60,
            snoozeUntilMillis = snooze.snoozeUntilMillis(),
        )
    }

    /** Grants [minutes] of extra budget from now; a running snooze is extended. */
    fun snoozeMinutes(minutes: Int) {
        if (minutes <= 0) return
        val now = clock.nowMillis()
        val base = maxOf(now, snooze.snoozeUntilMillis())
        snooze.setSnoozeUntilMillis(base + minutes * 60_000L)
    }

    /** Clears today's usage and any active snooze. */
    fun resetToday() {
        ledger.resetToday()
        snooze.setSnoozeUntilMillis(0L)
    }

    /**
     * Enforcement rule: the overlay only ever appears on a surface that
     * enforces with an overlay (feed reels count but never overlay), whose
     * budget is spent and whose snooze (if any) has expired.
     */
    fun shouldShowOverlay(surface: Surface?): Boolean {
        if (surface == null) return false
        if (!surface.overlayEnforced) return false
        if (!settings.isSurfaceBudgeted(surface)) return false
        val snapshot = snapshot()
        return snapshot.budgetExhausted && !snapshot.snoozeActive
    }
}
