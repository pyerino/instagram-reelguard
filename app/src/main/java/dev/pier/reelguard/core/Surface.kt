package dev.pier.reelguard.core

/**
 * Instagram surfaces ReelGuard distinguishes.
 *
 * Order is significant: surface detection evaluates top-down and the first match
 * wins. On v453 everything lives in one fragment-hosting window, so matching is
 * done on accessibility view-id sets, never on activity/component names.
 */
enum class Surface(val id: String, val budgetedByDefault: Boolean, val overlayEnforced: Boolean) {
    /** Full-screen reels player (IG Reels tab / reel viewer). */
    REELS_TAB("reels_tab", true, true),

    /** Reel/video opened from a DM conversation (full-screen player chrome). */
    DM_PLAYER("dm_player", true, true),

    /** Reel embedded in the home feed; time counts, no overlay, by definition. */
    FEED_REEL("feed_reel", true, false);

    companion object {
        fun fromIdOrNull(id: String): Surface? = entries.firstOrNull { it.id == id }
    }
}
