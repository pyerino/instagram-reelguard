package dev.pier.reelguard.core

import dev.pier.reelguard.igpatch.data.EmbeddedRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the embedded detection asset: it must parse, and every recorded
 * sample must classify to its surface with the expected playing verdict.
 * Mirrors the rules shipped in the companion app (single live-verified set).
 */
class DetectionRulesTest {

    private val rules: DetectionRules = DetectionRules.fromJson(EmbeddedRules.DETECTION_JSON)

    @Test
    fun `embedded rules parse with expected shape`() {
        assertEquals(1, rules.schemaVersion)
        assertTrue(rules.packageNames.contains("com.instagram.android"))
        assertTrue(rules.igVersionName.startsWith("453"))

        val reels = rules.surfaces.getValue("reels_tab")
        assertEquals("com.instagram.android:id/clips_pause_button", reels.pausedWhenIdPresent)

        val ids = rules.referencedViewIds()
        assertTrue(ids.contains("com.instagram.android:id/clips_pause_button"))
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every recorded sample classifies to its surface`() {
        for (surface in Surface.entries) {
            val rule = rules.surfaces[surface.id] ?: continue
            for (sample in rule.samples) {
                val presentIds = sample.presentIds.toSet()
                val detected = rules.detect(presentIds)
                assertEquals("sample '${sample.label}' of ${surface.id}", surface, detected)
                val playing = detected?.let { rules.playingFrom(it, presentIds) }
                if (playing != null) {
                    assertEquals("playing verdict for '${sample.label}'", sample.expectPlaying, playing)
                }
            }
        }
    }

    @Test
    fun `reels veto keeps the dm viewer out of the reels verdict`() {
        // The DM viewer shares the clips shell; its reply bar must flip the
        // verdict to dm_player, never reels_tab.
        val dmViewer = setOf(
            "com.instagram.android:id/root_clips_layout",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/reply_bar_container",
        )
        val detected = rules.detect(dmViewer)
        assertEquals(Surface.DM_PLAYER, detected)
        assertEquals(false, rules.playingFrom(Surface.DM_PLAYER, dmViewer + "com.instagram.android:id/clips_pause_button"))
    }

    @Test
    fun `foreign and empty trees detect nothing`() {
        assertEquals(null, rules.detect(emptySet()))
        assertEquals(null, rules.detect(setOf("com.whatsapp:id/conversations")))
    }
}
