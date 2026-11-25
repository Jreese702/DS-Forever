package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE'S TOP-SCREEN PRESET (SingleScreen; the user's pick for "While playing", 2026-11-25: the game's own HUD plus our
 * Nintendo bar; in fights the Wings and our top cast bar): while the mode is on, GameUi decides the GAME's elements by these modes
 * ([GameUi.effMode]) instead of the saved ones - which stay saved and drawn in Settings, never written here. Two screens: never read.
 *
 * | element | here |
 * |---|---|
 * | minimap, quest tracker, chat window | ON (the game draws them: nothing of ours replaces them on one screen yet; PLAY's docks may) |
 * | controller UI ("hud") | NINTENDO (the stock gamepad bar replaced by our Nintendo bar: `uisw` NBAR_ON) |
 * | buffs, XP bar | ON |
 * | NPC windows ("dialogs") | OFF (ours, over the game: the user's pick "Ours over the game") |
 * | the game's cast bar | shown (PLAY hides it once our top cast bar has every cast) |
 *
 * Pure (no android.*). The PLAY builder refines it (the docks, the add-on's `ss`).
 */
object SingleUi {
    /** the mode element [id] has in single screen mode; [saved] = the player's own choice (kept for anything not listed) */
    fun mode(id: String, saved: UiMode): UiMode = when (id) {
        "minimap", "tracker", "chat" -> UiMode.ON
        HudSplit.CONTROLLER_ID -> UiMode.NINTENDO
        HudSplit.BUFFS_ID, HudSplit.XP_ID -> UiMode.ON
        "dialogs" -> UiMode.OFF
        else -> saved
    }

    /** the game's cast bar hidden in single screen mode ([saved] = the player's Hide): shown for now (no bar of ours has every cast) */
    @Suppress("UNUSED_PARAMETER")
    fun castBarHide(saved: Boolean): Boolean = false
}
