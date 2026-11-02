package dev.pier.reelguard.igpatch

/**
 * Anti-churn state machine for the feed quiet-scroll nudge (round 8).
 *
 * Pure logic, clock-injected for tests. Semantics (lead rulings):
 *  - one nudge per centered-cell entry: the same cell key is not re-nudged
 *    until the entry resets (a real user scroll is observed, the budget
 *    returns, or a cooldown lapse clears the chain);
 *  - at least [MIN_GAP_MS] between any two nudges;
 *  - at most [MAX_CHAIN] consecutive nudges, then [COOLDOWN_MS] quiet;
 *  - a non-idle scroll observation resets the chain and the once-per-cell
 *    key, EXCEPT within [NUDGE_GRACE_MS] of our own nudge (the injected
 *    drag also reads as non-idle and must not count as the user scrolling).
 */
class NudgePolicy(private val nowMs: () -> Long) {
    private var lastNudgeKey = 0
    private var lastNudgeAt = Long.MIN_VALUE / 2
    private var chain = 0
    private var cooldownUntil = 0L
    private var graceUntil = 0L

    /** Per-tick scroll-state observation; non-idle = the user is moving. */
    fun onScrollObserved(idle: Boolean) {
        if (idle) return
        val now = nowMs()
        if (now < graceUntil) return
        chain = 0
        lastNudgeKey = 0
    }

    /** True when a nudge may fire for the currently-centered cell. */
    fun shouldNudge(centerKey: Int): Boolean {
        val now = nowMs()
        if (cooldownUntil > 0 && now >= cooldownUntil) {
            chain = 0
            cooldownUntil = 0L
        }
        if (now < cooldownUntil) return false
        if (now - lastNudgeAt < MIN_GAP_MS) return false
        if (centerKey != 0 && centerKey == lastNudgeKey) return false
        return true
    }

    fun onNudged(centerKey: Int) {
        val now = nowMs()
        lastNudgeKey = centerKey
        lastNudgeAt = now
        chain++
        graceUntil = now + NUDGE_GRACE_MS
        if (chain >= MAX_CHAIN) cooldownUntil = now + COOLDOWN_MS
    }

    /** Budget returned from exhausted: fresh slate for the next episode. */
    fun onBudgetRestored() {
        chain = 0
        lastNudgeKey = 0
        cooldownUntil = 0L
    }

    companion object {
        const val MIN_GAP_MS = 3000L
        const val MAX_CHAIN = 3
        const val COOLDOWN_MS = 10000L
        const val NUDGE_GRACE_MS = 1500L
    }
}
