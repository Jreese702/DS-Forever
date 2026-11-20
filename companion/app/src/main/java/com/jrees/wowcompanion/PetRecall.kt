package com.jrees.wowcompanion

/**
 * B: PET RECALL (the user, 2026-11-18: "I like the pet recall but I think we need to remove the pet smart attack, its obnoxious"; the
 * pick: rename to "B: pet recall", ON by default for pet classes, a player's saved choice kept). ONE row on the Controller tab, per
 * character, a pet class only. It was 2026-11-17's "Smart pet" (two halves); the attack half - the pet sent at your target when you
 * started attacking or cast a harmful spell (PadMap m172) - is GONE (PadMap m174). What is left is the recall, exactly as it was:
 *  - B calls the attacking pet back (the add-on's m165 / m171: the game's /petfollow first on B, Home's rest layout, nothing of ours
 *    holding) - only while Home has nothing of its own open that B closes (a module full screen, the buff list, the report's notice, the
 *    mail card - HomeView.bClosesSomething);
 *  - the first B in a fight (the give-back) recalls it too (m173), whatever Home shows.
 * THE WORD (Book b68): padtab 39 then [OFF_CODE] 36 = off, [ON_CODE] 37 = on with Home free, [BUSY_CODE] 0 = on with Home busy (B is
 * Home's own out of combat; only the fight's give-back B recalls). The add-on keeps it in memory only and says it back (`pf.on` /
 * `pf.bf`), so the app taps whenever a current padmap disagrees - at every connect, after a change, after a /reload - at most every
 * [RESEND_MS] for the same value. A class with no pet: no row, never told (no taps). Pure.
 *
 * DEFAULT + MIGRATION (HomeSettings.petRecall): a character's SAVED choice is kept (Smart pet's "smartPet.<who>", else B: pet follow's
 * "petFollowB.<who>"); nothing saved = ON. An add-on still holding Smart pet's "on" from an older app (37 / 0) is recall only: m174 has
 * no attack half to arm.
 */
object PetRecall {
    const val ROW_ID = "B: pet recall"
    val SEGMENTS = listOf("Off", "On")
    const val PADTAB = "padtab"
    const val PICK = 39
    const val OFF_CODE = 36
    const val ON_CODE = 37
    const val BUSY_CODE = 0
    const val RESEND_MS = 3_000L

    /** the word's three values */
    const val OFF = 0
    const val BUSY = 1             // on, Home busy: only the fight's give-back B recalls (B out of combat is Home's own)
    const val ON = 2               // on, Home free: B's rest-layout recall too

    /** the row's one line under its name (terse) */
    fun line(on: Boolean): String = if (on) "B calls your pet back." else "B does nothing to your pet."

    /** the row is offered: an add-on with the switch says this character is a pet class */
    fun shown(pf: PadMapModel.PetB?): Boolean = pf?.cls == true

    /** the word the add-on should hold now: [setting] this character's choice, a pet class; B's rest-layout recall only with Home free */
    fun want(setting: Boolean, pf: PadMapModel.PetB?, homeFree: Boolean): Int = when {
        !setting || !shown(pf) -> OFF
        homeFree -> ON
        else -> BUSY
    }

    /** the word the add-on holds now (its padmap's `pf`) */
    fun held(pf: PadMapModel.PetB): Int = when {
        !pf.on -> OFF
        pf.bfree -> ON
        else -> BUSY
    }

    /** the per-character key of the padmap's own character (its `dpad.who`), GameUi.character's form "Name-Realm"; null = no name or no
     *  realm yet (no tell; [CharKey]: never a partial "Name-" key) */
    fun who(name: String?, realm: String?): String? = CharKey.of(name, realm)

    /** the taps that tell the add-on [word] */
    fun cells(word: Int): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to when (word) {
        ON -> ON_CODE
        BUSY -> BUSY_CODE
        else -> OFF_CODE
    })

    /** whether the add-on must be told [want] now: [pf] the current padmap's (null = an add-on before m165: nothing to tell); the same
     *  value is sent again only after [RESEND_MS] ([lastSent] / [lastSentAt]: the last value told and when, 0 = never). [live]: the game
     *  is live (GameInput.live) - the 1.0.33 merge review: with the game closed or quiet no tap is tried */
    fun needsTell(want: Int, pf: PadMapModel.PetB?, lastSent: Int?, lastSentAt: Long, now: Long, live: Boolean = true): Boolean {
        if (!live || pf == null || held(pf) == want) return false
        return lastSent != want || lastSentAt == 0L || now - lastSentAt >= RESEND_MS
    }
}
