package dev.pier.reelguard.igpatch.ui

/**
 * Embedded modules carry no resources: the dex is merged into the host and any
 * R.string resolves against the HOST table (wrong entries). All user visible
 * strings therefore live here.
 */
object Str {
    const val OVERLAY_CLOSE_REELS = "Close reels"
    const val OVERLAY_EXTEND = "+5 min today"
    const val OVERLAY_SETTINGS = "ReelGuard settings"

    const val SETTINGS_TITLE = "ReelGuard"
    const val SETTINGS_LIMIT_LABEL = "Daily limit"
    const val SETTINGS_SURFACES_LABEL = "Surfaces"
    const val SETTINGS_SURFACE_OVERLAY_HINT = "counted · overlay on limit"
    const val SETTINGS_FEED_HINT = "counted · never overlays"
    const val SETTINGS_RESET = "Reset today"
    const val SETTINGS_DONE = "Done"

    const val SURFACE_REELS_TAB_TITLE = "Reels tab"
    const val SURFACE_DM_PLAYER_TITLE = "DM player"
    const val SURFACE_FEED_REEL_TITLE = "Feed reel"

    fun overlayTitle(used: Int, limit: Int) = "Reels · daily limit reached ($used/$limit min)"
    fun overlayCaption(used: Int, limit: Int) = "today: $used / $limit min"
    fun minutes(n: Int) = "$n min"
}
