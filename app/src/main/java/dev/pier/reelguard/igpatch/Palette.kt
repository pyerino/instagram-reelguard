package dev.pier.reelguard.igpatch

import android.graphics.Color

/** Shared dark palette for the in-IG surfaces (argb, no literal-sign traps). */
object Palette {
    val SCRIM = Color.argb(0x99, 0x10, 0x10, 0x10)
    val CARD_BACKGROUND = Color.argb(0xF2, 0x18, 0x18, 0x1C)
    val TEXT_PRIMARY = Color.argb(0xFF, 0xF4, 0xF4, 0xF6)
    val TEXT_SECONDARY = Color.argb(0xFF, 0xB9, 0xBD, 0xC7)
    val BUTTON_PRIMARY_BACKGROUND = Color.argb(0xFF, 0xF4, 0xF4, 0xF6)
    val BUTTON_PRIMARY_TEXT = Color.argb(0xFF, 0x15, 0x15, 0x19)
    val BUTTON_SECONDARY_BACKGROUND = Color.argb(0xFF, 0x33, 0x36, 0x3E)

    fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
