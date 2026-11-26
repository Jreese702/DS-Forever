package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE'S TOP-SCREEN PRESET (SingleScreen; the user's pick for "While playing", 2026-11-25: the game's own HUD plus our
 * Nintendo bar; in fights the Wings and our top cast bar; "1, 2, and also the chat on the left thr ds toolbar should have the same
 * functionality in single screen mode"): while the mode is on, GameUi decides the GAME's elements by these modes ([GameUi.effMode])
 * instead of the saved ones - which stay saved and drawn in Settings, never written here. Two screens: never read.
 *
 * | element | here |
 * |---|---|
 * | minimap, chat window | ON - OFF while the play docks are built ([docks]: Home's own map and chat stand over the game instead) |
 * | quest tracker | ON (the game draws it) |
 * | controller UI ("hud") | NINTENDO (the stock gamepad bar replaced by our Nintendo bar: `uisw` NBAR_ON) |
 * | buffs, XP bar | ON |
 * | NPC windows ("dialogs") | OFF (ours, over the game: the user's pick "Ours over the game") |
 * | the game's cast bar | shown - hidden once the add-on says single screen mode is on ([addonSingle]: ActionBar nbar25's top cast bar has every cast) |
 *
 * Pure (no android.*).
 */
object SingleUi {
    /** the play docks are built (the single-mode host's SingleDocks, CompanionHost's PLAY wiring): the game's minimap and chat give way */
    @Volatile var docks = false
    /** the add-on's `ss` says 1 (PadMap m199, SingleTop): our top cast bar shows every cast, so the game's own can hide */
    @Volatile var addonSingle = false

    /** the mode element [id] has in single screen mode; [saved] = the player's own choice (kept for anything not listed) */
    fun mode(id: String, saved: UiMode): UiMode = when (id) {
        "minimap", "chat" -> if (docks) UiMode.OFF else UiMode.ON
        "tracker" -> UiMode.ON
        HudSplit.CONTROLLER_ID -> UiMode.NINTENDO
        HudSplit.BUFFS_ID, HudSplit.XP_ID -> UiMode.ON
        "dialogs" -> UiMode.OFF
        else -> saved
    }

    /** the game's cast bar hidden in single screen mode ([saved] = the player's Hide, unused: one bar of ours or the game's, always one) */
    @Suppress("UNUSED_PARAMETER")
    fun castBarHide(saved: Boolean): Boolean = addonSingle

    /** tests */
    internal fun reset() { docks = false; addonSingle = false }
}
