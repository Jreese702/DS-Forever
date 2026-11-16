package com.jrees.wowcompanion

/**
 * B: PET FOLLOW (the user, 2026-11-16: "I want a pet class specific option where pressing B in the out of combat home menu basically stops
 * the pet attacking"; the picks: B does the game's own Pet Follow - it stops attacking, comes back, keeps its stance - a Settings row on the
 * Controller tab, on by default for a pet class, hidden for the others, saved per character).
 *
 * WHAT B DID THERE BEFORE, and still does: the game's own Back (stop casting, else stop targeting, else clear the target). The add-on
 * (PadMap m165) puts the game's /petfollow first on the same press, through the router's own secure B button, only while:
 *  - the app's word is on: this character's setting ([HomeSettings.petFollowB]) AND Home with nothing of its own open that B closes (a
 *    module full screen, the buff list, the report's notice) - [want];
 *  - a pet class (the padmap's `pf.cls`: Hunter, Warlock, Death Knight only - the review: a Priest's Mind Control is no pet class) and a
 *    live pet out that is at something (a live pet target: it is attacking or on its way; a following or Stay-parked pet is left alone);
 *  - nothing of ours holding and no game window (a room, a wheel, a pop-up: the router stands aside), out of combat, the rest layout.
 * In a fight (and while the fight layout shows) B keeps its fight rules. The add-on keeps the word in memory only and says it back
 * (`pf.on`), so the app taps padtab 39 then 36 / 37 (Book b66) whenever a current padmap disagrees - at every connect, after a change,
 * after a /reload - at most every [RESEND_MS] for the same value. A class with no pet: no row, never told (no taps). Pure.
 */
object PetFollowB {
    const val ROW_ID = "B: pet follow"
    val SEGMENTS = listOf("Off", "On")
    const val PADTAB = "padtab"
    const val PICK = 39
    const val OFF_CODE = 36
    const val ON_CODE = 37
    const val RESEND_MS = 3_000L

    /** the row's one line under its name (terse) */
    fun line(on: Boolean): String = if (on) "Home, out of combat: B calls your pet back." else "B keeps its own action."

    /** the row is offered: an add-on with the switch says this character is a pet class */
    fun shown(pf: PadMapModel.PetB?): Boolean = pf?.cls == true

    /** the word the add-on should hold now: [setting] this character's choice, a pet class, and Home with nothing of its own open */
    fun want(setting: Boolean, pf: PadMapModel.PetB?, homeFree: Boolean): Boolean = setting && shown(pf) && homeFree

    /** the per-character key of the padmap's own character (its `dpad.who`), GameUi.character's form "Name-Realm"; null = no name (no tell) */
    fun who(name: String?, realm: String?): String? = name?.takeIf { it.isNotEmpty() }?.let { "$it-${realm ?: ""}" }

    /** the taps that tell the add-on [on] */
    fun cells(on: Boolean): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to if (on) ON_CODE else OFF_CODE)

    /** whether the add-on must be told [want] now: [pf] the current padmap's (null = an add-on before m165: nothing to tell); the same
     *  value is sent again only after [RESEND_MS] ([lastSent] / [lastSentAt]: the last value told and when, 0 = never) */
    fun needsTell(want: Boolean, pf: PadMapModel.PetB?, lastSent: Boolean?, lastSentAt: Long, now: Long): Boolean {
        if (pf == null || pf.on == want) return false
        return lastSent != want || lastSentAt == 0L || now - lastSentAt >= RESEND_MS
    }

    /** the "What it does" picture's title and words */
    fun caption(on: Boolean): List<String> = if (on) listOf("Home, out of combat, your pet attacking:", "B calls it back to follow you.")
        else listOf("B keeps its own action.")
}
