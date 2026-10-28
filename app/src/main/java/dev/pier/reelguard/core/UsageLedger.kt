package dev.pier.reelguard.core

import java.time.Instant
import java.time.ZoneId

/**
 * Accrues reel-seconds against the daily budget. The day boundary is local
 * midnight in [zone].
 *
 * A read that observes a stale day reports 0 without writing; the next accrue
 * or reset persists the rolled-over day. [limitSeconds] is passed in by the
 * caller so limit changes apply immediately without cache invalidation.
 */
class UsageLedger(
    private val store: LedgerStore,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    fun todayEpochDay(nowMillis: Long = clock.nowMillis()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()

    /** Seconds used today; reads as 0 from local midnight on. */
    fun usedSeconds(): Int {
        val state = store.load() ?: return 0
        return if (state.dayEpochDay == todayEpochDay()) state.usedSeconds else 0
    }

    /** Adds [seconds] to today, clamped so usage never exceeds [limitSeconds]. */
    fun accrue(seconds: Int, limitSeconds: Int): Int {
        val today = todayEpochDay()
        val current = store.load()
        val base = if (current != null && current.dayEpochDay == today) current.usedSeconds else 0
        val headroom = (limitSeconds - base).coerceAtLeast(0)
        val delta = seconds.coerceAtLeast(0).coerceAtMost(headroom)
        val next = base + delta
        store.save(UsageState(dayEpochDay = today, usedSeconds = next))
        return next
    }

    fun resetToday() {
        store.save(UsageState(dayEpochDay = todayEpochDay(), usedSeconds = 0))
    }
}
