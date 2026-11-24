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
    /** the items, in today's (Classic) order left to right: the travel pair, the packs, the pet's happiness (feat/extras-polish); the
     *  purse is the gold line under the battery, [GOLD_H]. Each one's [key] is its Settings row's (BottomTiles.CLOCK_LINES) */
    enum class Kind(val key: String) { HEARTH("hearth"), TELEPORT("teleport"), BAGS("bags"), AMMO("ammo"), PET("pet") }

    // ------------------------------------------------------------------ THE ORDER (feat/extras-polish)

    /**
     * THE ROW'S ORDER IS THE PLAYER'S (the user, 2026-11-23: "I also want to be able to change the order on these"): a list of [Kind.key]s
     * in the layout record (HomeLayout.extras: per character, with the rest of Home's arrangement), dragged in Edit Home as the room bar's
     * icons are. [DEFAULT] = today's (Kind's own order); Reset Home to Classic puts it back. [order] makes any saved list a WHOLE order
     * (unknown or doubled keys go, a key the list does not know - one added by a later build - comes back after the one it follows in
     * [DEFAULT]), as RoomBar.normalize does for the rooms.
     */
    val DEFAULT: List<String> = Kind.values().map { it.key }

    fun order(saved: List<String>?): List<String> {
        if (saved == null) return DEFAULT
        val out = ArrayList<String>()
        for (k in saved) if (k in DEFAULT && k !in out) out += k
        if (out.size == DEFAULT.size) return out
        for ((i, k) in DEFAULT.withIndex()) {
            if (k in out) continue
            val prev = (i - 1 downTo 0).map { DEFAULT[it] }.firstOrNull { it in out }
            out.add(if (prev == null) 0 else out.indexOf(prev) + 1, k)
        }
        return out
    }

    fun isDefault(saved: List<String>): Boolean = order(saved) == DEFAULT

    /** the kind of a row key (null: none, e.g. "gold" - the chip's line) */
    fun kindOf(key: String): Kind? = Kind.values().firstOrNull { it.key == key }

    /** [saved]'s whole order with [key] moved to where [to] stands among the keys [shown] (the others close up), as a drag or a step
     *  along the shown row asks; a key not shown, or [to] outside the shown row: unchanged */
    fun moveShown(saved: List<String>, shown: List<String>, key: String, to: Int): List<String> {
        val o = order(saved)
        val vis = o.filter { it in shown }
        val from = vis.indexOf(key)
        if (from < 0 || to !in vis.indices || from == to) return o
        val target = vis[to]
        val out = o.toMutableList()
        out.remove(key)
        val at = out.indexOf(target)
        out.add(if (to > from) at + 1 else at, key)
        return out
    }

    /** THE PET'S HAPPINESS (feat/extras-polish; the game's three states, GetPetHappiness: 1 unhappy, 2 content, 3 happy); anything else
     *  = no pet with happiness (a warlock's demon, no pet out, an older add-on): the extra is not shown */
    fun petMood(h: Int?): Int? = h?.takeIf { it in 1..3 }

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
     * Items [widths] wide in the spot [left]..[right], the row centred on [cx] (the join's centre). THE EVEN-PITCH RULE (feat/extras-polish;
     * the user, 2026-11-23: "The badges on the home page when there are 3 for hearth bags and ammo are not centered and look like poop when
     * I use 3 with the ammo"): every cell is CENTRED on its own slot and the slots stand at ONE pitch, centred on [cx] - so the icons are
     * evenly spaced and the middle one (an odd count) stands right under the clock, whatever each value's width. (The old rule kept equal
     * gaps between the cells' EDGES and centred their whole extent: a wide value - the ammo's "1843/3200" - pushed every icon left and
     * spread its own gap, a ready Hearthstone (no value) pulled its neighbour in.) The pitch is the widest NEIGHBOURING pair's half widths
     * plus a [gap] as wide as there is room for, up to [GAP_MAX] (so no two values ever touch); a row too wide even at [GAP_MIN] is scaled
     * down (each item and gap by [Placement.scale]) to fit.
     */
    fun place(widths: FloatArray, left: Float, right: Float, cx: Float): Placement {
        val n = widths.size
        if (n == 0) return Placement(FloatArray(0), 0f, 1f)
        val room = 2f * minOf(cx - left, right - cx)                      // centred on cx: the nearer edge sets the room
        val pair = pairWidth(widths)
        val edge = edgeHalf(widths)
        var gap = if (n == 1) 0f else ((room / 2f - edge) / ((n - 1) / 2f) - pair).coerceAtMost(GAP_MAX)
        if (n > 1 && gap < GAP_MIN) gap = GAP_MIN
        val half = (n - 1) * (pair + gap) / 2f + edge
        val scale = if (2f * half > room && half > 0f) room / (2f * half) else 1f
        return Placement(evenLefts(widths, cx, (pair + gap) * scale, scale), gap * scale, scale)
    }

    /** the even pitch's base: the widest NEIGHBOURING pair's half widths summed (0 for one cell) */
    fun pairWidth(widths: FloatArray): Float {
        var m = 0f
        for (i in 0 until widths.size - 1) m = maxOf(m, (widths[i] + widths[i + 1]) / 2f)
        return m
    }

    /** the outer half cell past the end slots' centres (the wider end's: the row's box is symmetric round its centre) */
    fun edgeHalf(widths: FloatArray): Float = if (widths.isEmpty()) 0f else maxOf(widths.first(), widths.last()) / 2f

    /** each cell's LEFT on the even row: slot i's centre at [cx] + (i - (n - 1) / 2) x [pitch], the cell ([widths] x [scale]) centred on it */
    fun evenLefts(widths: FloatArray, cx: Float, pitch: Float, scale: Float): FloatArray {
        val n = widths.size
        return FloatArray(n) { i -> cx + (i - (n - 1) / 2f) * pitch - widths[i] * scale / 2f }
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
     * THE ROW LIFTED CLEAR OF A CAST'S NAME (fix/badges-cast; the user on 1523, 2026-11-23: "The icons shift smaller when casting but still
     * overlap with the cast text instead of shifting up"): at rest the cast's name stands ON the bar in its TextBox plate, in the gap
     * between the two button groups - higher than the bar's strip the [spot]'s bottom clears, so the small row (under the D-pad's Right
     * and the faces' left button, down to the strip) sat on the name. While a cast shows the row's bottom stays over [keep] (the name's
     * lane's top, less the clearance): bottom-aligned just over it, so it rises into the hourglass's neck between the D-pad's Right and the
     * faces' left button - as wide as that neck lets it (each cell centred on the even pitch, [place]'s rule) and never above [floor] (the
     * chip's bottom + [CHIP_PAD]). Its size is today's small one; only a row that cannot pass otherwise (wider than the neck, or taller
     * than the room between the chip and the name) is scaled down to fit. [widths] = each cell's width, [cellH] its height (at the row's
     * own fit); [reach] = how far an end cell's soft glow (a ready Hearthstone's) spreads past its cell, kept off the buttons too.
     * Returns the placement (its [Placement.scale] the whole scale on [widths]) and the row's top.
     */
    fun lift(spot: FloatArray, widths: FloatArray, cellH: Float, floor: Float, keep: Float, reach: Float = 0f): Pair<Placement, Float> {
        val n = widths.size
        if (n == 0) return Placement(FloatArray(0), 0f, 1f) to keep
        val q = if (keep - floor < cellH) ((keep - floor) / cellH).coerceAtLeast(0.1f) else 1f
        val span = room(spot, keep - cellH * q, keep)
        val pl = place(FloatArray(n) { widths[it] * q }, span[0] + reach * q, span[1] - reach * q, spot[4])
        val k = q * pl.scale
        return Placement(pl.lefts, pl.gap, k) to keep - cellH * k
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
     * (just over the cast bar's strip), each cell today's icon over its value (the user, 2026-11-19: "I still want the numbers under the
     * icons"), as big as the bottom's width and the room under the chip allow - a plain scale of today's cells. SMALL (a cast shows, LB + RB or ZL + ZR held): exactly today's chip and row.
     * [GROW_MS_IN] / [GROW_MS_OUT] on Motion's FastOutSlowIn (the combat grow's timing). Nothing ever stands on a button, the LB / RB
     * plates or the cast bar's strip: every size here is checked against the band's own button boxes (CastBarPlace.clockSpot).
     */
    const val CHIP_GROW_MAX = 1.5f
    const val ROW_GROW_MAX = 1.6f
    /** the gap between grown cells at least [GROWN_GAP] (unscaled px, x the row's scale) */
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
     * spot's centre x; [cells] = each extras cell's width at scale 1 (the wider of the icon and its value; [CELL_H] high).
     * One scale for both (each capped: [CHIP_GROW_MAX], [ROW_GROW_MAX]), the biggest that fits: the row bottom-aligned on the spot's
     * bottom (over the cast bar's strip), the chip over it. null = not even today's sizes fit that way: stay small.
     */
    fun grow(spot: FloatArray, chipTop: Float, chipW: Float, chipH: Float, cells: FloatArray): Grown? {
        val cx = spot[4]; val bottom = spot[3]
        // feat/extras-polish: the even-pitch row's box (symmetric round cx), at least GROWN_GAP between the widest neighbours
        val pair = pairWidth(cells); val edge = edgeHalf(cells)
        fun rowW(q: Float) = ((cells.size - 1).coerceAtLeast(0) * (pair + GROWN_GAP) + 2f * edge) * q
        // ONE scale for both (each capped at its own most), the biggest that fits: the row bottom-aligned on the spot's bottom, the chip
        // over it with the clearance between, neither on a button
        var q = 0f; var chip = 0f; var rowTop = bottom
        var t = maxOf(CHIP_GROW_MAX, ROW_GROW_MAX)
        while (t >= 1f - 1e-4f) {
            val s = minOf(t, CHIP_GROW_MAX); val r = minOf(t, ROW_GROW_MAX)
            val cb = chipTop + chipH * s
            val top = if (cells.isEmpty()) bottom else bottom - CELL_H * r
            val ok = cb + (if (cells.isEmpty()) 0f else CHIP_PAD) <= top && fits(room(spot, chipTop, cb), cx, chipW * s) &&
                (cells.isEmpty() || fits(room(spot, top, bottom), cx, rowW(r)))
            if (ok) { chip = s; q = r; rowTop = top; break }
            t -= STEP
        }
        if (chip == 0f) return null
        var lefts = FloatArray(cells.size)
        if (cells.isNotEmpty()) {
            // feat/extras-polish: the EVEN PITCH ([place]'s rule) across the bottom's free span, the gap between the widest neighbours as
            // wide as there is room for (GROWN_GAP .. GAP_MAX), the slots centred on the spot's centre (the middle icon under the clock)
            val span = room(spot, rowTop, bottom)
            val roomW = 2f * minOf(cx - span[0], span[1] - cx)
            val n = cells.size
            val gap = if (n == 1) 0f else ((roomW / 2f - edge * q) / ((n - 1) / 2f) - pair * q).coerceIn(GROWN_GAP * q, GAP_MAX * q)
            lefts = evenLefts(cells, cx, pair * q + gap, q)
        }
        return Grown(chip, rowTop, if (cells.isEmpty()) 1f else q, lefts)
    }

    /** the grow (0 = small, 1 = grown) [elapsed] ms after it last turned toward [grown] from [from], linear (the caller eases it) */
    fun growStep(from: Float, grown: Boolean, elapsed: Long): Float {
        val to = if (grown) 1f else 0f
        val ms = if (grown) GROW_MS_IN else GROW_MS_OUT
        val k = (elapsed / ms).coerceIn(0f, 1f)
        // fix/badges-cast: exactly its end once there (from a turn half way, from + (to - from) can miss it by a float's last bit, and
        // Home asks for a frame for as long as the grow is not at its end)
        return if (k >= 1f) to else from + (to - from) * k
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
     *    none: 0 = no total, the count alone ("264"; the user's ruling 2026-11-19: "/total" only while a quiver or pouch is worn).
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
        // the user's ruling 2026-11-19: no quiver / pouch = the count alone ("264"); the total only while one is worn
        val of = if (ammoBags.isNotEmpty()) ammoBags.sumOf { it.optInt("slots", 0) } * stack else 0
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

    /** a count in a narrow cell: as is under 10000, then thousands ("12k") */
    fun count(n: Int): String = if (n >= 10000) "${n / 1000}k" else n.toString()

    /** a figure out of its total, numbers only (terse rule): "68/80", "0/1000", "1.8k/3.2k"; [of] <= 0 = the total not known: "264" */
    fun outOf(have: Int, of: Int): String = if (of > 0) "${VendorSlots.num(have)}/${VendorSlots.num(of)}" else VendorSlots.num(have)

    /** which items show, in today's order: the player's rows (and the data there); none = no row (the clock chip is apart) */
    fun kinds(hearth: Boolean, teleport: Boolean, bags: Boolean, ammo: Boolean, pet: Boolean = false): List<Kind> =
        kinds(DEFAULT, setOfNotNull(Kind.HEARTH.takeIf { hearth }, Kind.TELEPORT.takeIf { teleport }, Kind.BAGS.takeIf { bags },
            Kind.AMMO.takeIf { ammo }, Kind.PET.takeIf { pet }))

    /** feat/extras-polish: the items [on] in the player's [order] (HomeLayout.extras, made whole: [order]) */
    fun kinds(order: List<String>, on: Set<Kind>): List<Kind> = order(order).mapNotNull { k -> kindOf(k)?.takeIf { it in on } }
}
