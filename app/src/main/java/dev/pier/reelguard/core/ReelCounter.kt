package dev.pier.reelguard.core

import java.time.Instant
import java.time.ZoneId

/** Persisted per-day reel count. [dayEpochDay] is a local-date epoch day. */
data class ReelCountState(val dayEpochDay: Long, val count: Int)

/** Persistence port for the daily reel counter. Same file, own keys. */
interface ReelCounterStore {
    fun load(): ReelCountState?
    fun save(state: ReelCountState)
}

/**
 * Display-only count of reels watched today. A reel counts when a tracked
 * surface's pager index advances while that surface is current and playing
 * (the same played verdict the accrual gates on); the entry reel of any
 * surface visit never counts, so re-entries cannot double-credit.
 *
 * The day boundary is local midnight in [zone] — the identical rule as
 * [UsageLedger] — and RESET_TODAY clears both. Purely informational: nothing
 * in enforcement reads it.
 */
class ReelCounter(
    private val store: ReelCounterStore,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    /** Per-surface last-seen pager index; session memory, intentionally not persisted. */
    private val lastSeenIndex = HashMap<Surface, Int>()

    /** Re-anchor on surface entry: the next observation is the entry reel. */
    fun onSurfaceEnter(surface: Surface) {
        lastSeenIndex.remove(surface)
    }

    /** Returns today's new count when [index] is an advance, else null. */
    fun onIndexObserved(surface: Surface, index: Int): Int? {
        val previous = lastSeenIndex.put(surface, index)
        if (previous == null || previous == index) return null
        return increment()
    }

    /** Today's count; reads as 0 from local midnight on (same rule as the ledger). */
    fun todayCount(): Int {
        val state = store.load() ?: return 0
        return if (state.dayEpochDay == todayEpochDay()) state.count else 0
    }

    fun resetToday() {
        store.save(ReelCountState(dayEpochDay = todayEpochDay(), count = 0))
    }

    private fun increment(): Int {
        val today = todayEpochDay()
        val current = store.load()
        val base = if (current != null && current.dayEpochDay == today) current.count else 0
        val next = base + 1
        store.save(ReelCountState(dayEpochDay = today, count = next))
        return next
    }

    private fun todayEpochDay(): Long =
        Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate().toEpochDay()

    companion object {
        /** HUD width guard: the pill must never push off-screen; cap the display. */
        const val DISPLAY_CAP = 999

        fun displayCount(count: Int): String =
            if (count > DISPLAY_CAP) "$DISPLAY_CAP+" else count.coerceAtLeast(0).toString()
    }
}
