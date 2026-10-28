package dev.pier.reelguard.igpatch.data

/**
 * Detection rules embedded as a constant: module code runs inside the host
 * process where apk-resource lookups are fragile. Harvested per
 * docs/DETECTION.md — re-run that runbook after any Instagram update.
 */
object EmbeddedRules {
    val DETECTION_JSON: String = """
{
  "schemaVersion": 1,
  "packageNames": ["com.instagram.android"],
  "igVersionName": "453.0.0.55.79",
  "igVersionCode": 385914243,
  "source": "static smali/resource analysis corrected by live uiautomator-tree passes on 453.0.0.55.79 (runtime trees beat static inference); re-harvest runbook in docs/DETECTION.md",
  "notes": "Single-window fragment architecture: surfaces are classified from accessibility view-id sets only, never from activity/component names. Live-verified on 453.0.0.55.79: (1) while a clip plays NO play/pause nodes exist; com.instagram.android:id/clips_pause_button appears only while paused - in the reels tab AND the DM viewer; (2) the static ids clips_video_player / clips_ufi_like_button / video_player_view / player_controls_* do NOT occur in any runtime tree - replaced by the ids below; (3) the DM viewer shares the whole clips* viewer shell (root_clips_layout, clips_video_container) plus DM chrome (reply_bar_container, reel_viewer_message_composer, sender_*), so reels_tab vetoes on reply bars and dm_player requires them; (4) feed videos expose video_container/video_states, no clips ids. Picture-in-picture reels (ClipsPiPInternalActivity) are a separate window, not counted in v1.",
  "surfaces": {
    "reels_tab": {
      "coreIdHints": [
        "com.instagram.android:id/clips_video_container"
      ],
      "idHints": [
        "com.instagram.android:id/root_clips_layout",
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/clips_media_component",
        "com.instagram.android:id/clips_ufi_component",
        "com.instagram.android:id/clips_ufi_more_button_component",
        "com.instagram.android:id/scrubber",
        "com.instagram.android:id/tab_bar"
      ],
      "negativeIdHints": [
        "clips_action_bar_*",
        "com.instagram.android:id/story_item_top_progress_bar_stub",
        "com.instagram.android:id/reply_bar_container",
        "com.instagram.android:id/reel_viewer_message_composer"
      ],
      "pausedWhenIdPresent": "com.instagram.android:id/clips_pause_button",
      "windowActivityPatterns": [
        "com.instagram.mainactivity.InstagramMainActivity",
        "com.instagram.android.activity.MainTabActivity"
      ],
      "textOrDescHints": [],
      "notes": "Fragment inside the main tab shell (ViewPager2 page); no dedicated activity. tab_bar co-present (dm viewer covers it). clips_action_bar_* is the camera/editor family; story stubs are Stories; reply-bar negatives keep the DM viewer (same clips shell) from classifying as reels_tab.",
      "samples": [
        {
          "label": "reels tab, playing (live tree 15:19)",
          "presentIds": [
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/root_clips_layout",
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_media_component",
            "com.instagram.android:id/tab_bar"
          ],
          "expectPlaying": true
        },
        {
          "label": "reels tab, paused (live tree, pause overlay up)",
          "presentIds": [
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/root_clips_layout",
            "com.instagram.android:id/clips_pause_button",
            "com.instagram.android:id/tab_bar"
          ],
          "expectPlaying": false
        }
      ]
    },
    "dm_player": {
      "coreIdHints": [
        "com.instagram.android:id/root_clips_layout",
        "com.instagram.android:id/reply_bar_container"
      ],
      "idHints": [
        "com.instagram.android:id/reel_viewer_message_composer",
        "com.instagram.android:id/sender_username_or_fullname",
        "com.instagram.android:id/sender_timestamp",
        "com.instagram.android:id/scrubber",
        "com.instagram.android:id/clips_media_component",
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/reply_bar_emoji_button_inside_composer"
      ],
      "negativeIdHints": [],
      "pausedWhenIdPresent": "com.instagram.android:id/clips_pause_button",
      "windowActivityPatterns": [
        "com.instagram.mainactivity.InstagramMainActivity",
        "com.instagram.modal.ModalActivity",
        "com.instagram.modal.TransparentModalActivity"
      ],
      "textOrDescHints": [],
      "notes": "Reel shared in a DM opens the same clips viewer inside a modal host, plus the reply bar / sender header. Same paused-marker as reels. The static player_controls_*/video_player_view ids never appear in this tree.",
      "samples": [
        {
          "label": "dm viewer, playing (live tree 15:24)",
          "presentIds": [
            "com.instagram.android:id/root_clips_layout",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/reply_bar_container",
            "com.instagram.android:id/reel_viewer_message_composer",
            "com.instagram.android:id/clips_media_component"
          ],
          "expectPlaying": true
        },
        {
          "label": "dm viewer, paused",
          "presentIds": [
            "com.instagram.android:id/root_clips_layout",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/reply_bar_container",
            "com.instagram.android:id/clips_pause_button"
          ],
          "expectPlaying": false
        }
      ]
    },
    "feed_reel": {
      "coreIdHints": [
        "com.instagram.android:id/video_container"
      ],
      "idHints": [
        "com.instagram.android:id/video_states",
        "com.instagram.android:id/feed_tab",
        "com.instagram.android:id/tab_bar"
      ],
      "negativeIdHints": [
        "com.instagram.android:id/root_clips_layout",
        "com.instagram.android:id/reply_bar_container"
      ],
      "windowActivityPatterns": [
        "com.instagram.mainactivity.InstagramMainActivity"
      ],
      "textOrDescHints": [],
      "notes": "Inline autoplay in the home feed; counted only, never overlays (Surface.overlayEnforced=false). No trusted paused-marker -> counts as playing while visible (see service DEFAULT_PLAYING_WITHOUT_MARKER); video_container also appears in some non-feed surfaces, acceptable for a count-only surface.",
      "samples": [
        {
          "label": "feed with inline video (live tree 15:22)",
          "presentIds": [
            "com.instagram.android:id/video_container",
            "com.instagram.android:id/video_states",
            "com.instagram.android:id/feed_tab",
            "com.instagram.android:id/tab_bar"
          ],
          "expectPlaying": true
        }
      ]
    }
  }
}
"""
}
