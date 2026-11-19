package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE CLOCK BAND (feat/clock-extras-v2; the user, 2026-11-13: "the clock and extras all kind of look like poop. lets center them where
 * the ZR and LR appear (fade them out when holding ZL and ZR) and make them space out evenly based on what else is turned on. the gold
 * should be the total gold (including silver and copper) when the hearthstone is ready it should have a subtle glow there should also be
 * an ammo option."): the extras the player turned on, as ONE row centred where the ZL + ZR join shows on Home (CastBarPlace.clockSpot),
 * equal gaps between the items, faded out while a trigger is held. THE CLOCK + BATTERY ARE NOT IN IT (the user, then: "No the time and
 * Battery still needs to be centered at the top like it was before, do not change that"): UiKit.clockChip at the band's top centre,
 * as before, with its own show / fade rules; the row stays under it ([CHIP_TOP] + [CHIP_H]). Pure (no android.*): checked off-device.
 */
object ClockBand {
    /** the items, left to right: the travel pair and the packs (the purse is the gold line under the battery, [GOLD_H]) */
    enum class Kind { HEARTH, TELEPORT, BAGS, AMMO }

    /** the top clock chip (UiKit.clockChip at the band's centre x, band top + [CHIP_TOP], [CHIP_H] high; unchanged since LATER 18) */
    const val CHIP_TOP = 10f
    const val CHIP_H = 84f
    /**
     * THE GOLD LINE (the user, 2026-11-13: "lets ditch the Icon for the gold and just list our total gold under the battery (still give
     * it a toggle)"): [GOLD_H] more under the chip's battery, the line's middle [GOLD_Y] from the chip's top; the time + battery unmoved
     */
    const val GOLD_H = 24f
    const val GOLD_Y = 87f

    /** the chip's bottom from the band's top: the time + battery, and the gold line while it shows */
    fun chipBottom(gold: Boolean): Float = CHIP_TOP + CHIP_H + (if (gold) GOLD_H else 0f)

    /** the widest and narrowest gap between two items (px at 1240 wide); under the narrowest the whole row scales down to fit */
    const val GAP_MAX = 40f
    const val GAP_MIN = 10f
    /** an item's cell: the icon (or the time) over its value */
    const val CELL_H = 48f
    const val ICON = 28f
    /** the row's clearance under the clock chip */
    const val CHIP_PAD = 4f

    /** where each item's LEFT edge goes and the row's [scale] (1 = room enough) */
    class Placement(val lefts: FloatArray, val gap: Float, val scale: Float) {
        val n get() = lefts.size
    }

    /**
     * Items [widths] wide in the spot [left]..[right], the row centred on [cx] (the join's centre): equal gaps, as wide as there is room
     * for up to [GAP_MAX]; a row too wide even at [GAP_MIN] is scaled down (each item and gap by [Placement.scale]) to fit.
     */
    fun place(widths: FloatArray, left: Float, right: Float, cx: Float): Placement {
        val n = widths.size
        if (n == 0) return Placement(FloatArray(0), 0f, 1f)
        val room = 2f * minOf(cx - left, right - cx)                      // centred on cx: the nearer edge sets the room
        val total = widths.sum()
        var gap = if (n == 1) 0f else ((room - total) / (n - 1)).coerceAtMost(GAP_MAX)
        var scale = 1f
        if (n > 1 && gap < GAP_MIN) gap = GAP_MIN
        val whole = total + gap * (n - 1)
        if (whole > room && whole > 0f) scale = room / whole
        val lefts = FloatArray(n)
        var x = cx - whole * scale / 2f
        for (i in 0 until n) { lefts[i] = x; x += (widths[i] + gap) * scale }
        return Placement(lefts, gap * scale, scale)
    }

    /**
     * The row's top (review of feat/clock-extras-v2: "center them where the ZR and LR appear"): ON the join (centred on its y) when the
     * row [x0]..[x1], [cellH] high, passes clear of every button there; else just under the buttons it would touch, never lower than
     * the spot's own middle; and never above [floor] (the clock chip's bottom + [CHIP_PAD]). [spot] = CastBarPlace.clockSpot (left, top,
     * right, bottom, cx, joinY, 8 button boxes, the clearance).
     */
    fun rowTop(spot: FloatArray, x0: Float, x1: Float, cellH: Float, floor: Float = Float.NEGATIVE_INFINITY): Float {
        val low = (spot[1] + spot[3]) / 2f - cellH / 2f
        if (spot.size < 6 + 32 + 1) return maxOf(low, floor)
        val pad = spot[spot.size - 1]
        var top = spot[5] - cellH / 2f
        for (p in 0 until 8) {
            val o = 6 + 4 * p
            if (spot[o] < x1 + pad && spot[o + 2] > x0 - pad) top = maxOf(top, spot[o + 3] + pad)
        }
        return maxOf(minOf(top, low), floor)
    }

    /**
     * The row's alpha under the triggers (1 = shown): from [from] toward hidden while [held], back while let go, linear over [ms]
     * (Home's own overlay fade, HomeView.FADE_MS), [elapsed] ms since the last change
     */
    fun fade(from: Float, held: Boolean, elapsed: Long, ms: Float): Float {
        val to = if (held) 0f else 1f
        val k = (elapsed / ms).coerceIn(0f, 1f)
        return from + (to - from) * k
    }

    // ------------------------------------------------------------------ THE GROWN INFO BLOCK (feat/home-info-block)

    /**
     * THE INFO BLOCK GROWS INTO THE EMPTY SPACE (the user, 2026-11-19: "these (block, battery gold and menu choices) need to fill out that
     * space and shrink when a cast bar pops up or L+R"). Home's centre column between the D-pad and the faces is an hourglass: narrow
     * between the D-pad's Right and the faces' left button, wide over and under them. GROWN: the clock chip (time, battery, gold line)
     * scaled up from its top centre, as big as passes between the buttons beside it; the extras row along the column's wide bottom
     * (just over the cast bar's strip), each cell the icon with its value BESIDE it (an "N/total" reads wider than an icon), as big as
     * the bottom's width and the room under the chip allow. SMALL (a cast shows, LB + RB or ZL + ZR held): exactly today's chip and row.
     * [GROW_MS_IN] / [GROW_MS_OUT] on Motion's FastOutSlowIn (the combat grow's timing). Nothing ever stands on a button, the LB / RB
     * plates or the cast bar's strip: every size here is checked against the band's own button boxes (CastBarPlace.clockSpot).
     */
    const val CHIP_GROW_MAX = 1.5f
    const val ROW_GROW_MAX = 1.6f
    /** a grown cell: the icon, this gap, the value (unscaled px, x the row's scale); the gap between cells at least [GROWN_GAP] */
    const val SIDE_GAP = 6f
    const val GROWN_GAP = 14f
    /** the search's step (scale units) */
    private const val STEP = 0.02f
    /** the grow's timing (ms): the combat grow's (Motion.COMBAT_GROW / COMBAT_SHRINK) */
    const val GROW_MS_IN = 240f
    const val GROW_MS_OUT = 200f

    /** the grown block: the chip's scale (1 = today), the row's top, its scale and each cell's left (no items: no lefts) */
    class Grown(val chip: Float, val rowTop: Float, val rowScale: Float, val lefts: FloatArray)

    /**
     * The free span (left, right) round [cx] for band rows [y0]..[y1]: the column's own edges ([spot] 0 / 2: the D-pad's and the faces'
     * outer buttons) narrowed by every button whose box reaches into those rows (with the clearance)
     */
    fun room(spot: FloatArray, y0: Float, y1: Float): FloatArray {
        var l = spot[0]; var r = spot[2]; val cx = spot[4]
        if (spot.size >= 6 + 32 + 1) {
            val pad = spot[spot.size - 1]
            for (p in 0 until 8) {
                val o = 6 + 4 * p
                if (spot[o + 1] >= y1 + pad || spot[o + 3] <= y0 - pad) continue
                val mid = (spot[o] + spot[o + 2]) / 2f
                if (mid < cx) l = maxOf(l, spot[o + 2] + pad) else r = minOf(r, spot[o] - pad)
            }
        }
        return floatArrayOf(l, r)
    }

    /** a span [w] wide centred on [cx] fits in [span] */
    private fun fits(span: FloatArray, cx: Float, w: Float) = cx - w / 2f >= span[0] - 0.01f && cx + w / 2f <= span[1] + 0.01f

    /**
     * The grown layout in [spot] (CastBarPlace.clockSpot in Home px): the chip [chipW] x [chipH] at scale 1 from [chipTop], centred on the
     * spot's centre x; [cells] = each extras cell's grown width at scale 1 (icon + [SIDE_GAP] + its value; the icon alone with no value).
     * The row first, bottom-aligned on the spot's bottom (the widest place, over the cast bar's strip), as big as fits under the chip at
     * its today's size; then the chip as big as fits over the row. null = not even today's sizes fit grown: stay small.
     */
    fun grow(spot: FloatArray, chipTop: Float, chipW: Float, chipH: Float, cells: FloatArray): Grown? {
        val cx = spot[4]; val bottom = spot[3]
        fun rowW(q: Float) = (cells.sum() + GROWN_GAP * (cells.size - 1).coerceAtLeast(0)) * q
        var q = 0f; var rowTop = bottom
        if (cells.isNotEmpty()) {
            var t = ROW_GROW_MAX
            while (t >= 1f - 1e-4f) {
                val top = bottom - ICON * t
                if (top >= chipTop + chipH + CHIP_PAD && fits(room(spot, top, bottom), cx, rowW(t))) { q = t; break }
                t -= STEP
            }
            if (q == 0f) return null
            rowTop = bottom - ICON * q
        }
        var s = CHIP_GROW_MAX
        var chip = 0f
        while (s >= 1f - 1e-4f) {
            val b = chipTop + chipH * s
            if (b + (if (cells.isEmpty()) 0f else CHIP_PAD) <= rowTop && fits(room(spot, chipTop, b), cx, chipW * s)) { chip = s; break }
            s -= STEP
        }
        if (chip == 0f) chip = 1f
        val lefts = FloatArray(cells.size)
        if (cells.isNotEmpty()) {
            // equal gaps across the bottom's free span, as wide as there is room for (up to GAP_MAX), centred on the spot's centre
            val span = room(spot, rowTop, bottom)
            val roomW = 2f * minOf(cx - span[0], span[1] - cx)
            val total = cells.sum() * q
            val gap = if (cells.size == 1) 0f else ((roomW - total) / (cells.size - 1)).coerceIn(GROWN_GAP * q, GAP_MAX * q)
            var x = cx - (total + gap * (cells.size - 1)) / 2f
            for (i in cells.indices) { lefts[i] = x; x += cells[i] * q + gap }
        }
        return Grown(chip, rowTop, if (cells.isEmpty()) 1f else q, lefts)
    }

    /** the grow (0 = small, 1 = grown) [elapsed] ms after it last turned toward [grown] from [from], linear (the caller eases it) */
    fun growStep(from: Float, grown: Boolean, elapsed: Long): Float {
        val to = if (grown) 1f else 0f
        val ms = if (grown) GROW_MS_IN else GROW_MS_OUT
        val k = (elapsed / ms).coerceIn(0f, 1f)
        return from + (to - from) * k
    }

    /** the purse the game's way (the gold line): (amount, 0 gold / 1 silver / 2 copper), the leading zero units left out (34s 56c under a gold; 0c) */
    fun coins(copper: Long): List<Pair<Long, Int>> {
        val m = copper.coerceAtLeast(0L)
        val g = m / 10000L; val s = (m / 100L) % 100L; val c = m % 100L
        val out = ArrayList<Pair<Long, Int>>(3)
        if (g > 0) out += g to 0
        if (g > 0 || s > 0) out += s to 1
        out += c to 2
        return out
    }

    /** the Hearthstone's soft glow: only in the bags, off cooldown, not held dim */
    fun hearthGlow(have: Boolean?, left: Double, dim: Boolean): Boolean = have == true && left <= 0.0 && !dim

    /**
     * THE AMMO, CARRIED / WHAT IT CAN BE (feat/home-info-block; the user, 2026-11-19: "I have ammo turned on for my bottom bar but I dont
     * see it at all when im empty" and "the bag and ammo ar confusing becaus they just state a number instread of a number out of total
     * available"; picks: 0 still shows, "264/1000"). Plain add-on data only (the Bags document: bags.equipped.ammo, the bag rows, the
     * item rows; Bags.lua bags-vslots1 sends the worn ammo's stack size):
     *  - [count]: the worn ammo (inventory slot 0) the game counts (GetInventoryItemCount: every stack of it in the bags), else the
     *    stacks of that ammo in the item rows; 0 when the slot is empty. NEVER hidden at 0 (the old row hid at 0 and with no slot item).
     *  - [of]: a quiver / ammo pouch on the bag bar (its family has the arrows / bullets bit): their slots x the ammo's stack size;
     *    none: what the plain bags could hold of it (their free slots + the slots that already hold it) x the stack size.
     *    The stack: the worn ammo's `stack`, else the one seen before, else the biggest stack in the bags, else [VendorSlots.AMMO_STACK].
     * Shown for a player who uses ammo: ammo in the slot, a quiver / pouch, the game's AmmoNeeded, or ammo seen before ([last]);
     * a character who never had any (a Shaman) gets no ammo cell. [icon] 0 = the stand-in quiver ([VendorSlots.AMMO_ICON]).
     */
    class Ammo(val icon: Int, val count: Int, val of: Int, val stack: Int, val id: Int) {
        val empty get() = count <= 0
        override fun equals(other: Any?) = other is Ammo && other.icon == icon && other.count == count && other.of == of &&
            other.stack == stack && other.id == id
        override fun hashCode() = ((icon * 31 + count) * 31 + of) * 31 + id
    }

    /** the ammo from a bags document ([bags] = `full.bags`); [last] = what was shown before (a document without `equipped` keeps it) */
    fun ammo(bags: JSONObject?, last: Ammo? = null): Ammo? {
        if (bags == null) return last
        val eq = bags.optJSONObject("equipped") ?: return last
        val worn = eq.optJSONObject("ammo")?.takeIf { it.optInt("id", 0) > 0 }
        val entries = bagRows(bags)
        val ammoBags = entries.filter { (it.optInt("fam", 0) and VendorSlots.FAM_AMMO) != 0 && it.optInt("slots", 0) > 0 }
        val uses = worn != null || ammoBags.isNotEmpty() || eq.optBoolean("ammoNeeded", false) || last != null
        if (!uses) return null
        val id = worn?.optInt("id", 0) ?: 0
        val items = bags.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
        val mine = if (id > 0) items.filter { it.optInt("id", 0) == id } else emptyList()
        val count = (worn?.optInt("count", -1) ?: -1).takeIf { it >= 0 }
            ?: if (worn != null) mine.sumOf { it.optInt("count", 0).coerceAtLeast(0) } else 0
        val stack = worn?.optInt("stack", 0)?.takeIf { it > 0 }
            ?: last?.stack?.takeIf { it > 0 && (id == 0 || last.id == id) }
            ?: maxOf(VendorSlots.AMMO_STACK, mine.maxOfOrNull { it.optInt("count", 0) } ?: 0)
        val of = if (ammoBags.isNotEmpty()) ammoBags.sumOf { it.optInt("slots", 0) } * stack
        else {
            val plain = plainBags(entries).map { it.optInt("bag", -99) }.toSet()
            val free = plainBags(entries).sumOf { it.optInt("free", 0).coerceAtLeast(0) }
            val holding = mine.count { it.optInt("bag", -99) in plain }
            (free + holding) * stack
        }
        val icon = worn?.optInt("icon", 0)?.takeIf { it > 0 }
            ?: ammoBags.firstNotNullOfOrNull { b -> b.optInt("icon", 0).takeIf { it > 0 } }
            ?: last?.icon?.takeIf { it > 0 } ?: VendorSlots.AMMO_ICON
        return Ammo(icon, count, of, stack, if (id > 0) id else (last?.id ?: 0))
    }

    /**
     * THE BAGS, USED / TOTAL (the user's pick 2026-11-19: "68/80"): every bag on the bar, 0 = the backpack .. 4, profession bags too
     * (an herb or a soul bag is still bag space); NOT a quiver / ammo pouch (that is the ammo's figure) nor the reagent bag (5+).
     * A bag whose free count was not read is left out of both numbers; a document with no bag rows uses its own total / free.
     * null = not said yet.
     */
    class Bags(val used: Int, val total: Int) {
        val full get() = total in 1..used
        override fun equals(other: Any?) = other is Bags && other.used == used && other.total == total
        override fun hashCode() = used * 31 + total
    }

    fun bags(bags: JSONObject?): Bags? {
        if (bags == null) return null
        val rows = bagRows(bags)
        if (rows.isEmpty()) {
            if (!bags.has("total") || !bags.has("free")) return null
            val t = bags.optInt("total", 0); val f = bags.optInt("free", 0)
            return if (t > 0) Bags((t - f).coerceIn(0, t), t) else null
        }
        val counted = rows.filter {
            val b = it.optInt("bag", -1)
            b in 0..4 && !it.optBoolean("reagent") && (it.optInt("fam", 0) and VendorSlots.FAM_AMMO) == 0 &&
                it.optInt("slots", 0) > 0 && it.optInt("free", -1) >= 0
        }
        if (counted.isEmpty()) return null
        val total = counted.sumOf { it.optInt("slots", 0) }
        val used = counted.sumOf { (it.optInt("slots", 0) - it.optInt("free", 0)).coerceAtLeast(0) }
        return Bags(used, total)
    }

    private fun bagRows(bags: JSONObject): List<JSONObject> =
        bags.optJSONArray("bags")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    /** the bags that take anything (family 0, not the reagent bag, the free count read): where ammo can go with no quiver */
    private fun plainBags(rows: List<JSONObject>) =
        rows.filter { it.has("fam") && it.optInt("fam", -1) == 0 && !it.optBoolean("reagent") && it.optInt("free", -1) >= 0 && it.optInt("slots", 0) > 0 }

    /** a count in a narrow cell: as is under 10000, then thousands ("12k") */
    fun count(n: Int): String = if (n >= 10000) "${n / 1000}k" else n.toString()

    /** a figure out of its total, numbers only (terse rule): "68/80", "0/1000", "1.8k/3.2k"; [of] <= 0 = the total not known: "264" */
    fun outOf(have: Int, of: Int): String = if (of > 0) "${VendorSlots.num(have)}/${VendorSlots.num(of)}" else VendorSlots.num(have)

    /** which items show, in order: the player's rows (and the data there); none = no row (the clock chip is apart) */
    fun kinds(hearth: Boolean, teleport: Boolean, bags: Boolean, ammo: Boolean): List<Kind> {
        val k = ArrayList<Kind>(4)
        if (hearth) k += Kind.HEARTH
        if (teleport) k += Kind.TELEPORT
        if (bags) k += Kind.BAGS
        if (ammo) k += Kind.AMMO
        return k
    }
}
