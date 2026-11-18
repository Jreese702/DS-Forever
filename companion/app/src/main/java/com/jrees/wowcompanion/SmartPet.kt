package com.jrees.wowcompanion

/**
 * SMART PET (the user, 2026-11-17: "The pet follow on B thing works, but its not attacking the next thing that I target and attack. I feel
 * like maybe it needs to be a full smart pet feature where it stops on b and then starts attacking whatever that I target and start attack
 * on"; the picks "Attack + harmful spells" and "Off by default"; then "to be clear I think this should just be a smart pet feature that
 * includes both of those settings in one"). ONE row on the Controller tab, per character, a pet class only, OFF by default, that REPLACES
 * "B: pet follow" (2026-11-16) and covers both halves:
 *  - B calls the attacking pet back (the add-on's m165 / m171: the game's /petfollow first on B, Home's rest layout, nothing of ours
 *    holding) - only while Home has nothing of its own open that B closes (a module full screen, the buff list, the report's notice, the
 *    mail card - HomeView.bClosesSomething);
 *  - the pet attacks your target when you start attacking or cast a harmful spell (PadMap m172: the game's secure /petattack in the
 *    press's own text, on our routes; whatever Home shows).
 * THE WORD (Book b67): padtab 39 then [OFF_CODE] 36 = off, [BOTH_CODE] 37 = on with Home free (both halves), [ATTACK_CODE] 0 = on with
 * Home busy (the attack half only). The add-on keeps it in memory only and says it back (`pf.on` / `pf.bf`), so the app taps whenever a
 * current padmap disagrees - at every connect, after a change, after a /reload - at most every [RESEND_MS] for the same value. A class
 * with no pet: no row, never told (no taps). Pure.
 *
 * MIGRATION (HomeSettings.smartPet): a character with "B: pet follow" SAVED keeps that value; nothing saved = Off (the user's pick). Note:
 * B: pet follow never saved an "On" over its default (its setter skipped a value equal to the default), so a character that only ever had
 * it on by default - or tapped On without an Off before - now starts Off.
 */
object SmartPet {
    const val ROW_ID = "Smart pet"
    val SEGMENTS = listOf("Off", "On")
    const val PADTAB = "padtab"
    const val PICK = 39
    const val OFF_CODE = 36
    const val BOTH_CODE = 37
    const val ATTACK_CODE = 0
    const val RESEND_MS = 3_000L

    /** the word's three values */
    const val OFF = 0
    const val ATTACK = 1           // on, Home busy: the pet's attack only (B is Home's own)
    const val BOTH = 2             // on, Home free: B's pet follow too

    /** the row's one line under its name (terse) */
    fun line(on: Boolean): String = if (on) "B calls your pet back. Your attacks send it in." else "Your pet is left alone."

    /** the row is offered: an add-on with the switch says this character is a pet class */
    fun shown(pf: PadMapModel.PetB?): Boolean = pf?.cls == true

    /** the word the add-on should hold now: [setting] this character's choice, a pet class; B's half only with Home free */
    fun want(setting: Boolean, pf: PadMapModel.PetB?, homeFree: Boolean): Int = when {
        !setting || !shown(pf) -> OFF
        homeFree -> BOTH
        else -> ATTACK
    }

    /** the word the add-on holds now (its padmap's `pf`) */
    fun held(pf: PadMapModel.PetB): Int = when {
        !pf.on -> OFF
        pf.bfree -> BOTH
        else -> ATTACK
    }

    /** the per-character key of the padmap's own character (its `dpad.who`), GameUi.character's form "Name-Realm"; null = no name (no tell) */
    fun who(name: String?, realm: String?): String? = name?.takeIf { it.isNotEmpty() }?.let { "$it-${realm ?: ""}" }

    /** the taps that tell the add-on [word] */
    fun cells(word: Int): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to when (word) {
        BOTH -> BOTH_CODE
        ATTACK -> ATTACK_CODE
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
