package dev.pier.reelguard.igpatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chain-cap / re-arm / cooldown semantics of the feed nudge (round 8). */
class NudgePolicyTest {
    private class NudgeFakeClock(var now: Long = 10_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun policyOf(clock: NudgeFakeClock) = NudgePolicy { clock.now }

    @Test
    fun `first nudge on a fresh cell is allowed`() {
        val clock = NudgeFakeClock()
        val policy = policyOf(clock)
        assertTrue(policy.shouldNudge(101))
    }

    @Test
    fun `same cell is not re-nudged without a reset`() {
        val clock = NudgeFakeClock()
        val policy = policyOf(clock)
        policy.onNudged(7)
        clock.advance(NudgePolicy.MIN_GAP_MS)
        assertFalse(policy.shouldNudge(7))
        assertTrue(policy.shouldNudge(8))
    }

    @Test
    fun `minimum gap holds between distinct cells`() {
        val clock = NudgeFakeClock()
        val policy = policyOf(clock)
        policy.onNudged(1)
        clock.advance(1000)
        assertFalse(policy.shouldNudge(2))
        clock.advance(NudgePolicy.MIN_GAP_MS - 1000)
        assertTrue(policy.shouldNudge(2))
    }

    @Test
    fun `chain caps at three then cools down ten seconds`() {
        val clock = NudgeFakeClock()
        val policy = policyOf(clock)
        policy.onNudged(1)
        clock.advance(NudgePolicy.MIN_GAP_MS)
        policy.onNudged(2)
        clock.advance(NudgePolicy.MIN_GAP_MS)
        policy.onNudged(3)
        // Chain full: even a fresh cell inside the cooldown is denied.
        clock.advance(NudgePolicy.MIN_GAP_MS)
        assertFalse(policy.shouldNudge(4))
        // Past the cooldown the chain resets and nudging resumes.
        clock.advance(NudgePolicy.COOLDOWN_MS)
        assertTrue(policy.shouldNudge(4))
        // And the cap re-arms from zero: three fresh nudges cool down again.
        policy.onNudged(4)
        clock.advance(NudgePolicy.MIN_GAP_MS)
        policy.onNudged(5)
        clock.advance(NudgePolicy.MIN_GAP_MS)
        policy.onNudged(6)
        clock.advance(NudgePolicy.MIN_GAP_MS)
        assertFalse(policy.shouldNudge(7))
        clock.advance(NudgePolicy.COOLDOWN_MS)
        assertTrue(policy.shouldNudge(7))
    }

    @Test
    fun `user scroll resets chain and once-per-cell key`() {
        val clock = NudgeFakeClock()
        val policy = policyOf(clock)
        policy.onNudged(1)
        clock.advance(NudgePolicy.NUDGE_GRACE_MS + 1)
        policy.onScrollObserved(idle = false)
        clock.advance(NudgePolicy.MIN_GAP_MS)
        // Same cell re-eligible: the scroll made it a new entry.
        assertTrue(policy.shouldNudge(1))
    }

    @Test
    fun `our own nudge drag does not count as a user scroll`() {
        val clock = NudgeFakeClock()
        val policy = policyOf(clock)
        policy.onNudged(1)
        policy.onScrollObserved(idle = false) // within grace: ignored
        clock.advance(NudgePolicy.MIN_GAP_MS)
        assertFalse(policy.shouldNudge(1))
    }

    @Test
    fun `budget restored clears chain and cell memory`() {
        val clock = NudgeFakeClock()
        val policy = policyOf(clock)
        policy.onNudged(1)
        policy.onBudgetRestored()
        clock.advance(NudgePolicy.MIN_GAP_MS)
        assertTrue(policy.shouldNudge(1))
    }
}
