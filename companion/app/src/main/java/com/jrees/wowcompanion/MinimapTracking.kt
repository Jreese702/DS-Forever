package com.jrees.wowcompanion

/**
 * ISSUE #87 (the user's ruling, 2026-11-19: "accept that the live dots are gone"): since game build 1.60.1.70862 (2026-11-08) the
 * minimap answers the add-on's pretend mouse with zero-line tooltips, so Map.lua's minimap probe found nothing and the live herb /
 * ore dots (and the chests and NPCs it saw) went blank. The probe is removed (Map.lua mp3); the game's top-screen minimap is never
 * forced on. Where the live dots' switches were (Home's map filter, its Gathering & rares tab) one terse line sends players to the
 * game's own minimap tracking (Find Herbs / Find Minerals); with it on, Home's map still draws QuestieDB's herb / ore spawn spots
 * (map.track). What stays of the probe's options: Sparkles (bit 4), the soft-target icons, a game setting the add-on remembers.
 */
object MinimapTracking {
    /** the line under the filter's cells (translated: es / fr in companion/i18n) */
    const val ADVICE = "Herbs/ore: use the game's minimap tracking"

    /** the Sparkles bit of the pin lane's op 2 (Map.lua WC.mmp.setOpt) */
    const val SPARKLES = 4

    /** a kept "mapprobe" opts value -> what is asked now: the Sparkles bit only (1 / 2, the old probe / auto-switch, read as nothing) */
    fun wanted(stored: Int): Int = stored and SPARKLES
}
