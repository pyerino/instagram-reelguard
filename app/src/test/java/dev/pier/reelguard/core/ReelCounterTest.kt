package dev.pier.reelguard.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class CounterFakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

private class CounterFakeStore : ReelCounterStore {
    var state: ReelCountState? = null
    override fun load(): ReelCountState? = state

    override fun save(state: ReelCountState) {
        this.state = state
    }
}

private val REEL_ZONE = ZoneId.of("Europe/Copenhagen")

private fun reelCph(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, second, 0, REEL_ZONE).toInstant().toEpochMilli()

private class CounterHarness(nowMillis: Long) {
    val clock = CounterFakeClock(nowMillis)
    val store = CounterFakeStore()
    val counter = ReelCounter(store, clock, REEL_ZONE)
}

class ReelCounterTest {

    @Test
    fun `entry reel anchors and later advances count`() {
        val h = CounterHarness(reelCph(2026, 11, 2, 12, 0, 0))
        h.counter.onSurfaceEnter(Surface.REELS_TAB)

        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 5)) // entry: anchor only
        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 5)) // no change
        assertEquals(1, h.counter.onIndexObserved(Surface.REELS_TAB, 6))
        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 6))
        assertEquals(2, h.counter.onIndexObserved(Surface.REELS_TAB, 7))
        assertEquals(2, h.counter.todayCount())
    }

    @Test
    fun `backward navigation counts too`() {
        val h = CounterHarness(reelCph(2026, 11, 2, 12, 0, 0))
        h.counter.onSurfaceEnter(Surface.REELS_TAB)
        h.counter.onIndexObserved(Surface.REELS_TAB, 4)
        assertEquals(1, h.counter.onIndexObserved(Surface.REELS_TAB, 3))
    }

    @Test
    fun `re-entry re-anchors without crediting the entry reel`() {
        val h = CounterHarness(reelCph(2026, 11, 2, 12, 0, 0))
        h.counter.onSurfaceEnter(Surface.REELS_TAB)
        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 2))
        assertEquals(1, h.counter.onIndexObserved(Surface.REELS_TAB, 3))
        assertEquals(1, h.counter.todayCount())

        // Leave (memory for the surface drops on the next enter) and come back
        // while the pager still sits on 3: the entry reel is not re-credited.
        h.counter.onSurfaceEnter(Surface.REELS_TAB)
        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 3))
        assertEquals(2, h.counter.onIndexObserved(Surface.REELS_TAB, 4))
        assertEquals(2, h.counter.todayCount())
    }

    @Test
    fun `per-surface memories are independent`() {
        val h = CounterHarness(reelCph(2026, 11, 2, 12, 0, 0))
        h.counter.onSurfaceEnter(Surface.REELS_TAB)
        h.counter.onSurfaceEnter(Surface.DM_PLAYER)

        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 10))
        assertNull(h.counter.onIndexObserved(Surface.DM_PLAYER, 0))
        assertEquals(1, h.counter.onIndexObserved(Surface.DM_PLAYER, 1))
        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 10))
        assertEquals(2, h.counter.onIndexObserved(Surface.REELS_TAB, 11))
        assertEquals(2, h.counter.todayCount())
    }

    @Test
    fun `count rolls over with the daily budget at local midnight`() {
        val h = CounterHarness(reelCph(2026, 11, 2, 23, 59, 30))
        h.counter.onSurfaceEnter(Surface.REELS_TAB)
        h.counter.onIndexObserved(Surface.REELS_TAB, 0)
        h.counter.onIndexObserved(Surface.REELS_TAB, 1)
        assertEquals(1, h.counter.todayCount())

        h.clock.now += 60_000 // 00:00:30 the next morning

        assertEquals(0, h.counter.todayCount())

        // Stale-day state is replaced by the next counted advance, on the new day.
        h.counter.onSurfaceEnter(Surface.REELS_TAB)
        assertNull(h.counter.onIndexObserved(Surface.REELS_TAB, 1))
        assertEquals(1, h.counter.onIndexObserved(Surface.REELS_TAB, 2))
        val saved = requireNotNull(h.store.state)
        val expectedDay = java.time.Instant.ofEpochMilli(h.clock.now).atZone(REEL_ZONE).toLocalDate().toEpochDay()
        assertEquals(expectedDay, saved.dayEpochDay)
        assertEquals(1, saved.count)
    }

    @Test
    fun `resetToday zeroes the visible count`() {
        val h = CounterHarness(reelCph(2026, 11, 2, 20, 0, 0))
        h.counter.onSurfaceEnter(Surface.REELS_TAB)
        h.counter.onIndexObserved(Surface.REELS_TAB, 1)
        h.counter.onIndexObserved(Surface.REELS_TAB, 2)
        assertEquals(1, h.counter.todayCount())

        h.counter.resetToday()
        assertEquals(0, h.counter.todayCount())

        // Index memory is session-state: the next real advance still counts.
        assertEquals(1, h.counter.onIndexObserved(Surface.REELS_TAB, 3))
    }

    @Test
    fun `display caps at 999`() {
        assertEquals("0", ReelCounter.displayCount(0))
        assertEquals("37", ReelCounter.displayCount(37))
        assertEquals("999", ReelCounter.displayCount(999))
        assertEquals("999+", ReelCounter.displayCount(1000))
        assertEquals("999+", ReelCounter.displayCount(123_456))
        assertEquals("0", ReelCounter.displayCount(-3))
    }

    @Test
    fun `state survives a new counter over the same store`() {
        val first = CounterHarness(reelCph(2026, 11, 2, 9, 0, 0))
        first.counter.onSurfaceEnter(Surface.REELS_TAB)
        first.counter.onIndexObserved(Surface.REELS_TAB, 0)
        first.counter.onIndexObserved(Surface.REELS_TAB, 1)
        first.counter.onIndexObserved(Surface.REELS_TAB, 2)

        val reopened = ReelCounter(first.store, first.clock, REEL_ZONE)
        assertEquals(2, reopened.todayCount())
        reopened.onSurfaceEnter(Surface.REELS_TAB)
        assertNull(reopened.onIndexObserved(Surface.REELS_TAB, 2))
        assertEquals(3, reopened.onIndexObserved(Surface.REELS_TAB, 3))
    }
}
