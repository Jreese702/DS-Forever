package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * bags-gear1 THE EQUIP SLIDE-OUT (the user, 2026-11-16: "Choose which ring or weapon slot to replace"; "Design: X opens a slide-out of
 * ARMOR (item icons) next to the item. It lists the options for that slot, plus a blank entry that unequips."; "The Y text menu stays for
 * enchant and bind (use-effect items)."; "When pressed, X animates the options sliding out." The picks: D1a, D2a, D3a, D4a, D5a, D6a).
 * The rules of the Character room's rail and the Bags room's target row, pure (org.json only): SlideOutTest.
 *
 *  - THE RAIL (Character room, X on a slot): the bags' pieces for THAT slot, best item level first ([WornReplace.picks]: the game's slot
 *    rules, what this character can't wear left out), then THE BLANK (the worn piece comes off: Unequip's rule, [WornMore.rows]) when
 *    something is worn there; the ammo slot's rail is the bags' ammo, no blank. A worn slot with nothing to offer still has the blank
 *    (C5); an empty slot with nothing = no rail ("Nothing fits").
 *  - WHICH WAY (D3a): toward the doll's middle: the left column slides right, the right column left, the weapons row (and ammo) up; the
 *    D-pad along it moves, across it does nothing (G5); A acts, B / X back. Four icons at a time from a side column (C4), a chevron
 *    says there are more.
 *  - WHAT CAN GO NOW ([why]): the route table of [WornReplace] (armour never in a fight, a weapon only where the game's own equip lands
 *    it, a game window = the drag only), plus a two-hander over a worn off hand (or an off-hand piece under a worn two-hander) with no
 *    free plain-bag slot: "Bags full"; a pad without the cells: "Unavailable". A greyed entry's A says why; nothing is tapped.
 *  - THE CARD (D4a): the game's full tooltip when the app has it (a Bags visit), else a short one (name, stats); the stat changes at
 *    its foot counted against THIS slot only ([changesFor]); the blank: "Unequip" and the losses.
 *  - THE WATCH (C6): a Replace's answer is the slot itself: the chosen piece there within [WATCH_MS] (the game's bind question
 *    pausing it), else "The game refused it".
 *  - THE BAGS ROOM (D1a): X opens its target row only for gear with two places to go ([targets]), out of a fight, no game window.
 *  - char-popout THE OTHER SLOT'S PIECE (the user, 2026-11-16: "on weapons that are both 1 hand or can actually be swapped to the offhand
 *    or vice versa then that option should be listed in the pop out options."; "if the only other option for something is nothing then I
 *    should be able to select that to unequip."): the rail of a hand, a ring or a trinket slot lists FIRST what the other hand / ring /
 *    trinket slot wears when the game's slot rules let it go here ([swaps]: an off hand's one-hander into the main hand; the main hand's
 *    one-hander into the off hand only for a class that dual wields; main-hand-only weapons, two-handers, shields and held-in-off-hand
 *    pieces never cross; rings and trinkets always). Picking it swaps the two (Bags.lua's bagsSwapWorn, Character c20's arm), out of a
 *    fight only (char-popout2, THE USER 2026-11-16 picked "Allow it in fights": the two hands' swap in a fight too, [combatSwap]); what this slot wears goes into the other slot when it may, else into the bags ("Bags full" when there is no room).
 */
object SlideOut {
    /** the icons a rail shows at once (C4: four fit from a side column, a fifth would cover the other column) */
    const val SHOWN = 4
    /** C6: how long the slot may take to show the chosen piece before the room says the game refused it */
    const val WATCH_MS = 2000L

    enum class Dir { RIGHT, LEFT, UP }
    enum class Act { NONE, CHOOSE, BACK }

    // the doll's columns (Doll.SLOTS, the game's character frame; SlideOutDollTest keeps the two the same)
    private val LEFT_COLUMN = setOf(1, 2, 3, 15, 5, 4, 19, 9)
    private val RIGHT_COLUMN = setOf(10, 6, 7, 8, 11, 12, 13, 14)

    /** D3a: the left column slides right, the right column left, the weapons row and the ammo slot up */
    fun dir(slot: Int): Dir = when (slot) {
        in LEFT_COLUMN -> Dir.RIGHT
        in RIGHT_COLUMN -> Dir.LEFT
        else -> Dir.UP
    }

    /** one entry of a rail: a bag piece, the blank ([pick] null: what is worn comes off), or char-popout's swap ([from]: the worn slot
     *  whose piece comes here) */
    data class Entry(val pick: WornReplace.Pick?, val from: Int? = null) {
        val blank get() = pick == null && from == null
        val swap get() = from != null
    }

    /** the rail of worn [slot]: char-popout's [swaps] first (the other slot's piece), then [picks] in their order, then the blank (G7:
     *  last) when something is worn there; ammo has no blank */
    fun entries(picks: List<WornReplace.Pick>, slot: Int, worn: Boolean, swaps: List<Int> = emptyList()): List<Entry> =
        swaps.map { Entry(null, it) } + picks.map { Entry(it) } + if (worn && slot in 1..19) listOf(Entry(null)) else emptyList()

    /** char-popout: the other slot of a pair (the hands, the rings, the trinkets), or null */
    fun partner(slot: Int): Int? = when (slot) {
        WornReplace.MAIN -> WornReplace.OFF; WornReplace.OFF -> WornReplace.MAIN
        11 -> 12; 12 -> 11; 13 -> 14; 14 -> 13
        else -> null
    }

    /** char-popout: the worn slot whose piece may come into [slot] (the other of its pair, by the game's slot rules for that piece's
     *  equip location: [WornReplace.slotsOf]); empty when there is none. [worn] = slot -> the add-on's equipped row (its `equip`) */
    fun swaps(slot: Int, worn: Map<Int, JSONObject>, dualWield: Boolean): List<Int> {
        val other = partner(slot) ?: return emptyList()
        val w = worn[other]?.takeIf { wornId(it) > 0 } ?: return emptyList()
        val loc = w.optString("equip").takeIf { it.isNotEmpty() } ?: return emptyList()
        return if (slot in WornReplace.slotsOf(loc, dualWield)) listOf(other) else emptyList()
    }

    /** char-popout2 (THE USER, 2026-11-16, picked "Allow it in fights"): the swaps tried in a fight: the two hands (main hand and off
     *  hand, Bags bags-swap2: weapon Unequip's watched route; a refusal comes back as the room's one line, [WornMore.say]); rings,
     *  trinkets and anything else wait for the fight to end */
    fun combatSwap(slot: Int, from: Int): Boolean =
        slot != from && slot in HANDS && from in HANDS
    private val HANDS = setOf(WornReplace.MAIN, WornReplace.OFF)

    /** char-popout: what [slot] wears now can't go into [from] (the swap puts it in the bags: it needs a free slot) */
    fun swapNeedsRoom(slot: Int, from: Int, worn: Map<Int, JSONObject>, dualWield: Boolean): Boolean {
        val w = worn[slot]?.takeIf { wornId(it) > 0 } ?: return false
        return from !in WornReplace.slotsOf(w.optString("equip").takeIf { it.isNotEmpty() }, dualWield)
    }

    // ---------------------------------------------------------------- where the icons stand

    /** a square: its left / top edge and side */
    data class Box(val left: Float, val top: Float, val size: Float) {
        val right get() = left + size
        val bottom get() = top + size
        val cx get() = left + size / 2f
        val cy get() = top + size / 2f
    }

    /**
     * Where the [i]-th shown icon of a rail stands: from the slot's own box [from], icons [size] square and [step] apart (one doll pitch),
     * toward the doll's middle ([dir]); the first one a step's gap off the slot.
     */
    fun cell(from: Box, dir: Dir, i: Int, step: Float, size: Float = from.size): Box {
        val gap = step - size
        return when (dir) {
            Dir.RIGHT -> Box(from.right + gap + i * step, from.cy - size / 2f, size)
            Dir.LEFT -> Box(from.left - gap - i * step - size, from.cy - size / 2f, size)
            Dir.UP -> Box(from.cx - size / 2f, from.top - gap - i * step - size, size)
        }
    }

    /** a box on its way between [a] (at 0) and [b] (at 1) */
    fun lerp(a: Box, b: Box, t: Float): Box =
        Box(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.size + (b.size - a.size) * t)

    /** the first entry shown so that [sel] of [n] is in view, moving the window [first] as little as it can */
    fun first(sel: Int, n: Int, first: Int): Int {
        if (n <= SHOWN) return 0
        var f = first
        if (sel < f) f = sel
        if (sel > f + SHOWN - 1) f = sel - SHOWN + 1
        return f.coerceIn(0, n - SHOWN)
    }

    /** (more before the window, more after it): the chevrons */
    fun more(first: Int, n: Int): Pair<Boolean, Boolean> = (first > 0) to (first + SHOWN < n)

    /** a press while a rail is open: along it moves (clamped), across it nothing (G5), A chooses, B / X go back */
    fun key(sel: Int, n: Int, dir: Dir, key: String): Pair<Int, Act> {
        val (next, prev) = when (dir) {
            Dir.RIGHT -> "PADDRIGHT" to "PADDLEFT"
            Dir.LEFT -> "PADDLEFT" to "PADDRIGHT"
            Dir.UP -> "PADDUP" to "PADDDOWN"
        }
        return when (key) {
            next -> (sel + 1).coerceAtMost(maxOf(0, n - 1)) to Act.NONE
            prev -> (sel - 1).coerceAtLeast(0) to Act.NONE
            "PAD1" -> sel to Act.CHOOSE
            "PAD2", "PAD3" -> sel to Act.BACK
            else -> sel to Act.NONE
        }
    }

    // ---------------------------------------------------------------- what can go on now

    private fun wornId(w: JSONObject?) = w?.optInt("id", 0) ?: 0

    /** the game must find a free bag slot for what comes off besides the piece's own: a two-hander over a worn off hand, an off-hand
     *  piece under a worn two-hander (the game takes the two-hander off) */
    fun needsRoom(p: WornReplace.Pick, slot: Int, worn: Map<Int, JSONObject>): Boolean = when (slot) {
        WornReplace.MAIN -> BagsCompare.twoHand(p.row.optString("equip")) && wornId(worn[WornReplace.OFF]) > 0
        WornReplace.OFF -> wornId(worn[WornReplace.MAIN]) > 0 && worn[WornReplace.MAIN]?.optString("equip") == "INVTYPE_2HWEAPON"
        else -> false
    }

    /**
     * Why [e] can't go into [slot] now, or null. [freeGeneral] = the bags' free plain slots (null = not known: the game judges);
     * [routed] = the pad has this entry's cells (the blank: the add-on's arm and Bags' bmvq row).
     */
    fun why(e: Entry, slot: Int, combat: Boolean, window: Boolean, freeGeneral: Int?, worn: Map<Int, JSONObject>, routed: Boolean,
            dualWield: Boolean = false): String? {
        e.from?.let { from ->
            // char-popout: a swap is out of a fight, except (char-popout2, THE USER 2026-11-16 picked "Allow it in fights": "the hand
            // swap works in combat; if the game refuses, the pop-out says so") the two hands' swap, as weapon Unequip is ([combatSwap])
            if (combat && !combatSwap(slot, from)) return "In combat"
            if (freeGeneral != null && freeGeneral <= 0 && swapNeedsRoom(slot, from, worn, dualWield)) return "Bags full"
            return if (routed) null else "Unavailable"
        }
        val p = e.pick ?: return WornMore.rows(slot, worn = true, combat = combat, freeGeneral = freeGeneral, routed = routed).firstOrNull()?.why
        if (slot == WornReplace.AMMO) return when {
            window -> "Window open"                  // its secure cell would sell / deposit it; ammo has no drag
            !routed -> "Unavailable"
            else -> null
        }
        WornReplace.why(p, slot, combat, window)?.let { return it }
        if (freeGeneral != null && freeGeneral <= 0 && needsRoom(p, slot, worn)) return "Bags full"
        return if (routed) null else "Unavailable"
    }

    // ---------------------------------------------------------------- the card (D4a)

    /** a card: its title (the piece's name, or "Unequip"), the title's quality (-1 = the kit's gold: the blank), and its lines in the
     *  add-on's tooltip form ({t, c, r?, rc?}) */
    data class Card(val title: String, val quality: Int, val lines: JSONArray, val full: Boolean)

    private fun line(t: String, c: String? = null): JSONObject = JSONObject().put("t", t).also { if (c != null) it.put("c", c) }

    /**
     * The stat changes for worn [slot] only, as they go at a card's foot (a blank line, the header, the changes): the card of THIS slot
     * among [cards] ([BagsCompare.cards]). A piece the game compared elsewhere only (an off-hand piece against a worn two-hander, where
     * the two-hander comes off) shows that compare when this slot is its only place ([loc], [dualWield]).
     */
    fun changesFor(cards: JSONArray, slot: Int, loc: String?, dualWield: Boolean): List<JSONObject> {
        val mine = JSONArray()
        for (i in 0 until cards.length()) cards.optJSONObject(i)?.takeIf { it.optInt("slot", -1) == slot }?.let { mine.put(it) }
        if (mine.length() == 0 && WornReplace.slotsOf(loc, dualWield) == listOf(slot)) return BagsCompare.hover(cards)
        return BagsCompare.hover(mine)
    }

    /** the stats of a bags row as the game writes them ("+3 Stamina"; armour as "58 Armor") */
    fun statLines(row: JSONObject): List<JSONObject> = BagsCompare.stats(row.optJSONArray("stats")).map { s ->
        val a = kotlin.math.abs(s.v)
        val n = if (a == kotlin.math.floor(a)) a.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", a)
        val sign = if (s.key.startsWith("RESISTANCE0")) (if (s.v < 0) "-" else "") else (if (s.v < 0) "-" else "+")
        line("$sign$n ${s.name}")
    }

    /**
     * The card of entry [e] for worn [slot]. [tip] = the game's full tooltip of the bag piece when the app holds one (a Bags visit),
     * else null: the short card. [worn] = slot -> the add-on's equipped row. The blank: "Unequip", the worn piece's name, its losses.
     */
    fun card(e: Entry, slot: Int, tip: JSONObject?, worn: Map<Int, JSONObject>, dualWield: Boolean,
             words: BagsCompare.Words = BagsCompare.Words()): Card {
        e.from?.let { return swapCard(it, slot, worn, dualWield) }
        val p = e.pick
        if (p == null) {
            val w = worn[slot]
            val lines = JSONArray()
            (w?.optString("item")?.takeIf { it.isNotEmpty() && it[0].code > 3 })?.let { lines.put(line(it, BagsCompare.DIM)) }
            for (l in BagsCompare.diff(JSONArray(), listOf(w?.optJSONArray("stats")))) lines.put(l)
            return Card(WornMore.UNEQUIP, -1, lines, false)
        }
        val row = p.row
        val q = row.optInt("quality", 1)
        val tipLines = tip?.optJSONArray("lines")?.takeIf { it.length() > 0 }
        val lines = JSONArray()
        val title: String
        if (tipLines != null) {
            val first = tipLines.optJSONObject(0)
            title = first?.optString("t")?.takeIf { it.isNotEmpty() } ?: row.optString("name")
            for (i in 1 until tipLines.length()) tipLines.optJSONObject(i)?.let { lines.put(JSONObject(it.toString())) }
        } else {
            title = row.optString("name")
            for (l in statLines(row)) lines.put(l)
        }
        if (slot != WornReplace.AMMO) {
            val cards = BagsCompare.cards(tip, row, worn, dualWield, words)
            for (l in changesFor(cards, slot, row.optString("equip").takeIf { it.isNotEmpty() }, dualWield)) lines.put(l)
        }
        return Card(title, q, lines, tipLines != null)
    }

    /** char-popout: a swap's card: the other slot's piece as the room shows a worn piece (the game's tooltip lines it keeps, `tip`; else
     *  its name and stats), and when what this slot wears goes to the bags, its losses at the foot */
    private fun swapCard(from: Int, slot: Int, worn: Map<Int, JSONObject>, dualWield: Boolean): Card {
        val w = worn[from]
        val tip = w?.optJSONArray("tip")?.takeIf { it.length() > 0 }
        val lines = JSONArray()
        val name = w?.optString("item")?.takeIf { it.isNotEmpty() && it[0].code > 3 } ?: w?.optString("name") ?: ""
        val title: String
        if (tip != null) {
            title = tip.optJSONObject(0)?.optString("t")?.takeIf { it.isNotEmpty() } ?: name
            for (i in 1 until tip.length()) tip.optJSONObject(i)?.let { lines.put(JSONObject(it.toString())) }
        } else {
            title = name
            if (w != null) for (l in statLines(w)) lines.put(l)
        }
        if (swapNeedsRoom(slot, from, worn, dualWield))
            for (l in BagsCompare.diff(JSONArray(), listOf(worn[slot]?.optJSONArray("stats")))) lines.put(l)
        return Card(title, w?.optInt("quality", 1) ?: 1, lines, tip != null)
    }

    // ---------------------------------------------------------------- the watch (C6)

    /** a Replace sent: worn [slot] should hold item [want] by [until] (uptime ms) */
    class Watch(val slot: Int, val want: Int, var until: Long)

    enum class Seen { DONE, WAIT, REFUSED }

    /** the slot holds [wornId] at [now]; [asking] = the game's own question is up (it pauses the watch: the player answers it) */
    fun seen(w: Watch, now: Long, wornId: Int?, asking: Boolean): Seen {
        if (wornId == w.want) return Seen.DONE
        if (asking) { w.until = maxOf(w.until, now + WATCH_MS); return Seen.WAIT }
        return if (now >= w.until) Seen.REFUSED else Seen.WAIT
    }

    // ---------------------------------------------------------------- the animation (D5a)

    /** icon [i]'s share of the slide-out [t] ms after it began (0..1, before the curve) */
    fun outShare(t: Long, i: Int): Float = ((t - i * Motion.RAIL_STAGGER).toFloat() / Motion.RAIL_OUT).coerceIn(0f, 1f)

    /** the slide-back's share [t] ms after it began */
    fun inShare(t: Long): Float = (t.toFloat() / Motion.RAIL_IN).coerceIn(0f, 1f)

    /** the slide-out of [n] shown icons is still moving [t] ms after it began */
    fun outMoving(t: Long, n: Int): Boolean = t < Motion.RAIL_OUT + maxOf(0, n - 1) * Motion.RAIL_STAGGER

    // ---------------------------------------------------------------- the Bags room (D1a)

    /** the worn slots bags row [row] could go in: gear only (no bag, no quiver, no ammo); a dual wielder's one-hander both hands */
    fun targets(row: JSONObject, dualWield: Boolean): List<Int> {
        val loc = row.optString("equip").takeIf { it.isNotEmpty() } ?: return emptyList()
        if (!BagsCompare.isGear(loc)) return emptyList()
        return WornReplace.slotsOf(loc, dualWield)
    }

    /** X opens the Bags room's target row: gear with two places to go, out of a fight, no game window ([ctx]) */
    fun opens(row: JSONObject, dualWield: Boolean, combat: Boolean, ctx: String?): Boolean =
        ctx == null && !combat && targets(row, dualWield).size > 1

    /** bags-gear2: where the Bags room's slot menu and the piece's tooltip stand (left edges) and how wide the tooltip is */
    data class Pop(val menuLeft: Float, val tipLeft: Float, val tipW: Float)

    /**
     * bags-gear2 (the user, 2026-11-16: "The equip a one hand from a bag is really confusing because the tool tip is in front of the
     * options"; "I think it just needs its own menu pop out for an easy clear fix"): the slot menu ([menuW] wide) beside the piece's tile
     * ([tileLeft] .. [tileRight]) and the tooltip ([tipW]) beside them, never over the menu or the tile, inside [margin] .. [width] -
     * [margin]. In order: both on the tile's right (the menu nearer), both on its left, the menu right / tooltip left, the menu left /
     * tooltip right. Too narrow for any: the menu on the roomier side, the tooltip narrowed into the widest free span left.
     */
    fun pop(tileLeft: Float, tileRight: Float, menuW: Float, tipW: Float, width: Float, gap: Float = 14f, margin: Float = 16f): Pop {
        val lo = margin; val hi = width - margin
        fun fits(l: Float, w: Float) = l >= lo - 0.01f && l + w <= hi + 0.01f
        val mRight = tileRight + gap; val mLeft = tileLeft - gap - menuW
        val tries = listOf(
            mRight to mRight + menuW + gap,
            mLeft to mLeft - gap - tipW,
            mRight to tileLeft - gap - tipW,
            mLeft to tileRight + gap,
        )
        for ((m, t) in tries) if (fits(m, menuW) && fits(t, tipW)) return Pop(m, t, tipW)
        val menu = if (hi - tileRight >= tileLeft - lo) mRight.coerceAtMost(hi - menuW) else mLeft.coerceAtLeast(lo)
        // the free spans left of and right of what the menu and the tile cover
        val usedL = minOf(tileLeft, menu); val usedR = maxOf(tileRight, menu + menuW)
        val spanL = usedL - gap - lo; val spanR = hi - (usedR + gap)
        return if (spanR >= spanL) Pop(menu, usedR + gap, minOf(tipW, maxOf(0f, spanR)))
               else { val w = minOf(tipW, maxOf(0f, spanL)); Pop(menu, usedL - gap - w, w) }
    }

    /** bags-gear2: a worn slot's name in the slot menu: the game's word (translated by the room) and the number the game's character
     *  frame gives the second finger / trinket ("Finger" 1 / 2), else null */
    fun slotName(inv: Int): Pair<String, Int?> = when (inv) {
        11, 12 -> "Finger" to inv - 10
        13, 14 -> "Trinket" to inv - 12
        16 -> "Main Hand" to null
        17 -> "Off Hand" to null
        18 -> "Ranged" to null
        else -> "" to null
    }

    /** the Bags room's start (G9, the bag bar's chooser): an empty slot first, else the lower item level ([worn]: slot -> row) */
    fun start(slots: List<Int>, worn: Map<Int, JSONObject>): Int {
        slots.indexOfFirst { wornId(worn[it]) <= 0 }.takeIf { it >= 0 }?.let { return it }
        return slots.indices.minByOrNull { worn[slots[it]]?.optInt("ilvl", 0) ?: 0 } ?: 0
    }
}
