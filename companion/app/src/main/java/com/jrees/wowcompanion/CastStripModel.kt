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
 * ([CastStripView]) placed by [Side] ([place]); a swipe toward the other side moves it there ([swipeTo]); the Bind box's Cast bar tab
 * ([tabCells], [slotCells]: padtab 39 then 26 / 20-25, Book b56). The Spellbook's own "Cast bar" button is gone (the tab replaces it).
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

    /** slot side, gap and the well's padding for [side] at a bar [barW] wide: LEFT / RIGHT fill their column, BOTH spreads them wider */
    fun slotSide(side: Side, barW: Float): Float {
        val max = if (side == Side.BOTH) MAX_SIDE_BOTH else MAX_SIDE_ONE
        return ((barW - 2 * PAD - GAP * (SLOTS - 1)) / SLOTS).coerceIn(MIN_SIDE, max)
    }

    /** the bar's height for [side] on a page [w] wide (0 = none) */
    fun height(side: Side, w: Float, gap: Float): Float {
        if (side == Side.OFF) return 0f
        return slotSide(side, barBox(side, w, 1000f, gap).w) + 2 * PAD
    }

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

    /** slot [i]'s box inside a bar [bw] x [bh] (the bar's own px): LEFT / RIGHT packed in the middle, BOTH spread evenly across */
    fun slotBox(side: Side, i: Int, bw: Float, bh: Float): Box {
        val s = minOf(slotSide(side, bw), bh - 2 * PAD).coerceAtLeast(MIN_SIDE)
        val y0 = (bh - s) / 2f
        val x = if (side == Side.BOTH) {
            val g = (bw - 2 * PAD - SLOTS * s) / SLOTS                        // an even share each side of every slot
            PAD + g / 2f + i * (s + g)
        } else {
            val gw = s * SLOTS + GAP * (SLOTS - 1)
            (bw - gw) / 2f + i * (s + GAP)
        }
        return Box(x, y0, x + s, y0 + s)
    }

    // ---------------------------------------------------------------- cb3: the swipe

    /**
     * a finger that went [dx], [dy] px in [ms]: the side the bar moves to, or null (a tap, a slow drag, an upward / downward one, BOTH or
     * OFF: nothing moves). Only toward the other side: LEFT moves right, RIGHT moves left. A swipe is a mostly-sideways move of at least
     * [SWIPE_MIN_PX], or a quick flick (at least [FLICK_PX] at [FLICK_PX_PER_MS] or faster)
     */
    fun swipeTo(side: Side, dx: Float, dy: Float, ms: Long): Side? {
        if (side != Side.LEFT && side != Side.RIGHT) return null
        if (!isSwipe(dx, dy, ms)) return null
        return when {
            side == Side.LEFT && dx > 0 -> Side.RIGHT
            side == Side.RIGHT && dx < 0 -> Side.LEFT
            else -> null
        }
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

    const val MAX_SIDE_ONE = 84f        // LEFT / RIGHT: a column's six (cb2's 2 x 6 were 56)
    const val MAX_SIDE_BOTH = 112f      // BOTH: bigger still, spread across the page
    const val MIN_SIDE = 8f
    const val GAP = 8f
    const val PAD = 10f
    const val SWIPE_MIN_PX = 120f
    const val FLICK_PX = 48f
    const val FLICK_PX_PER_MS = 0.6f
    const val TAP_SLOP_PX = 24f

    /** the live bar (the host sets it from every `castbar` document); listeners hear each change */
    object Live {
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
