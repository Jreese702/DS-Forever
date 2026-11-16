package com.jrees.wowcompanion

/**
 * uneq1: A WORN PIECE'S MORE MENU in the Character room (the user's picks, 2026-11-13: "Unequip lives in the Character room: choose a
 * worn slot, Y = More opens a small menu with 'Unequip' first (A does it, B back, Up/Down moves; touch: a More card button with the Y
 * glyph)"; "WEAPONS (slots 16,17,18) MAY be unequipped in combat, armor is refused in combat"; "bags full -> row disabled 'Bags full';
 * ammo (slot 0) not offered"). The rules, pure: which rows, which are usable and why not, the keys, the taps and the answer's words.
 * The move itself is Bags.lua's WC.bagsUnequip, reached by the add-on's arm cell (Character.lua `uneq`) then bmvq column slot - 1.
 *
 * char-more (the user, 2026-11-13: "if unequip is the only thing in this more menu then what even is the point? we should have options
 * there like unequip, replace and enchant"): three rows, Unequip, Replace ([WornReplace]: the bags' pieces for THIS slot) and Enchant
 * (the kits that fit it, [KitFit]); a row that cannot be used is drawn disabled with its terse reason.
 *
 * bags-gear1 THE EQUIP SLIDE-OUT (the user, 2026-11-16: "X opens a slide-out of ARMOR (item icons) next to the item. It lists the options
 * for that slot, plus a blank entry that unequips."; "The Y text menu stays for enchant and bind (use-effect items)."): Replace and
 * Unequip leave the Y menu for X's slide-out ([SlideOut]; its blank keeps Unequip's rule, [rows]). Y is [yRows]: Enchant, and Bind to
 * a Button when the piece has a use effect; Enchant alone = Y opens the kit list at once ([kitsAtOnce]).
 */
object WornMore {
    const val UNEQUIP = "Unequip"
    const val ENCHANT = "Enchant"
    const val BIND = "Bind to a Button"
    /** main hand, off hand, ranged / relic: the only pieces tried in a fight */
    val WEAPONS = setOf(16, 17, 18)

    /** one row: its label, and when it cannot be used the terse reason drawn beside it */
    data class Row(val label: String, val usable: Boolean, val why: String? = null)

    /**
     * UNEQUIP'S RULE for worn [slot] (uneq1; bags-gear1: the slide-out's blank): one row. Empty = none at all (ammo, slot 0, or not a worn
     * slot). [freeGeneral] = the bags document's free slots in plain bags (null = not known yet: the add-on judges); [routed] = the
     * add-on's arm cell and Bags' bmvq row are there.
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

    /**
     * bags-gear1 THE Y MENU of worn [slot] ("The Y text menu stays for enchant and bind (use-effect items)."): Enchant (the kits that
     * may go on it: [kits] in the bags at all, [kitsFit] of them not refused by this piece, [KitFit]; char-more2: [window] = a game window
     * would sell / deposit / attach the kit), then Bind to a Button when the piece has a use effect ([use]; out of a fight, [bindRouted]
     * = the add-on's Bind arm and Bags' bmvq row are there). Empty = no menu (nothing worn, ammo, not a worn slot).
     */
    fun yRows(slot: Int, worn: Boolean, use: Boolean, combat: Boolean, kits: Int, kitsFit: Int, window: Boolean,
              bindRouted: Boolean): List<Row> {
        if (slot !in 1..19 || !worn) return emptyList()
        val ew = when {
            window -> "Window open"
            kits <= 0 -> "No kits"
            kitsFit <= 0 -> "Nothing fits"
            else -> null
        }
        val out = arrayListOf(Row(ENCHANT, ew == null, ew))
        if (use) {
            val bw = when {
                combat -> "In combat"
                !bindRouted -> "Unavailable"
                else -> null
            }
            out.add(Row(BIND, bw == null, bw))
        }
        return out
    }

    /** G8 (the user on a one-row menu: "what even is the point?"): Enchant alone = Y opens the kit list at once, the button says Enchant */
    fun kitsAtOnce(rows: List<Row>): Boolean = rows.size == 1 && rows[0].label == ENCHANT

    enum class Act { NONE, CHOOSE, BACK }

    /**
     * b-fix (the user on build 1462: "pressing B in the more menu closes the character page and leaves the menu open."): does B on the
     * pad leave the room for Home NOW (the app's own B reader, CompanionHost.bPressed)? Not while something of the room's own is open
     * ([open]: the More menu, its Replace / Enchant list, the kit list, the save question): the add-on holds B then and closes it, one
     * step back; the second B leaves. Not while the game's own question is up ([gameAsks]: B answers it). Only when B still reaches the
     * game's window although a menu is open ([bToGame]: the capture refused to take B off it, in a fight) does it leave as before.
     */
    fun bLeadsHome(open: Boolean, gameAsks: Boolean, bToGame: Boolean): Boolean = when {
        gameAsks -> false
        open -> bToGame
        else -> true
    }

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

    /** the add-on's answer (eqs ack `unequip`; bags-gear1: `bind`) as the room's one line; null = done, nothing to say (the doll shows it) */
    fun say(ok: Boolean, why: String): String? = if (ok) null else when (why) {
        "full" -> "Bags full"
        "combat" -> "In combat"
        "empty" -> "Empty"
        "settle", "cursor", "late", "busy", "lost" -> "Try again"
        "unsupported", "no item", "bad", "notready" -> "Unavailable"
        else -> "The game refused it"
    }
}
