package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE CLOCK BAND (feat/clock-extras-v2; the user, 2026-11-13: "the clock and extras all kind of look like poop. lets center them where
 * the ZR and LR appear (fade them out when holding ZL and ZR) and make them space out evenly based on what else is turned on. the gold
 * should be the total gold (including silver and copper) when the hearthstone is ready it should have a subtle glow there should also be
 * an ammo option."): the clock + battery and the extras the player turned on, as ONE row centred where the ZL + ZR join shows on Home
 * (CastBarPlace.clockSpot), equal gaps between the items, faded out while a trigger is held. Pure (no android.*): checked off-device.
 */
object ClockBand {
    /** the items, left to right: the travel pair, the clock in the middle, the purse and the packs */
    enum class Kind { HEARTH, TELEPORT, CLOCK, GOLD, BAGS, AMMO }

    /** the widest and narrowest gap between two items (px at 1240 wide); under the narrowest the whole row scales down to fit */
    const val GAP_MAX = 40f
    const val GAP_MIN = 10f
    /** an item's cell: the icon (or the time) over its value */
    const val CELL_H = 48f
    const val ICON = 28f

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
     * the spot's own middle. [spot] = CastBarPlace.clockSpot (left, top, right, bottom, cx, joinY, 8 button boxes, the clearance).
     */
    fun rowTop(spot: FloatArray, x0: Float, x1: Float, cellH: Float): Float {
        val low = (spot[1] + spot[3]) / 2f - cellH / 2f
        if (spot.size < 6 + 32 + 1) return low
        val pad = spot[spot.size - 1]
        var top = spot[5] - cellH / 2f
        for (p in 0 until 8) {
            val o = 6 + 4 * p
            if (spot[o] < x1 + pad && spot[o + 2] > x0 - pad) top = maxOf(top, spot[o + 3] + pad)
        }
        return minOf(top, low)
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

    /** the purse the game's way: (amount, 0 gold / 1 silver / 2 copper), the leading zero units left out (34s 56c under a gold; 0c) */
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

    /** the equipped ammo (inventory slot 0: its icon and count, bags.equipped.ammo, plain data); null = none in the slot (no row) */
    class Ammo(val icon: Int, val count: Int)
    fun ammo(equipped: JSONObject?): Ammo? {
        val a = equipped?.optJSONObject("ammo") ?: return null
        if (a.optInt("id", 0) <= 0) return null
        val n = a.optInt("count", -1)
        if (n <= 0) return null
        return Ammo(a.optInt("icon", 0), n)
    }

    /** a count in a narrow cell: as is under 10000, then thousands ("12k") */
    fun count(n: Int): String = if (n >= 10000) "${n / 1000}k" else n.toString()

    /** which items show, in order: [on] the player's rows (and the data there), the clock always */
    fun kinds(hearth: Boolean, teleport: Boolean, gold: Boolean, bags: Boolean, ammo: Boolean): List<Kind> {
        val k = ArrayList<Kind>(6)
        if (hearth) k += Kind.HEARTH
        if (teleport) k += Kind.TELEPORT
        k += Kind.CLOCK
        if (gold) k += Kind.GOLD
        if (bags) k += Kind.BAGS
        if (ammo) k += Kind.AMMO
        return k
    }
}
