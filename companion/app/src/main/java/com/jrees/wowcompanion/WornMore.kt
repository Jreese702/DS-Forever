package com.jrees.wowcompanion

/**
 * uneq1: A WORN PIECE'S MORE MENU in the Character room (the user's picks, 2026-11-13: "Unequip lives in the Character room: choose a
 * worn slot, Y = More opens a small menu with 'Unequip' first (A does it, B back, Up/Down moves; touch: a More card button with the Y
 * glyph)"; "WEAPONS (slots 16,17,18) MAY be unequipped in combat, armor is refused in combat"; "bags full -> row disabled 'Bags full';
 * ammo (slot 0) not offered"). The rules, pure: which rows, which are usable and why not, the keys, the taps and the answer's words.
 * The move itself is Bags.lua's WC.bagsUnequip, reached by the add-on's arm cell (Character.lua `uneq`) then bmvq column slot - 1.
 */
object WornMore {
    const val UNEQUIP = "Unequip"
    /** main hand, off hand, ranged / relic: the only pieces tried in a fight */
    val WEAPONS = setOf(16, 17, 18)

    /** one row: its label, and when it cannot be used the terse reason drawn beside it */
    data class Row(val label: String, val usable: Boolean, val why: String? = null)

    /**
     * The rows for worn [slot]. Empty = no menu at all (ammo, slot 0, or not a worn slot). [freeGeneral] = the bags document's free
     * slots in plain bags (null = not known yet: the add-on judges); [routed] = the add-on's arm cell and Bags' bmvq row are there.
     */
    fun rows(slot: Int, worn: Boolean, combat: Boolean, freeGeneral: Int?, routed: Boolean): List<Row> {
        if (slot !in 1..19) return emptyList()
        val why = when {
            !worn -> "Empty"
            combat && slot !in WEAPONS -> "In combat"
            freeGeneral != null && freeGeneral <= 0 -> "Bags full"
            !routed -> "Unavailable"
            else -> null
        }
        return listOf(Row(UNEQUIP, why == null, why))
    }

    enum class Act { NONE, CHOOSE, BACK }

    /** a press while the menu is open: Up / Down move (clamped), A chooses, B goes back one step. (new selection, what to do) */
    fun key(sel: Int, n: Int, key: String): Pair<Int, Act> = when (key) {
        "PADDUP" -> (sel - 1).coerceAtLeast(0) to Act.NONE
        "PADDDOWN" -> (sel + 1).coerceAtMost(maxOf(0, n - 1)) to Act.NONE
        "PAD1" -> sel to Act.CHOOSE
        "PAD2" -> sel to Act.BACK
        else -> sel to Act.NONE
    }

    /** the taps of one unequip, in ONE batch (all or nothing): the arm on the add-on's row, then the worn slot's bmvq column */
    fun cells(row: String, arm: Int, slot: Int): List<Pair<String, Int>> = listOf(row to arm, "bmvq" to slot - 1)

    /** uneq2: how long after this room's own unequip tap its answer is still said (the 0.6 s confirm and a late block fit well inside) */
    const val ANSWER_MS = 5000L

    /** uneq2: an `unequip` ack is this room's answer only when its tap went out at [sentAt] (uptime ms, 0 = none) within [ANSWER_MS]
     *  of [now]; the add-on re-sends its last ack in every eqs part, so anything else is an old answer and is never said */
    fun fresh(sentAt: Long, now: Long): Boolean = sentAt > 0L && now >= sentAt && now - sentAt <= ANSWER_MS

    /** the add-on's answer (eqs ack `unequip`) as the room's one line; null = done, nothing to say (the doll shows it) */
    fun say(ok: Boolean, why: String): String? = if (ok) null else when (why) {
        "full" -> "Bags full"
        "combat" -> "In combat"
        "empty" -> "Empty"
        "settle", "cursor", "late" -> "Try again"
        else -> "The game refused it"
    }
}
