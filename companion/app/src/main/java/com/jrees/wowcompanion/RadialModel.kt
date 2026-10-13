package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE CONSUMABLES RADIAL (D-pad UP; the user's design 2026-10-05, the look "v4" signed off; Home leads, Spells' Radial.lua r1 sends
 * the items and owns the secure "radial" row). Pure: the `radial` document, the pages and what a press does.
 *
 * The document (Spells r1): r = { items = [{col, id, n, i, c, g, cd = {d, l}}], more, dead = [cols], fight, row = "radial",
 * cells = 24, pad }. Items arrive in the user's group order (1 food & drink, 2 potions / elixirs / flasks, 3 bandages, 4 other usable),
 * bag order inside a group, one per item id; at most 24 (3 pages of 8); `more` counts the rest. `col` = the item's secure cell (stable;
 * nil for an item looted in a fight: no cell until it ends); `dead` = cells frozen in a fight whose item ran out.
 *
 * A USE IS ALWAYS A TAP on the item's secure cell (the game allows an item only from a hardware event; Spells): the app taps, the
 * game uses it, in fights too.
 */
object RadialModel {
    const val PER_PAGE = 8
    /** the hub (B: close); the same value as UiKit.CR_HUB, kept here so this file needs no Android */
    const val HUB = -2
    /** a slice the stick rested on shorter than this before a release was swept through (the spring-back), not aimed */
    const val LATCH_MS = 90L

    /**
     * THE AIM FROM THE STICK'S VECTOR (the user after b62: "a bigger deadzone during selections"): the stick must be pushed past
     * [DEAD_IN] of its travel to aim, and an aim holds until it falls under [DEAD_OUT] (so the spring-back's slow start is not a
     * re-aim); the lit slice changes only when the stick is [HYST_DEG] past the shared edge into the next slice.
     */
    const val DEAD_IN = 0.6
    const val DEAD_OUT = 0.45
    /**
     * THE STICK'S NEUTRAL (the user 2026-10-09: "after making a selection there should be a delay to restoring thumbstick control UNLESS
     * it is moved back to neutral position first ... when using release mode, there should be a delay to being able to select unless the
     * stick gets returned to neutral first"): inside this, the stick is at rest. [ARM_MS] = how long after a wheel opens a RELEASE can
     * pick with no neutral seen; [STICK_BACK_MS] = how long after a pick / close the stick stays the wheel's with no neutral seen.
     */
    const val NEUTRAL = 0.3
    const val ARM_MS = 300L
    // RC3 (the user on RC2: "my guy walking off after selecting something. needs more delay"): 300 -> 1000; neutral still frees at once.
    // RC4 (the user on RC3: "when I try to do my hearthstone with a held stick, its still walking after I select it"): a stick HELD after
    // a pick never walks - only the stick back in the middle hands it over. This is a safety net only (a reader that stops reporting
    // frees at once, see the views' onStatus), long enough for a 10 s cast held through
    const val STICK_BACK_MS = 12_000L
    const val HYST_DEG = 10.0

    /** [x] right +, [y] UP + (normalised -1..1) -> the slice 0..7 (0 = up, clockwise), or -1 = the middle; [current] = the lit one */
    fun aimOf(x: Double, y: Double, current: Int): Int {
        val m = kotlin.math.hypot(x, y)
        if (m < (if (current >= 0) DEAD_OUT else DEAD_IN)) return -1
        var a = Math.toDegrees(kotlin.math.atan2(x, y))
        if (a < 0) a += 360.0
        if (current >= 0) {
            var d = kotlin.math.abs(a - current * 45.0) % 360.0
            if (d > 180.0) d = 360.0 - d
            if (d <= 22.5 + HYST_DEG) return current
        }
        return ((a + 22.5) / 45.0).toInt() % 8
    }

    data class Item(val col: Int?, val id: Int, val name: String, val icon: Int, val count: Int, val group: Int,
                    val cdLen: Double = 0.0, val cdLeft: Double = 0.0,
                    /** PINNED to its slice by the player (r7) */
                    val pinned: Boolean = false,
                    /** a pin whose item ran out (r7 have = false): greyed, keeps its slice, no cell */
                    val ranOut: Boolean = false)

    /** a pinnable item (Spells r7 cand[]): its column [k] on the pin row, its name and icon (the chooser shows it) */
    data class Cand(val k: Int, val name: String, val icon: Int)

    /** the "+" slice (the user's pin sheet: the bottom wedge of page 1; UiKit.CR_PLUS) */
    const val PLUS = 4
    /** the pin row's UNPIN column (Spells r7: 0-7 pick a slice, 8 unpin, 9-39 the candidates) */
    const val UNPIN_COL = 8

    /** [cells] = Spells' secure row is really built (r.pad = true); [noPad] = it is not (r2: r.nopad, every col nil): items show,
     *  nothing can be used yet. The row's cells live on the TapPad the quests document describes */
    data class Doc(val items: List<Item>, val more: Int, val dead: Set<Int>, val fight: Boolean, val row: String,
                   val cells: Boolean, val noPad: Boolean,
                   /** PINNING (Spells r7): page 1's pins by slice (0-7, never [PLUS]) */
                   val pins: Map<Int, Item> = emptyMap(),
                   /** every pinnable item (cand[] with a k), by item id */
                   val cand: Map<Int, Cand> = emptyMap(),
                   /** the pin row ("radialpin"), null = no row: no "+", no pinning (an r6 add-on) */
                   val prow: String? = null,
                   /** the SPELL radial (`sradial`, Spells' contract 2026-10-07): items at fixed places 0-23 (the doc's s - 1), gaps
                    *  allowed; null = the consumables radial, filled in order */
                   val places: Map<Int, Item>? = null)

    /** a `radial` document -> its state, or null when it is not one */
    fun parse(msg: JSONObject): Doc? {
        val spell = msg.optString("type") == "sradial"
        if (!spell && msg.optString("type") != "radial") return null
        val r = msg.optJSONObject("r") ?: return null
        val items = ArrayList<Item>()
        val a = r.optJSONArray("items")
        if (a != null) for (k in 0 until a.length()) {
            val o = a.optJSONObject(k) ?: continue
            val id = o.optInt("id", 0)
            if (id <= 0) continue
            val cd = o.optJSONObject("cd")
            items += Item(
                col = o.optInt("col", -1).takeIf { it >= 0 },     // nil / null (looted in a fight) = no cell
                id = id, name = o.optString("n", ""), icon = o.optInt("i", 0), count = o.optInt("c", 1),
                group = o.optInt("g", 4),
                cdLen = cd?.optDouble("d", 0.0) ?: 0.0, cdLeft = cd?.optDouble("l", 0.0) ?: 0.0)
        }
        val dead = HashSet<Int>()
        r.optJSONArray("dead")?.let { d -> for (k in 0 until d.length()) dead += d.optInt(k) }
        val prow = r.optString("prow", "").takeIf { it.isNotEmpty() }
        val pins = HashMap<Int, Item>()
        r.optJSONObject("pins")?.let { o ->
            for (slice in 0 until PER_PAGE) {
                if (slice == PLUS) continue
                val p = o.optJSONObject(slice.toString()) ?: continue
                val id = p.optInt("id", 0)
                if (id <= 0) continue
                val have = p.optBoolean("have", true)
                val cd = p.optJSONObject("cd")
                pins[slice] = Item(
                    col = if (have) p.optInt("col", -1).takeIf { it >= 0 } else null,
                    id = id, name = p.optString("n", ""), icon = p.optInt("i", 0), count = if (have) p.optInt("c", 1) else 0,
                    group = 0, cdLen = cd?.optDouble("d", 0.0) ?: 0.0, cdLeft = cd?.optDouble("l", 0.0) ?: 0.0,
                    pinned = true, ranOut = !have)
            }
        }
        val cand = HashMap<Int, Cand>()
        r.optJSONArray("cand")?.let { a ->
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val col = o.optInt("k", -1)
                if (o.optInt("id", 0) > 0 && col > UNPIN_COL) cand[o.optInt("id")] = Cand(col, o.optString("n", ""), o.optInt("i", 0))
            }
        }
        if (spell) {
            // each item at its own place s (1-24); an item without one, or two on one place, is left out
            val places = HashMap<Int, Item>()
            if (a != null) for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val s = o.optInt("s", 0)
                val it = items.firstOrNull { x -> x.id == o.optInt("id", 0) } ?: continue
                if (s in 1..PER_PAGE * 3 && (s - 1) !in places) places[s - 1] = it
            }
            return Doc(places.toSortedMap().values.toList(), 0, dead, r.optBoolean("fight", false), r.optString("row", "sradial"),
                cells = r.optBoolean("pad", false), noPad = r.optBoolean("nopad", false), places = places)
        }
        return Doc(items, r.optInt("more", 0), dead, r.optBoolean("fight", false), r.optString("row", "radial"),
            cells = r.optBoolean("pad", false), noPad = r.optBoolean("nopad", false),
            pins = if (prow != null) pins else emptyMap(), cand = cand, prow = prow)
    }

    /** the "+" stands on page 1 (the add-on has the pin row) */
    fun plusOn(doc: Doc?): Boolean = doc?.prow != null

    /** page 1's slices free for the auto-fill: not pinned, not the "+" */
    private fun free0(doc: Doc): List<Int> = (0 until PER_PAGE).filter { it !in doc.pins && !(plusOn(doc) && it == PLUS) }

    fun pages(doc: Doc?): Int {
        if (doc == null || doc.items.isEmpty()) return 1
        doc.places?.let { p -> return ((p.keys.maxOrNull() ?: 0) / PER_PAGE) + 1 }
        if (!plusOn(doc)) return (doc.items.size + PER_PAGE - 1) / PER_PAGE
        val rest = doc.items.size - free0(doc).size
        return 1 + if (rest > 0) (rest + PER_PAGE - 1) / PER_PAGE else 0
    }

    /**
     * page [page]'s 8 slices, 0 = the top, clockwise; null = an empty slice (and the "+"). With pinning (r7): page 1 = the pins on their
     * slices, the "+" on [PLUS], the auto-fill in the free slices in order; the rest of the auto-fill on the next pages, 8 each.
     */
    fun slices(doc: Doc?, page: Int): List<Item?> {
        val items = doc?.items ?: emptyList()
        doc?.places?.let { p -> return List(PER_PAGE) { k -> p[page * PER_PAGE + k] } }
        if (doc == null || !plusOn(doc)) return List(PER_PAGE) { k -> items.getOrNull(page * PER_PAGE + k) }
        val free = free0(doc)
        if (page == 0) {
            val out = arrayOfNulls<Item>(PER_PAGE)
            for ((s, p) in doc.pins) out[s] = p
            free.forEachIndexed { i, s -> out[s] = items.getOrNull(i) }
            return out.toList()
        }
        return List(PER_PAGE) { k -> items.getOrNull(free.size + (page - 1) * PER_PAGE + k) }
    }

    /** the pins only, page 1 (the slice chooser shows what is pinned where, nothing auto-filled) */
    fun pinSlices(doc: Doc?): List<Item?> = List(PER_PAGE) { s -> doc?.pins?.get(s) }

    /** the items pinned now (the picker marks them) */
    fun pinnedIds(doc: Doc?): Set<Int> = doc?.pins?.values?.map { it.id }?.toSet() ?: emptySet()

    /** the slice item [id] is pinned to, or -1 */
    fun pinOf(doc: Doc?, id: Int): Int = doc?.pins?.entries?.firstOrNull { it.value.id == id }?.key ?: -1

    /** the taps that PIN item [id] to [slice]: (row, slice) then (row, its column), ONE call (Spells r7); null = not possible */
    fun pinTaps(doc: Doc?, slice: Int, id: Int): List<Pair<String, Int>>? {
        val row = doc?.prow ?: return null
        if (slice !in 0 until PER_PAGE || slice == PLUS) return null
        val k = doc.cand[id]?.k ?: return null
        return listOf(row to slice, row to k)
    }

    /** the taps that UNPIN item [id] (its slice, then UNPIN); null = it is not pinned */
    fun unpinTaps(doc: Doc?, id: Int): List<Pair<String, Int>>? {
        val row = doc?.prow ?: return null
        val s = pinOf(doc, id).takeIf { it >= 0 } ?: return null
        return listOf(row to s, row to UNPIN_COL)
    }

    /** may [it] be used now: it has a live cell (not dead in this fight, not looted mid-fight) */
    fun usable(doc: Doc?, it: Item?): Boolean = doc != null && !doc.noPad && it != null && it.col != null && it.col !in doc.dead

    /**
     * ONE RETRY OF A USE, only when it surely did not happen (Spells, 2026-10-06: a LATE `radialuse` is not a lost release, and a second
     * tap would eat a second piece of food): no `radialuse` for it by the deadline ([acked] false) AND the latest document still shows
     * the item ([before] = it at the tap) with the same count and no cooldown begun. Gone from the list, fewer, or cooling = it was used.
     */
    fun shouldRetry(before: Item, now: Doc?, acked: Boolean): Boolean {
        if (acked || now == null) return false
        val it = now.items.firstOrNull { x -> x.id == before.id } ?: return false
        return it.count == before.count && it.cdLeft <= before.cdLeft && !(before.cdLeft <= 0.0 && it.cdLeft > 0.0)
    }

    /** what a press asks for */
    sealed class Act {
        object None : Act()
        object Close : Act()
        /** tap the secure cell [col] (the item [id]) */
        data class Use(val col: Int, val id: Int) : Act()
        /** the "+": the bag picker (pinning) */
        object Plus : Act()
    }

    /**
     * HOLD Up + FLICK (the user, 2026-10-06 after b60: "it doesnt seem to use unless I release the up arrow"; picked "Letting go of the
     * stick" and "B cancels, A does nothing"): hold Up = the wheel; push either stick at an item (it lights); LET THE STICK GO back to the
     * middle = that item is used (Up may stay held) and the wheel closes. Letting go of Up = the wheel closes, nothing used. B = cancel.
     * A = nothing. A press already over when the wheel's reader starts (a tap) closes it.
     */
    class Flow {
        /**
         * TAP (the user, 2026-10-06: "lets make the Other radial type. Make this an option"): tap Up = the wheel opens and STAYS; aim
         * with either stick; A uses the aimed item; B (or Up again) closes. False = HOLD (the default): letting the stick go uses it.
         */
        var holdOpen = true
        /** A to select (true) or letting the stick go selects (false): the PICK half of the one radial behaviour (2026-10-09) */
        var pickA = false
        /** both halves at once (the older single setting and the picker's own TAP way): Tap + A, or Hold + Release */
        var tap: Boolean
            get() = !holdOpen && pickA
            set(v) { holdOpen = !v; pickA = v }
        var page = 0; private set
        /** 0..7, or -1 = none */
        var pointed = -1; private set
        /** a stick is pushed at [pointed] right now */
        private var aiming = false
        /** RELEASE arming (2026-10-09): 0 = off (tests); the view sets [RadialModel.ARM_MS] and [openAt] at each open */
        var armMs = 0L
        var openAt = 0L
        private var sawNeutral = false
        /** the aim on now began ARMED (a neutral seen, or [armMs] past the open): only then may its release pick */
        private var aimArmed = true
        /** the slice the stick rested on at least [LATCH_MS] before the current one, and since when [pointed] has been aimed */
        private var stable = -1
        private var since = 0L

        fun reset() { page = 0; pointed = -1; aiming = false; stable = -1; since = 0L; sawNeutral = false; aimArmed = true }

        /**
         * A stick's eighth at [now] (ms): pushed = it aims (lights). Back to the middle after aiming = the aimed item is used: the slice
         * the stick RESTED on, not one it only swept through as it sprang back (b61: aimed at the Hearthstone, bottom right, let go, and
         * the spring-back crossed the down slice: 5 -> 4 -> rest used the 4). A slice held less than [LATCH_MS] before the release is
         * such a sweep; the one held before it counts.
         */
        fun stick(doc: Doc?, sector: Int, now: Long = 0L): Act {
            if (sector < 0 && !aiming) sawNeutral = true                 // a rest report with no aim on: the stick is in neutral
            if (sector >= 0) {
                if (!aiming) aimArmed = armMs <= 0L || sawNeutral || now - openAt >= armMs
                if (sector != pointed || !aiming) {
                    if (aiming && pointed >= 0 && now - since >= LATCH_MS) stable = pointed
                    pointed = sector; since = now
                }
                aiming = true
                return Act.None
            }
            if (!aiming) return Act.None
            aiming = false
            val target = if (now - since >= LATCH_MS || stable < 0) pointed else stable
            pointed = target
            // A TO SELECT: the stick only aims; the aim stays lit for A
            if (pickA) return Act.None
            sawNeutral = true
            // RELEASE: an aim that began before the wheel was armed (the stick already pushed at the open) never picks
            if (!aimArmed) { pointed = -1; return Act.None }
            return useAt(doc, target)
        }

        /** the stick's vector (x right +, y up +) at [now]: through the dead-zone and the hysteresis into [stick] */
        fun vector(doc: Doc?, x: Double, y: Double, now: Long): Act {
            if (!aiming && kotlin.math.hypot(x, y) < NEUTRAL) sawNeutral = true
            val s = aimOf(x, y, if (aiming) pointed else -1)
            if (s == (if (aiming) pointed else -1)) return Act.None
            return stick(doc, s, now)
        }

        /** Up let go: HOLD = the wheel closes, nothing used; TAP = nothing (the wheel stays) */
        fun upReleased(): Act = if (holdOpen) Act.Close else Act.None

        /** Up pressed again while open: TAP = close; HOLD cannot see it (Up is held) */
        fun upPressed(): Act = if (holdOpen) Act.None else Act.Close

        /** A: HOLD = nothing (the user's pick); TAP = use the aimed item */
        fun a(doc: Doc? = null): Act = if (pickA) useAt(doc, pointed) else Act.None
        fun b(): Act = Act.Close

        /** LB (-1) / RB (+1): the next page, wrapping; the pointer stays on its slice */
        fun turn(doc: Doc?, by: Int): Boolean {
            val n = pages(doc)
            if (n <= 1) return false
            page = ((page + by) % n + n) % n
            return true
        }

        /** a finger on slice [s] (the chooser): it lights */
        fun point(s: Int) { if (s in 0 until PER_PAGE) pointed = s }

        /** the D-pad: up / right step clockwise, down / left back (the fallback pointer) */
        fun step(by: Int) { pointed = if (pointed < 0) 0 else ((pointed + by) % PER_PAGE + PER_PAGE) % PER_PAGE }

        /** a finger on slice [slice] (UiKit.consumableRadialHit; [HUB] = the hub): uses it; the hub closes */
        fun touch(doc: Doc?, slice: Int): Act = when {
            slice == HUB -> Act.Close
            slice in 0 until PER_PAGE -> { pointed = slice; useAt(doc, slice) }
            else -> Act.None
        }

        /** the page is gone (fewer items now): back to the last one */
        fun clamp(doc: Doc?) { if (page >= pages(doc)) page = pages(doc) - 1 }

        private fun useAt(doc: Doc?, slice: Int): Act {
            if (slice !in 0 until PER_PAGE) return Act.None
            if (page == 0 && slice == PLUS && plusOn(doc)) return Act.Plus
            val it = slices(doc, page)[slice]
            return if (usable(doc, it)) Act.Use(it!!.col!!, it.id) else Act.None
        }
    }
}
