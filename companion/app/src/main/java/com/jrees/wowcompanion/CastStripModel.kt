package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE TAP-TO-CAST BAR (GitHub #43; the add-on half is WowCompanion/ActionBar.lua cb1-cb3 and WowCompanionSpells/Book.lua b53-b56): a tap
 * is ONE pad tap on the add-on's secure row "castbar" (cell = the slot's index), the game's own cast on DS Forever's own button. Spells
 * only. An edit made in a fight is queued by the add-on until the fight ends; its slot comes dimmed ([Slot.queued]) and with no cell, so
 * it cannot be tapped meanwhile. The cooldowns come from the add-on's `castcd` (facecd's shape: only what the game leaves plain, the
 * length estimated in a fight), never read off the screen.
 * cb2 THE LAZY ROW (1.0.30 code 1454): the add-on registers the castbar row only once the bar has a spell (TapPad's 56 rows are
 * over-subscribed), so a pick goes by the padtab row instead: the document's [Bar.padPick] (pp = [39, 19]: Smart Range's one-shot pick,
 * then the cast bar's code; Book b55).
 * cb3 SIX SLOTS ON ITS OWN SETTING (the user, 2026-11-13: "the tap to cast bar should get its own bottom screen section. the buttons
 * inside of it are too small, so I want to propose this- for options to turn it on, left right or both, which basically makes it a
 * setting that can be on the left side under chat the right side under the mini map, or sitting under both (or off by default). the bar
 * itself should have 6 slots for abilities. if its sitting on the right or left, the player should be able to swipe on the bar to move it
 * to the other [side]. to assign to the bar, in the bind menu, where there is the L and R tabs, we will add a third tab if this setting
 * is turned on, showing the 6 slots on that bar."; the pick: 6 abilities in all, the Bind tab shows only those 6): ONE row of 6
 * ([CastStripView]) placed by [Side] ([place]); the Bind box's Cast bar tab
 * ([tabCells], [slotCells]: padtab 39 then 26 / 20-25, Book b56). The Spellbook's own "Cast bar" button is gone (the tab replaces it).
 * cb4 THREE AT A TIME (the user, 2026-11-13: "make them only show 3 abilities at a time for left or right, and swipe to the other 3. they
 * should be the same size as the both option"): LEFT / RIGHT show slots 1-3 or 4-6 ([perPage], [slotIndex]) at BOTH's size and spacing
 * ([slotSide], [slotBox]), two page dots in the well's foot ([dots]); a sideways swipe flips the page either way ([flipTo], the slide
 * [dragOffset] .. [slideAt]) and no longer moves the bar (the side is set in Settings only). BOTH is unchanged (all 6). The page is kept
 * for the app's run ([Live.page]).
 * The logic, without the look (unit-tested in dsforever: CastStripModelTest).
 */
object CastStripModel {
    const val ROW = "castbar"
    /** cb2: the row the picks go by (Book b55 / b56) */
    const val PADTAB = "padtab"
    /** cb3: six slots in all (the user's pick) */
    const val SLOTS = 6
    /** cb2: padtab 39 = Smart Range's one-shot pick (PadTabCtl p46), then the cast bar's code */
    const val PICK = 39
    /** b56: the code after [PICK] that opens the Bind box's Cast bar tab */
    const val TAB_CODE = 26
    /** b56: the codes after [PICK] for slots 1-6 (20 = slot 1) */
    const val SLOT_CODE0 = 20

    /** THE SETTING (Settings > Bottom screen > Tap-to-cast bar): OFF (the default), under the chat (LEFT), under the minimap (RIGHT),
     *  or one bar under BOTH columns */
    enum class Side(val label: String) { OFF("Off"), LEFT("Left"), RIGHT("Right"), BOTH("Both") }

    /** one slot: [index] 0..5, the spell [id], its [icon] file id, the pad [cell] while the add-on's cell casts it (null = do not tap),
     *  [queued] = a fight's edit waits for the fight's end (drawn dimmed) */
    data class Slot(val index: Int, val id: Int?, val icon: Int?, val cell: Int?, val queued: Boolean)

    /** the `castbar` document: [slots] always [SLOTS] (empty ones have no id), [pick] the row's pick cell (null = no pad row),
     *  [fight] the add-on's lockdown now, [padPick] cb2's pick on the padtab row (its columns in order; null = an older add-on),
     *  [n] the add-on's slot count (cb3 = 6; an older add-on's 12 has no slot pick) */
    data class Bar(val slots: List<Slot>, val pick: Int?, val fight: Boolean, val version: String, val padPick: List<Int>? = null,
                   val n: Int = SLOTS) {
        /** a pick can be sent: the padtab pick (cb2) or the row's own pick cell */
        val pickable: Boolean get() = padPick != null || pick != null
        /** cb3: the add-on takes a pick for one slot (the Bind box's Cast bar tab, Book b56) */
        val slotPick: Boolean get() = padPick?.firstOrNull() == PICK && n == SLOTS
        val hasSpells: Boolean get() = slots.any { it.id != null }
        fun has(spellId: Int?): Boolean = spellId != null && slots.any { it.id == spellId }
        val full: Boolean get() = slots.all { it.id != null }
        /** a Spellbook entry is on the bar: the add-on keeps the id Book's taps hand it (the book item's action id, Spells `a`),
         *  which is not the entry's [SpellsModel.Entry.spellId] for an overridden spell, so either one counts */
        fun hasEntry(actionId: Int?, spellId: Int?): Boolean = has(actionId?.takeIf { it > 0 }) || has(spellId)
        /** the slot an entry is on (0..5), or -1 */
        fun slotOf(actionId: Int?, spellId: Int?): Int = slots.indexOfFirst { s ->
            s.id != null && (s.id == actionId?.takeIf { it > 0 } || s.id == spellId) }
    }

    /** the id the add-on stores for a Spellbook entry: Book's taps carry the book item's action id (Spells `a`), else its spell id */
    fun entryId(actionId: Int?, spellId: Int?): Int? = actionId?.takeIf { it > 0 } ?: spellId

    fun parse(msg: JSONObject): Bar? {
        if (msg.optString("type") != "castbar") return null
        val bySlot = HashMap<Int, Slot>()
        msg.optJSONArray("e")?.let { a ->
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val s = o.optInt("s", 0)
                if (s !in 1..SLOTS) continue
                val id = o.optInt("id", 0).takeIf { it > 0 }
                val cell = if (o.has("c")) o.optInt("c", -1).takeIf { it in 0 until SLOTS } else null
                bySlot[s - 1] = Slot(s - 1, id, o.optInt("i", 0).takeIf { it > 0 }, cell, o.optInt("q", 0) == 1)
            }
        }
        val slots = (0 until SLOTS).map { bySlot[it] ?: Slot(it, null, null, null, false) }
        val pick = if (msg.has("pick")) msg.optInt("pick", -1).takeIf { it >= 0 } else null
        val pp = msg.optJSONArray("pp")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }
            ?.takeIf { l -> l.isNotEmpty() && l.size <= 4 && l.all { it in 0 until 40 } }
        return Bar(slots, pick, msg.optBoolean("fight", false), msg.optString("v", ""), pp, msg.optInt("n", 12))
    }

    /** the `castcd` message: slot index (0..5) -> its running cooldown; EMPTY = none runs. Null when it is not one. */
    fun parseCd(msg: JSONObject): Map<Int, PadMapModel.FaceCd>? {
        if (msg.optString("type") != "castcd") return null
        val k = msg.optJSONObject("k") ?: return emptyMap()
        val out = HashMap<Int, PadMapModel.FaceCd>()
        for (s in 1..SLOTS) {
            val o = k.optJSONObject(s.toString()) ?: continue
            val d = o.optDouble("d", 0.0); val l = o.optDouble("l", 0.0)
            if (d > 0 && l > 0 && l <= d + 0.001) out[s - 1] = PadMapModel.FaceCd(d, l, o.optBoolean("g", false), o.optBoolean("e", false))
        }
        return out
    }

    /** a finger on slot [i]: the ONE tap (row, cell), or null (empty, queued, not built) */
    fun tapCell(bar: Bar?, i: Int): Pair<String, Int>? {
        val s = bar?.slots?.getOrNull(i) ?: return null
        if (s.id == null || s.queued) return null
        return s.cell?.let { ROW to it }
    }

    // ---------------------------------------------------------------- cb3: the setting

    /** the saved setting: its name, else (never chosen) the old Home row's On -> LEFT (cb3's migration), else OFF (the default) */
    fun sideOf(saved: String?, oldStrip: Boolean?): Side =
        saved?.let { n -> Side.values().firstOrNull { it.name == n } } ?: if (oldStrip == true) Side.LEFT else Side.OFF

    fun shown(side: Side): Boolean = side != Side.OFF

    // ---------------------------------------------------------------- cb3: the place on Home

    /** a box in Home's px (plain numbers: the off-device tests have no android.graphics) */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
    }

    // ---------------------------------------------------------------- cb4: the pages (Left / Right show 3 at a time)

    /** cb4: the slots one page shows: LEFT / RIGHT 3 (slots 1-3 or 4-6), BOTH all 6, OFF none */
    fun perPage(side: Side): Int = when (side) { Side.LEFT, Side.RIGHT -> PAGE; Side.BOTH -> SLOTS; Side.OFF -> 0 }

    /** cb4: the pages [side] has: LEFT / RIGHT 2, BOTH 1 (all 6 at once), OFF 0 */
    fun pages(side: Side): Int = if (side == Side.OFF) 0 else SLOTS / perPage(side)

    /** cb4: the slot index (0..5) at place [k] (0 = leftmost) on [page] of [side]; -1 = no such place */
    fun slotIndex(side: Side, page: Int, k: Int): Int {
        val n = perPage(side)
        if (k !in 0 until n) return -1
        return page.coerceIn(0, maxOf(0, pages(side) - 1)) * n + k
    }

    /** cb4: the page slot [i] (0..5) is on */
    fun pageOf(side: Side, i: Int): Int = perPage(side).let { n -> if (n == 0) 0 else (i / n).coerceIn(0, pages(side) - 1) }

    /** the slot side for [side] at a bar [barW] wide: every mode the same size (cb4, the user: "they should be the same size as the both
     *  option"), at most [MAX_SIDE], smaller only when the bar is too narrow for its row */
    fun slotSide(side: Side, barW: Float): Float {
        val n = maxOf(1, perPage(side))
        return ((barW - 2 * PAD - GAP * (n - 1)) / n).coerceIn(MIN_SIDE, MAX_SIDE)
    }

    /** the bar's height for [side] on a page [w] wide (0 = none): the slots' row, and on a paged bar the dots' band under it */
    fun height(side: Side, w: Float, gap: Float): Float {
        if (side == Side.OFF) return 0f
        return slotSide(side, barBox(side, w, 1000f, gap).w) + 2 * PAD + dotsBand(side)
    }

    /** cb4: the band under a paged bar's slots that holds its dots (0 = one page) */
    fun dotsBand(side: Side): Float = if (pages(side) > 1) DOTS_H else 0f

    /** the bar's box: [side]'s column (LEFT under the chat, RIGHT under the minimap, BOTH under both) above the page's foot */
    private fun barBox(side: Side, w: Float, h: Float, gap: Float): Box {
        val c = w / 2f
        return when (side) {
            Side.LEFT -> Box(gap, 0f, c - 4f, h - gap)
            Side.RIGHT -> Box(c + 4f, 0f, w - gap, h - gap)
            else -> Box(gap, 0f, w - gap, h - gap)
        }
    }

    /** the bar's place on a page [w] x [h] with Home's module [gap]; null = OFF. The columns it lies under end a gap above it */
    fun place(side: Side, w: Float, h: Float, gap: Float): Box? {
        if (side == Side.OFF) return null
        val col = barBox(side, w, h, gap)
        val bh = height(side, w, gap)
        return Box(col.l, h - gap - bh, col.r, h - gap)
    }

    /** the left column (chat, the quest card above chat, the party module) ends above the bar */
    fun cutsLeft(side: Side): Boolean = side == Side.LEFT || side == Side.BOTH
    /** the right column (the minimap, its auras, the quest card over it) ends above the bar */
    fun cutsRight(side: Side): Boolean = side == Side.RIGHT || side == Side.BOTH

    /** place [k]'s box (0 = leftmost of the page's [perPage]) inside a bar [bw] x [bh] (the bar's own px): spread evenly across, the
     *  same room at each end as half the room between two (BOTH's look; cb4: LEFT / RIGHT alike) */
    fun slotBox(side: Side, k: Int, bw: Float, bh: Float): Box {
        val n = maxOf(1, perPage(side))
        val row = bh - dotsBand(side)
        val s = minOf(slotSide(side, bw), row - 2 * PAD).coerceAtLeast(MIN_SIDE)
        val y0 = (row - s) / 2f
        val g = (bw - 2 * PAD - n * s) / n                                   // an even share each side of every slot
        val x = PAD + g / 2f + k * (s + g)
        return Box(x, y0, x + s, y0 + s)
    }

    /** cb4: the page dots' centres (y, then one x per page) in a bar [bw] x [bh]: in the band under the slots, under the middle;
     *  null = one page */
    fun dots(side: Side, bw: Float, bh: Float): Pair<Float, List<Float>>? {
        val n = pages(side)
        if (n < 2) return null
        val y = bh - PAD - dotsBand(side) / 2f + 4f
        val x0 = bw / 2f - (n - 1) * DOT_STEP / 2f
        return y to (0 until n).map { x0 + it * DOT_STEP }
    }

    // ---------------------------------------------------------------- cb4: the swipe flips the page

    /**
     * a finger that went [dx], [dy] px in [ms] on a bar showing [page]: the page it flips to, or null (a tap, a slow drag, an upward /
     * downward one, BOTH or OFF: nothing flips). Either way flips (there are two pages); it NEVER moves the bar (cb4: the side is set in
     * Settings only). A swipe is a mostly-sideways move of at least [SWIPE_MIN_PX], or a quick flick (at least [FLICK_PX] at
     * [FLICK_PX_PER_MS] or faster)
     */
    fun flipTo(side: Side, page: Int, dx: Float, dy: Float, ms: Long): Int? {
        if (pages(side) < 2) return null
        if (!isSwipe(dx, dy, ms)) return null
        return (page.coerceIn(0, pages(side) - 1) + 1) % pages(side)
    }

    /** the move is a swipe, not a tap (a tap may wander up to [TAP_SLOP_PX]) */
    fun isSwipe(dx: Float, dy: Float, ms: Long): Boolean {
        val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy)
        if (ax < 1.5f * ay) return false
        if (ax >= SWIPE_MIN_PX) return true
        return ax >= FLICK_PX && ms > 0 && ax / ms >= FLICK_PX_PER_MS
    }

    /** the finger is not a tap any more (the slot's cast is not sent on its release) */
    fun leftTap(dx: Float, dy: Float): Boolean = kotlin.math.hypot(dx, dy) > TAP_SLOP_PX

    /** cb4, the slide: the shown page's offset while a finger drags it [dx] on a bar [bw] wide (it follows the finger, at most a page) */
    fun dragOffset(dx: Float, bw: Float): Float = dx.coerceIn(-bw, bw)

    /** cb4, the slide: where the NEW page starts after a flip released at [offset] (the old one goes on the way the finger did, the new
     *  one comes in behind it from the other end), sliding to 0 */
    fun flipStart(offset: Float, bw: Float): Float = if (offset < 0f) offset + bw else if (offset > 0f) offset - bw else bw

    /** cb4, the slide: the other page's offset beside the shown one's [offset] (to its right while the shown one goes left) */
    fun otherOffset(offset: Float, bw: Float): Float = if (offset <= 0f) offset + bw else offset - bw

    /** cb4, the slide: the offset at [t] (0..1) from [from] to 0, eased out */
    fun slideAt(from: Float, t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return from * u * u * u }

    // ---------------------------------------------------------------- cb3: the Bind box's Cast bar tab (Book b56)

    /** the Bind box shows its third tab: the setting is on and the add-on takes a slot pick */
    fun bindTab(side: Side, bar: Bar?): Boolean = side != Side.OFF && bar?.slotPick == true

    /** the taps that open the box's Cast bar tab: padtab 39 then 26 */
    fun tabCells(): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to TAB_CODE)

    /** the taps that put the box's spell on slot [i] (0..5): padtab 39 then 20 + i; null = no such slot */
    fun slotCells(i: Int): List<Pair<String, Int>>? = if (i in 0 until SLOTS) listOf(PADTAB to PICK, PADTAB to SLOT_CODE0 + i) else null

    /** the box's tab after LB ([dir] -1) or RB (+1) from [tab] (0 Normal, 1 In combat, 2 Cast bar); [three] = the Cast bar tab is there */
    fun stepTab(tab: Int, dir: Int, three: Boolean): Int = (tab + dir).coerceIn(0, if (three) 2 else 1)

    /** the cooldown sweep's part still to run at [nowMs] (elapsedRealtime) for a cooldown that arrived at [arrivalMs]: 1 = all, 0 = done */
    fun sweepLeft(cd: PadMapModel.FaceCd, arrivalMs: Long, nowMs: Long): Float {
        val start = PadMapModel.cdStartMs(cd, arrivalMs); val dur = PadMapModel.cdDurMs(cd)
        if (dur <= 0) return 0f
        return (1f - (nowMs - start).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

    /** cb4: a LEFT / RIGHT page's slots (slots 1-3, then 4-6) */
    const val PAGE = 3
    /** every mode's slot side (cb4: LEFT / RIGHT the same as BOTH's; cb3's LEFT / RIGHT were 84 at most, 78 on the Thor) */
    const val MAX_SIDE = 112f
    const val DOT_R = 3.5f
    /** cb4: the dots' band under a LEFT / RIGHT bar's slots (the slots keep BOTH's size; the bar is this much taller) */
    const val DOTS_H = 12f
    const val DOT_STEP = 14f
    const val SLIDE_MS = 220L
    const val MIN_SIDE = 8f
    const val GAP = 8f
    const val PAD = 10f
    const val SWIPE_MIN_PX = 120f
    const val FLICK_PX = 48f
    const val FLICK_PX_PER_MS = 0.6f
    const val TAP_SLOP_PX = 24f

    /** the live bar (the host sets it from every `castbar` document); listeners hear each change */
    object Live {
        /** cb4: the LEFT / RIGHT page shown (0 = slots 1-3, 1 = 4-6), kept for the app's run (not saved: a new run starts on 1-3) */
        @Volatile var page: Int = 0
        @Volatile var bar: Bar? = null
            private set
        private val listeners = ArrayList<() -> Unit>()
        fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
        fun set(b: Bar) {
            if (b == bar) return
            bar = b
            val ls = synchronized(listeners) { listeners.toList() }
            for (l in ls) l()
        }
    }
}
