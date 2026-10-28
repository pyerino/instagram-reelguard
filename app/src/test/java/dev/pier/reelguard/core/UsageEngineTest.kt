package dev.pier.reelguard.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

private class FakeLedgerStore : LedgerStore {
    var state: UsageState? = null
    override fun load(): UsageState? = state

    override fun save(state: UsageState) {
        this.state = state
    }
}

private class FakeSettings : SettingsRepository {
    var limit: Int = 45
    val budgeted = mutableMapOf<Surface, Boolean>()

    override fun limitMinutes(): Int = limit

    override fun setLimitMinutes(minutes: Int) {
        limit = minutes
    }

    override fun isSurfaceBudgeted(surface: Surface): Boolean =
        budgeted[surface] ?: surface.budgetedByDefault

    override fun setSurfaceBudgeted(surface: Surface, enabled: Boolean) {
        budgeted[surface] = enabled
    }
}

private class FakeSnooze : SnoozeRepository {
    var until: Long = 0L
    override fun snoozeUntilMillis(): Long = until

    override fun setSnoozeUntilMillis(millis: Long) {
        until = millis
    }
    var writes: Int = 0
}

private val ZONE = ZoneId.of("Europe/Copenhagen")

private fun cph(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZONE).toInstant().toEpochMilli()

/** Wires one engine over shared fakes, the way AppGraph wires the real ones. */
private class Harness(nowMillis: Long) {
    val clock = FakeClock(nowMillis)
    val store = FakeLedgerStore()
    val settings = FakeSettings()
    val snooze = FakeSnooze()
    val engine = UsageEngine(
        ledger = UsageLedger(store, clock, ZONE),
        settings = settings,
        snooze = snooze,
        clock = clock,
    )
}

class UsageEngineTest {

    @Test
    fun `accrual requires playing and screen on`() {
        val harness = Harness(cph(2026, 10, 28, 10, 0, 0))
        harness.engine.accrueSecond(Surface.REELS_TAB, playing = false, screenInteractive = true)
        harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = false)
        assertEquals(0, harness.engine.snapshot().usedSeconds)

        repeat(3) {
            harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }
        assertEquals(3, harness.engine.snapshot().usedSeconds)
    }

    @Test
    fun `feed reels count by default and can be disabled`() {
        val harness = Harness(cph(2026, 10, 28, 9, 0, 0))
        harness.engine.accrueSecond(Surface.FEED_REEL, playing = true, screenInteractive = true)
        assertEquals(1, harness.engine.snapshot().usedSeconds)

        harness.settings.setSurfaceBudgeted(Surface.FEED_REEL, false)
        repeat(2) {
            harness.engine.accrueSecond(Surface.FEED_REEL, playing = true, screenInteractive = true)
        }
        assertEquals(1, harness.engine.snapshot().usedSeconds)
    }

    @Test
    fun `accrual clamps at the budget boundary`() {
        val harness = Harness(cph(2026, 10, 28, 8, 0, 0))
        harness.settings.limit = 1

        repeat(61) {
            harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }

        val snapshot = harness.engine.snapshot()
        assertEquals(60, snapshot.usedSeconds)
        assertEquals(0, snapshot.remainingSeconds)
        assertTrue(snapshot.budgetExhausted)
    }

    @Test
    fun `usage rolls over at local midnight`() {
        val harness = Harness(cph(2026, 10, 28, 23, 59, 30))
        repeat(30) {
            harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }
        assertEquals(30, harness.engine.snapshot().usedSeconds)
        val dayOne = harness.engine.snapshot().dayEpochDay

        harness.clock.now += 60_000 // 00:00:30 the next morning

        val nextDay = harness.engine.snapshot()
        assertEquals(0, nextDay.usedSeconds)
        assertEquals(dayOne + 1, nextDay.dayEpochDay)
        assertFalse(nextDay.budgetExhausted)

        harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        assertEquals(1, harness.engine.snapshot().usedSeconds)
    }

    @Test
    fun `state survives a new engine over the same stores`() {
        val first = Harness(cph(2026, 10, 28, 12, 0, 0))
        repeat(90) {
            first.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }

        val second = Harness(first.clock.now)
        // Same persisted stores, fresh engine instance.
        val reopened = UsageEngine(
            ledger = UsageLedger(first.store, second.clock, ZONE),
            settings = first.settings,
            snooze = first.snooze,
            clock = second.clock,
        )
        assertEquals(90, reopened.snapshot().usedSeconds)
        assertEquals(45, reopened.snapshot().limitMinutes)
    }

    @Test
    fun `snooze expires and can be extended`() {
        val start = cph(2026, 10, 28, 18, 0, 0)
        val harness = Harness(start)

        harness.engine.snoozeMinutes(5)
        assertTrue(harness.engine.snapshot().snoozeActive)

        harness.clock.now = start + 5 * 60_000L - 1_000L
        assertTrue(harness.engine.snapshot().snoozeActive)

        harness.clock.now += 2_000L // one second past expiry
        val expired = harness.engine.snapshot()
        assertFalse(expired.snoozeActive)
        assertEquals(start + 5 * 60_000L, expired.snoozeUntilMillis)

        // A grant while a snooze is running extends it from the later of now/until.
        harness.clock.now = start + 60_000L
        harness.engine.snoozeMinutes(5)
        assertEquals(start + 10 * 60_000L, harness.engine.snapshot().snoozeUntilMillis)
    }

    @Test
    fun `overlay only on exhausted budgeted surfaces without active snooze`() {
        val start = cph(2026, 10, 28, 21, 0, 0)
        val harness = Harness(start)
        harness.settings.limit = 1

        assertFalse(harness.engine.shouldShowOverlay(Surface.REELS_TAB))
        assertFalse(harness.engine.shouldShowOverlay(null))

        repeat(60) {
            harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }
        assertTrue(harness.engine.shouldShowOverlay(Surface.REELS_TAB))

        harness.engine.snoozeMinutes(5)
        assertFalse(harness.engine.shouldShowOverlay(Surface.REELS_TAB))

        harness.clock.now = start + 6 * 60_000L
        assertTrue(harness.engine.shouldShowOverlay(Surface.REELS_TAB))

        // feed_reel defaults to count-only: time accrues once enabled, never overlays.
        harness.settings.setSurfaceBudgeted(Surface.FEED_REEL, true)
        assertFalse(harness.engine.shouldShowOverlay(Surface.FEED_REEL))
    }

    @Test
    fun `reset clears usage and snooze`() {
        val harness = Harness(cph(2026, 10, 28, 20, 0, 0))
        harness.settings.limit = 1
        repeat(60) {
            harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }
        harness.engine.snoozeMinutes(5)

        harness.engine.resetToday()

        val snapshot = harness.engine.snapshot()
        assertEquals(0, snapshot.usedSeconds)
        assertFalse(snapshot.snoozeActive)
        assertFalse(snapshot.budgetExhausted)
    }

    @Test
    fun `time accrues while snoozed and counts against the same budget`() {
        val harness = Harness(cph(2026, 10, 28, 22, 0, 0))
        harness.settings.limit = 1
        repeat(60) {
            harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }
        harness.engine.snoozeMinutes(5)

        repeat(30) {
            harness.engine.accrueSecond(Surface.REELS_TAB, playing = true, screenInteractive = true)
        }
        assertEquals(60, harness.engine.snapshot().usedSeconds)
    }
}
