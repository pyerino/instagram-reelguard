package dev.pier.reelguard.core

/** Time source abstraction; every budget computation is injectable and testable. */
fun interface Clock {
    fun nowMillis(): Long
}
