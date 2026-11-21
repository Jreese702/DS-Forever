package com.jrees.wowcompanion

/**
 * THE STICKY REALM'S ONE-TIME QUESTION (the add-on's Safety k10, the user's rulings 2026-11-20: "Realms work different in wow forever.
 * There isnt a dedicated realm like normal servers."; a character's name is unique there, the realm only where its settings already
 * live; when it is ambiguous "the bottom screen asks ONCE (remember the answer)"; nothing is moved or renamed).
 *
 * The add-on resolves the character's key itself and publishes it in the padmap's `dpad.who`: `n` the name and `r` the realm of the
 * STORED key (the settings' own "Name-Realm", never the realm the game reports when that differs), so [CharKey.of] here is always the
 * add-on's key and the app never picks a realm itself. While the key is ambiguous (one name holding settings under two or more realms
 * and nothing naming one) there is no `r` and `pk` lists the candidates ({ r = realm, n = settings count }); the app asks once on the
 * bottom screen, A picks, B = later (asked again at the next login: meanwhile no key, nothing is written). The answer goes back by the
 * padtab's third page (Book b74: 39, 38, 37, then 10 + i for candidate i, 9 for "a new character"); the add-on stores it as this
 * character's GUID record (WowCompanionDB.who) and never asks again.
 *
 * Also the WCSETUP "character key" line ([diagLine]): words and counts only, never a name, a realm or a GUID.
 * Pure (no Android).
 */
object KeyPick {
    /** one saved place a character's settings may live under: [realm] (the key "Name-[realm]") holding [count] settings */
    data class Cand(val realm: String, val count: Int)

    /** the add-on's sources of the key (Safety k10 WC.charKeySource), the diagnostic line's first word */
    val SOURCES = setOf("guid", "guid-id", "sticky", "live", "hint", "picked", "new", "ask", "none",
        "kept")                                                          // "kept": an add-on before k10
    val GUID_WORDS = setOf("same", "server-changed", "id-changed", "new", "unknown")

    /** the padtab's third page (Book b74): [index] 0-based in the candidates, null = "a new character" */
    fun cells(index: Int?): List<Pair<String, Int>> {
        val code = if (index == null) NEW_CODE else FIRST_CODE + index.coerceIn(0, MAX - 1)
        return listOf(PADTAB to 39, PADTAB to 38, PADTAB to 37, PADTAB to code)
    }
    const val PADTAB = "padtab"
    const val NEW_CODE = 9
    const val FIRST_CODE = 10
    /** the add-on lists at most this many (codes 10-19) */
    const val MAX = 10

    const val TITLE = "Which saved settings are this character's?"
    const val FOOTER = "Nothing is moved or renamed."
    const val NEW_CHAR = "New character"
    const val LATER = "Later"

    /** one choice's words: "Name - Realm, N settings" (the player's own bottom screen only; never logged) */
    fun label(name: String, c: Cand): String = if (c.count == 1) "$name - ${c.realm}, 1 setting" else "$name - ${c.realm}, ${c.count} settings"

    /** the choices in the add-on's order, then "New character" */
    fun options(name: String, cands: List<Cand>): List<String> = cands.take(MAX).map { label(name, it) } + NEW_CHAR

    /** the choice at [i] in [options]: the candidate's index, or null for "New character" */
    fun pickOf(i: Int, cands: List<Cand>): Int? = if (i in 0 until minOf(cands.size, MAX)) i else null

    enum class Act { NONE, WAIT, ASK, CLOSE }

    /**
     * what the app does now: [cands] the current padmap's question (empty = none, or the key is known), [open] the question shows or
     * waits for the controller, [later] the player said Later for this character in this game run, [sentAgoMs] since the last answer
     * was tapped (null = none: an answer the add-on has not taken yet is not asked over for [ANSWER_WAIT_MS]), [ready] Home free (no
     * room, no other prompt, no game window), [fight] in combat (never asked in a fight; an open question goes away and comes back)
     */
    fun decide(cands: List<Cand>, open: Boolean, later: Boolean, sentAgoMs: Long?, ready: Boolean, fight: Boolean): Act = when {
        cands.isEmpty() -> if (open) Act.CLOSE else Act.NONE
        open -> if (fight) Act.CLOSE else Act.NONE
        later -> Act.NONE
        sentAgoMs != null && sentAgoMs in 0 until ANSWER_WAIT_MS -> Act.WAIT
        fight || !ready -> Act.WAIT
        else -> Act.ASK
    }
    const val ANSWER_WAIT_MS = 10_000L

    /**
     * the WCSETUP line (Safety k10 / PadMap m180): "character key: <source>, realm reported|empty, hint present|none, name candidates N,
     * unclaimed same-class N, guid same|server-changed|id-changed|new"; only the add-on's own words and counts (an unknown word is left
     * out), never a name or realm; null = no source said
     */
    fun diagLine(d: PadMapModel.Dpad?): String? {
        val rs = d?.realmSource?.takeIf { it in SOURCES } ?: return null
        val b = StringBuilder(rs)
        d.realmReported?.takeIf { it == "reported" || it == "empty" }?.let { b.append(", realm ").append(it) }
        d.realmHint?.takeIf { it == "present" || it == "none" }?.let { b.append(", hint ").append(it) }
        d.nameCands?.let { b.append(", name candidates ").append(it) }
        d.unclaimed?.let { b.append(", unclaimed same-class ").append(it) }
        d.guidState?.takeIf { it in GUID_WORDS }?.let { b.append(", guid ").append(it) }
        return "character key: $b"
    }
}
