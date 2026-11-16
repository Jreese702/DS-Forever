package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * char-more REPLACE (the user, 2026-11-13: "we should have options there like unequip, replace and enchant"): the bags' pieces that can
 * go in ONE worn slot, as the game's own slot rules have it, and the pad cells that put one there. Pure (org.json only): WornReplaceTest.
 *
 *  - the slots of an equip location are BagsCompare's (rings and trinkets either slot; a one-hander in the off hand only for a class that
 *    dual wields, the equipped document's `dw`); a two-hander goes in the MAIN hand only (the game moves the off hand out itself);
 *  - a piece the player can't wear (the add-on's `nouse`: a level / class / proficiency the game refuses, or a red line on its kept
 *    tooltip) is not listed at all;
 *  - THE ROUTE: a piece whose only slot is this one goes on by its bag slot's own SECURE cell ("bag<bag>", the game's right-click: its
 *    equip, its bind question, weapons in a fight as the game allows). A piece with two possible slots (a ring, a trinket, a dual
 *    wielder's one-hander) goes in THIS slot by the Bags room's drag (the bag slot's pick, then the worn slot: EquipCursorItem(slot)),
 *    which the add-on refuses in a fight, so it is offered out of a fight only.
 *  - char-more2 (review): while a game window changes what a right-click does ([window]: the bags document's `ctx`, a vendor / bank /
 *    mail / trade / auction house) the secure cell would SELL / deposit / attach the piece, so every piece goes on by the drag then
 *    (its id checked by the add-on, nothing sold), and none in a fight.
 *  - bags-gear1 THE AMMO SLOT (the equip slide-out, the class-only ruling; the user, 2026-11-16: "It lists the options for that slot"):
 *    slot 0's pieces are the bags' ammo (`INVTYPE_AMMO`, one per kind, the kind worn now left out), each by its bag slot's secure cell
 *    (the game's right-click equips ammo), in a fight as well (the game decides); never by the drag (there is no worn-slot column for
 *    it), so not while a game window would sell it.
 */
object WornReplace {
    const val MAIN = 16
    const val OFF = 17
    const val AMMO = 0
    const val AMMO_LOC = "INVTYPE_AMMO"

    /** one bag piece for the slot: where it is, its bags row, and whether the game's own equip lands it in this slot (the secure cell) */
    data class Pick(val bag: Int, val slot: Int, val row: JSONObject, val exact: Boolean)

    /** the worn slots a piece of equip location [loc] can go in, for Replace */
    fun slotsOf(loc: String?, dualWield: Boolean): List<Int> =
        if (BagsCompare.twoHand(loc)) listOf(MAIN) else BagsCompare.slotsFor(loc, dualWield)

    /** the bags' pieces for worn [slot] ([items] = (bag, slot, bags row)); best item level first, then the bags' order.
     *  bags-gear1: slot 0 (ammo) = the bags' ammo, one stack per kind, [wornAmmo] (the kind worn now) left out */
    fun picks(items: List<Triple<Int, Int, JSONObject>>, slot: Int, dualWield: Boolean, wornAmmo: Int? = null): List<Pick> {
        if (slot == AMMO) return ammo(items, wornAmmo)
        if (slot !in 1..19) return emptyList()
        val out = ArrayList<Pick>()
        for ((bag, s, row) in items) {
            if (row.optBoolean("nouse")) continue
            val slots = slotsOf(row.optString("equip").takeIf { it.isNotEmpty() }, dualWield)
            if (slot !in slots) continue
            out.add(Pick(bag, s, row, slots.size == 1))
        }
        return out.sortedWith(compareByDescending<Pick> { it.row.optInt("ilvl", 0) }.thenBy { it.bag }.thenBy { it.slot })
    }

    /** bags-gear1: the ammo slot's pieces: the bags' ammo stacks, the first stack of each kind, the kind worn now left out */
    private fun ammo(items: List<Triple<Int, Int, JSONObject>>, wornAmmo: Int?): List<Pick> {
        val seen = HashSet<Int>()
        val out = ArrayList<Pick>()
        for ((bag, s, row) in items) {
            if (row.optString("equip") != AMMO_LOC || row.optBoolean("nouse")) continue
            val id = row.optInt("id", 0)
            if (id <= 0 || id == wornAmmo || !seen.add(id)) continue
            out.add(Pick(bag, s, row, true))
        }
        return out.sortedWith(compareByDescending<Pick> { it.row.optInt("ilvl", 0) }.thenBy { it.bag }.thenBy { it.slot })
    }

    /** can [p] go into worn [slot] now: out of a fight any; in a fight a weapon slot's piece the game's own equip lands there
     *  (bags-gear1: and ammo, by its secure cell: the game decides) */
    fun now(p: Pick, slot: Int, combat: Boolean, window: Boolean = false): Boolean =
        !combat || (!window && (slot in WornMore.WEAPONS || slot == AMMO) && p.exact)

    /** the terse reason a listed piece can't go on now, or null */
    fun why(p: Pick, slot: Int, combat: Boolean, window: Boolean = false): String? = if (now(p, slot, combat, window)) null else "In combat"

    /**
     * The cells that put [p] into worn [slot] on [pad] (null = this pad can't, nothing is tapped): its bag slot's secure cell, or (two
     * possible slots, out of a fight) the drag: the bag slot's named pick on the shared row ([BagsMoveRoute]) then bmvq column slot - 1.
     */
    fun cells(pad: JSONObject?, p: Pick, slot: Int, combat: Boolean, window: Boolean = false): List<Pair<String, Int>>? {
        val rows = pad?.optJSONObject("rows") ?: return null
        if (!now(p, slot, combat, window)) return null
        if (p.exact && !window) return if (rows.has("bag${p.bag}") && p.slot >= 1) listOf("bag${p.bag}" to p.slot - 1) else null
        if (slot == AMMO) return null                       // bags-gear1: ammo only by its secure cell (no worn-slot column)
        if (!rows.has("bmvq")) return null
        val pick = BagsMoveRoute.cells(pad, p.bag, p.slot) ?: return null
        return pick + ("bmvq" to slot - 1)
    }
}

/**
 * char-more ENCHANT: which of the bags' kits (armor kits, stones, oils, enchants: the bags row's `kit`) may go on a worn piece. The add-on
 * learns it from the GAME: whenever a kit's "pick an item" is on, Bags.lua asks the game, for every worn piece, whether the pick may go on
 * it (KIT.match: C_Item.DoesItemMatchTargetEnchantingSpell / DoesItemMatchSpellItemCondition) and sends the answers that still hold for
 * the piece worn now on the kit's row: `kfy` = ",5,7," (yes), `kfn` = ",1,16," (no). A kit not asked about yet for this piece is offered
 * (the game still says no to it, and the room stops the pick: "It can't go there"). Pure: WornReplaceTest.
 */
object KitFit {
    enum class Fit { YES, NO, UNKNOWN }

    fun fit(row: JSONObject, slot: Int): Fit = when {
        row.optString("kfy").contains(",$slot,") -> Fit.YES
        row.optString("kfn").contains(",$slot,") -> Fit.NO
        else -> Fit.UNKNOWN
    }

    /** the kits for worn [slot]: the known misfits left out, the known fits first, otherwise the bags' order */
    fun forSlot(kits: List<Triple<Int, Int, JSONObject>>, slot: Int): List<Triple<Int, Int, JSONObject>> =
        kits.filter { fit(it.third, slot) != Fit.NO }.sortedBy { if (fit(it.third, slot) == Fit.YES) 0 else 1 }
}
