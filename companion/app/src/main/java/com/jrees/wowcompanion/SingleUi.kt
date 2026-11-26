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
 * | quest tracker | ON - OFF while the play docks are built (Home's own focused-quest card stands under the map instead) |
 * | controller UI ("hud") | OFF: no Nintendo bar, no stock bar (our Wings stand there in and out of fights: ActionBar nbar28) |
 * | buffs | ON |
 * | XP bar | OFF (Home's room row slides up in its place out of fights, our XP track on it) |
 * | NPC windows ("dialogs") | OFF (ours, over the game: the user's pick "Ours over the game") |
 * | the game's cast bar | shown - hidden once the add-on says single screen mode is on ([addonSingle]: ActionBar nbar25's top cast bar has every cast) |
 *
 * THE 1535 POLISH (the user on test 1533, 2026-11-26: "the Nintendo bar is BAD and I hate it. I love the top screen bar that we just
 * built"; "The normal quest list should stay hidden and instead it should show our focused quest box like it does on the bottom screen";
 * "We need to hide the top screen xp bar so that we can recreate our home screen row buttons"): the controller UI and the XP bar OFF, the
 * tracker OFF with the docks. Every one is hidden by the add-on's own alpha switches (Hub's element words, Tracker.lua's alpha), never a
 * CVar, and comes back as saved the moment the mode is off (two screens read the saved modes) or the add-on reloads without the app.
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
        "minimap", "chat", "tracker" -> if (docks) UiMode.OFF else UiMode.ON
        HudSplit.CONTROLLER_ID -> UiMode.OFF
        HudSplit.BUFFS_ID -> UiMode.ON
        HudSplit.XP_ID -> UiMode.OFF
        "dialogs" -> UiMode.OFF
        else -> saved
    }

    /** the game's cast bar hidden in single screen mode ([saved] = the player's Hide, unused: one bar of ours or the game's, always one) */
    @Suppress("UNUSED_PARAMETER")
    fun castBarHide(saved: Boolean): Boolean = addonSingle

    /** tests */
    internal fun reset() { docks = false; addonSingle = false }
}
