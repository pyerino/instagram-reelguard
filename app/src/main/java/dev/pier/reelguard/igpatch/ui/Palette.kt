package dev.pier.reelguard.igpatch.ui

/** Shared dark palette / density helpers for the in-IG surfaces. */
object Palette {
    const val SCRIM = 0x99101010
    const val CARD_BACKGROUND = 0xF218181C
    const val TEXT_PRIMARY = 0xFFF4F4F6
    const val TEXT_SECONDARY = 0xFFB9BDC7
    const val BUTTON_PRIMARY_BACKGROUND = 0xFFF4F4F6
    const val BUTTON_PRIMARY_TEXT = 0xFF151519
    const val BUTTON_SECONDARY_BACKGROUND = 0xFF33363E

    fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
