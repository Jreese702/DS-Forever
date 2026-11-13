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
 * should be the same size as the both option"): LEFT / RIGHT were two pages of 3 at BOTH's size ([slotSide]), with page dots and a
 * swipe that flipped them; the swipe stopped moving the bar (the side is set in Settings only). Replaced by cb5's scroll.
 * cb5 A SCROLL BAR, NOT PAGES (the user, 2026-11-13: "I dont want them shown as pages, I want it to be a literal scroll bar that can stop
 * in any position. do we have space to add more? it looks like there are gaps"; the picks: 4 in view, "4 with an arrow on the sides
 * indicating if there are abilities that way for the left and right options", 6 in all): LEFT / RIGHT are a strip of all 6 at BOTH's
 * size, 4 in view spread evenly ([visible], [pitch], [slotBox] with a scroll), that follows the finger and stops anywhere ([dragScroll],
 * [clampScroll]; a light fling with friction: [velocity], [flingAt], [flingDone]; no snapping), clipped to the well ([window]). The
 * page dots are gone; an edge arrow shows (fading with the scroll) where slots are hidden that way ([arrows], [arrowBox]). A tap casts
 * the slot under the finger at the scroll it is at ([slotAt]). The scroll is kept for the app's run ([Live.pos]). BOTH is unchanged.
 * cb6 EIGHT SLOTS, FOUR PER SIDE (the user, 2026-11-13: "only see 6 slots instead of 8 when using both, I think that you misunderstood
 * and kept 6 total slots on both side even with the slide for more."; the pick: "8 total, 4 per side"): [SLOTS] = 8 everywhere. LEFT /
 * RIGHT: the strip of 8 scrolls under 4 in view (as cb5). BOTH: two wells ([wells]), slots 1-4 under the chat and 5-8 under the
 * minimap, each well exactly where LEFT's / RIGHT's bar lies and each slot where LEFT's / RIGHT's slot at rest lies, no scroll. The Bind
 * box's slots 7-8: padtab 39 then 27-28 ([slotCode]; 20-25 stay slots 1-6, 26 the tab; Book b57, ActionBar cb6).
 * The logic, without the look (unit-tested in dsforever: CastStripModelTest).
 */
object CastStripModel {
    const val ROW = "castbar"
    /** cb2: the row the picks go by (Book b55 / b56) */
    const val PADTAB = "padtab"
    /** cb6: eight slots in all, four per side (the user's pick: "8 total, 4 per side"; cb3's was six) */
    const val SLOTS = 8
    /** cb2: padtab 39 = Smart Range's one-shot pick (PadTabCtl p46), then the cast bar's code */
    const val PICK = 39
    /** b56: the code after [PICK] that opens the Bind box's Cast bar tab */
    const val TAB_CODE = 26
    /** b56: the codes after [PICK] for slots 1-6 (20 = slot 1) */
    const val SLOT_CODE0 = 20
    /** b57 (cb6): the code after [PICK] for slot 7 (28 = slot 8): past [TAB_CODE], which keeps its code */
    const val SLOT_CODE7 = 27

    /** cb6: the padtab code after [PICK] for slot [i] (0..7): 20-25 for slots 1-6, 27-28 for slots 7-8; -1 = no such slot */
    fun slotCode(i: Int): Int = when (i) { in 0..5 -> SLOT_CODE0 + i; 6, 7 -> SLOT_CODE7 + (i - 6); else -> -1 }

    /** THE SETTING (Settings > Bottom screen > Tap-to-cast bar): OFF (the default), under the chat (LEFT), under the minimap (RIGHT),
     *  or one bar under BOTH columns */
    enum class Side(val label: String) { OFF("Off"), LEFT("Left"), RIGHT("Right"), BOTH("Both") }

    /** one slot: [index] 0..7, the spell [id], its [icon] file id, the pad [cell] while the add-on's cell casts it (null = do not tap),
     *  [queued] = a fight's edit waits for the fight's end (drawn dimmed) */
    data class Slot(val index: Int, val id: Int?, val icon: Int?, val cell: Int?, val queued: Boolean)

    /** the `castbar` document: [slots] always [SLOTS] (empty ones have no id), [pick] the row's pick cell (null = no pad row),
     *  [fight] the add-on's lockdown now, [padPick] cb2's pick on the padtab row (its columns in order; null = an older add-on),
     *  [n] the add-on's slot count (cb6 = 8; cb3's 6 and an older add-on's 12 have no slot pick here: their codes differ) */
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
        /** the slot an entry is on (0..7), or -1 */
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

    /** the `castcd` message: slot index (0..7) -> its running cooldown; EMPTY = none runs. Null when it is not one. */
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

    // ---------------------------------------------------------------- the bar in a fight

    /** THE BAR IN A FIGHT (the user, 2026-11-13: "there should be a settings toggle to make them Hide/stay in combat"; the picks: the
     *  Tap-to-cast bar's own section, "Hide in combat" the default): HIDE = the bar goes (cb7: slides away) while Home shows a fight, back when it
     *  ends, taking no touches while hidden; STAY = today's (seen and tappable in a fight, Party mode on or off) */
    enum class Combat(val label: String) { HIDE("Hide"), STAY("Stay") }

    /** the saved choice by name; never chosen (or unknown) = HIDE, the default */
    fun combatOf(saved: String?): Combat = saved?.let { n -> Combat.values().firstOrNull { it.name == n } } ?: Combat.HIDE

    /** the bar is hidden now: HIDE and Home shows a fight ([fighting] = HomeView.fightShown(): the takeover or the grow's fight) */
    fun hiddenIn(mode: Combat, fighting: Boolean): Boolean = mode == Combat.HIDE && fighting

    /**
     * cb7 THE BAR SLIDES AWAY (the user, 2026-11-13: "the tap to cast bar on the bottom currently fades out, but it should slide down
     * behind the home bar out of view, not fade out and give that space back to the map or chat"): HIDE no longer fades. In a fight the
     * bar's box goes down below Home's page ([hiddenBox]: under Home's bottom bar, out of view) and the column(s) it sat under (the chat
     * on LEFT, the minimap on RIGHT, both on BOTH) end at the page's foot again ([columnBottom]); the fight's end brings both back. Home
     * lays the two places and its ONE motion (the page's ValueAnimator, Motion.curve, 200-240 ms) carries the bar's box and the columns'
     * bottoms on the same progress, so they stay together ([slideAt] / [columnAt] are that motion's frames, for the tests). No fade.
     *
     * The gate, off-device: [note] the choice and the fight on every tick (true = hidden changed: Home lays the page again), [sliding]
     * while the bar's box is on its way (Home sets it for the motion), [takesTouch] false while hidden or sliding.
     */
    class FightGate {
        var hidden = false
            private set
        var sliding = false
        fun note(mode: Combat, fighting: Boolean): Boolean {
            val h = hiddenIn(mode, fighting)
            if (h == hidden) return false
            hidden = h
            return true
        }
        fun takesTouch(): Boolean = !hidden && !sliding
        /** nothing to draw: hidden and landed (below the page) */
        val gone: Boolean get() = hidden && !sliding
    }

    /** cb7: the bar's box hidden in a fight: the same box moved down until its top is the page's foot [h] (behind Home's bottom bar) */
    fun hiddenBox(b: Box, h: Float): Box = Box(b.l, h, b.r, h + b.h)

    /** cb7: a column's bottom under the bar: [cut] = where it ends above the laid bar, [full] = its own bottom with no bar; hidden = full */
    fun columnBottom(cut: Float, full: Float, hidden: Boolean): Float = if (hidden) full else cut

    /** cb7: the motion's frame at progress [k] (0 = laid, 1 = hidden; the fight's end runs it back): the bar's box ... */
    fun slideAt(b: Box, h: Float, k: Float): Box {
        val d = (h - b.t) * k.coerceIn(0f, 1f)
        return Box(b.l, b.t + d, b.r, b.b + d)
    }

    /** ... and a column's bottom on the same [k] (between where it ends above the bar and its own bottom) */
    fun columnAt(cut: Float, full: Float, k: Float): Float = cut + (full - cut) * k.coerceIn(0f, 1f)

    // ---------------------------------------------------------------- cb3: the place on Home

    /** a box in Home's px (plain numbers: the off-device tests have no android.graphics) */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
    }

    // ---------------------------------------------------------------- cb5: the scroll (Left / Right: 4 in view, the strip scrolls freely)

    /** cb5: the slots in view at once: LEFT / RIGHT 4 (the strip of all 8 scrolls under them), BOTH all 8 (cb6: 4 per well), OFF none */
    fun visible(side: Side): Int = when (side) { Side.LEFT, Side.RIGHT -> VISIBLE; Side.BOTH -> SLOTS; Side.OFF -> 0 }

    /** cb6: the bar's wells across, in the bar's own px ([bw] wide): BOTH = two, the left column's (slots 1-4) and the right column's
     *  (slots 5-8), [SPLIT] apart at the middle (LEFT's bar ends 4 px left of the page's middle, RIGHT's starts 4 px right of it), so
     *  each is exactly LEFT's / RIGHT's bar; every other side one well, the whole bar */
    fun wells(side: Side, bw: Float): List<Pair<Float, Float>> =
        if (side == Side.BOTH) listOf(0f to bw / 2f - SPLIT / 2f, bw / 2f + SPLIT / 2f to bw) else listOf(0f to bw)

    /** cb6: the well slot [i] lies in (BOTH: 0 for slots 1-4, 1 for 5-8; else 0) */
    fun wellOf(side: Side, i: Int): Int = if (side == Side.BOTH && i >= VISIBLE) 1 else 0

    /** cb6: one well's width in a bar [bw] wide */
    private fun wellW(side: Side, bw: Float): Float = wells(side, bw)[0].let { it.second - it.first }

    /** cb5: the strip scrolls (more slots than fit in view: LEFT / RIGHT) */
    fun scrolls(side: Side): Boolean = visible(side) in 1 until SLOTS

    /** the slot side for [side] at a bar [barW] wide: every mode the same size (cb4, the user: "they should be the same size as the both
     *  option"), at most [MAX_SIDE], smaller only when the bar is too narrow for the slots in view */
    fun slotSide(side: Side, barW: Float): Float {
        val n = VISIBLE                                                  // cb6: 4 in a well, every side (BOTH's well is LEFT's bar)
        return ((wellW(side, barW) - 2 * PAD - GAP * (n - 1)) / n).coerceIn(MIN_SIDE, MAX_SIDE)
    }

    /** the bar's height for [side] on a page [w] wide (0 = none): the slots' row (cb5: no dots band) */
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

    /** the slot side inside a bar [bw] x [bh] */
    private fun sideIn(side: Side, bw: Float, bh: Float): Float = minOf(slotSide(side, bw), bh - 2 * PAD).coerceAtLeast(MIN_SIDE)

    /** cb5: one slot's share across a bar [bw] wide: the slot and an even gap (the slots in view spread evenly, the same room at each
     *  end as half the room between two: BOTH's look, LEFT / RIGHT alike with 4 in view). cb6: across one well (4 to a well) */
    fun pitch(side: Side, bw: Float): Float = (wellW(side, bw) - 2 * PAD) / VISIBLE

    /** cb5: the furthest the strip scrolls (px): the hidden slots' shares; 0 = it does not scroll (BOTH, OFF) */
    fun maxScroll(side: Side, bw: Float): Float = if (!scrolls(side)) 0f else maxOf(0f, (SLOTS - VISIBLE) * pitch(side, bw))

    /** cb5: a scroll held to the strip's ends (any place between: no snapping) */
    fun clampScroll(side: Side, bw: Float, o: Float): Float = o.coerceIn(0f, maxScroll(side, bw))

    /** slot [i]'s box (0..7) inside a bar [bw] x [bh] (the bar's own px) with the strip scrolled [scroll] px (0 = slot 1 leftmost);
     *  cb6: BOTH lays slots 1-4 in the left well and 5-8 in the right one, each at its LEFT / RIGHT place at rest */
    fun slotBox(side: Side, i: Int, bw: Float, bh: Float, scroll: Float = 0f): Box {
        val s = sideIn(side, bw, bh)
        val p = pitch(side, bw)
        val y0 = (bh - s) / 2f
        val k = wellOf(side, i)
        val x = wells(side, bw)[k].first + PAD + (p - s) / 2f + (i - k * VISIBLE) * p - clampScroll(side, bw, scroll)
        return Box(x, y0, x + s, y0 + s)
    }

    /** cb5: the strip's window across (the slots are clipped to it): the well inside its ring edge */
    fun window(bw: Float): Pair<Float, Float> = PAD / 2f to bw - PAD / 2f

    /** cb6: well [k]'s window (BOTH has two; else [window]) */
    fun windowOf(side: Side, bw: Float, k: Int): Pair<Float, Float> = wells(side, bw)[k].let { (l, r) -> l + PAD / 2f to r - PAD / 2f }

    /** cb5: the slot (0..7) under a finger at [x], [y] in a bar [bw] x [bh] scrolled [scroll]: the slot's share across, inside the
     *  window, and the slot's row (half a [GAP] of slack); -1 = none (cb6: BOTH's gap between its two wells too) */
    fun slotAt(side: Side, x: Float, y: Float, bw: Float, bh: Float, scroll: Float): Int {
        if (visible(side) == 0) return -1
        val ws = wells(side, bw)
        val k = ws.indices.firstOrNull { val (wl, wr) = windowOf(side, bw, it); x in wl..wr } ?: return -1
        val p = pitch(side, bw)
        val lo = if (ws.size > 1) k * VISIBLE else 0
        val hi = if (ws.size > 1) lo + VISIBLE - 1 else SLOTS - 1
        val i = (lo + kotlin.math.floor((x - ws[k].first - PAD + clampScroll(side, bw, scroll)) / p).toInt()).coerceIn(lo, hi)
        val r = slotBox(side, i, bw, bh, scroll)
        val m = GAP / 2f
        if (y < r.t - m || y > r.b + m) return -1
        return i
    }

    /** cb5: the slots at least partly in view at [scroll] (their indexes, leftmost first; cb6: each in its own well's window) */
    fun inView(side: Side, bw: Float, bh: Float, scroll: Float): List<Int> =
        (0 until SLOTS).filter { i ->
            val (wl, wr) = windowOf(side, bw, wellOf(side, i))
            slotBox(side, i, bw, bh, scroll).let { it.r > wl && it.l < wr }
        }

    /** cb5: the edge arrows' strength (0 hidden .. 1 shown), left then right: a slot is hidden that way; each fades in over the first
     *  [ARROW_FADE] of a slot's share of scroll away from its end (none on a bar that does not scroll) */
    fun arrows(side: Side, bw: Float, scroll: Float): Pair<Float, Float> {
        if (!scrolls(side)) return 0f to 0f
        val f = pitch(side, bw) * ARROW_FADE
        val o = clampScroll(side, bw, scroll)
        return (o / f).coerceIn(0f, 1f) to ((maxScroll(side, bw) - o) / f).coerceIn(0f, 1f)
    }

    /** cb5: the arrow's box at the bar's [left] or right edge: in the room beside the end slot in view, on the slots' middle */
    fun arrowBox(side: Side, left: Boolean, bw: Float, bh: Float): Box {
        val s = sideIn(side, bw, bh)
        val end = PAD + (pitch(side, bw) - s) / 2f                          // the well's edge to the first slot at rest
        val cx = (PAD / 2f + end) / 2f
        val x = if (left) cx else bw - cx
        return Box(x - ARROW_W / 2f, bh / 2f - ARROW_H / 2f, x + ARROW_W / 2f, bh / 2f + ARROW_H / 2f)
    }

    // ---------------------------------------------------------------- cb5: the finger (drag, fling, tap)

    /** the finger is not a tap any more (the slot's cast is not sent on its release) */
    fun leftTap(dx: Float, dy: Float): Boolean = kotlin.math.hypot(dx, dy) > TAP_SLOP_PX

    /** cb5: the strip under a finger that went [dx] since it held the strip at [start]: it follows the finger (a finger going left
     *  brings the slots on the right into view), held to the ends */
    fun dragScroll(side: Side, bw: Float, start: Float, dx: Float): Float = clampScroll(side, bw, start - dx)

    /** cb5: a finger's speed across (px/ms, + = rightward) from its recent samples ([ts] ms, [xs] px, oldest first): the move over the
     *  last [VELOCITY_MS]; 0 for fewer than two */
    fun velocity(ts: List<Long>, xs: List<Float>): Float {
        if (ts.size < 2 || ts.size != xs.size) return 0f
        val tEnd = ts.last()
        var k = ts.size - 2
        while (k > 0 && tEnd - ts[k - 1] <= VELOCITY_MS) k--
        val dt = (tEnd - ts[k]).toFloat()
        return if (dt <= 0f) 0f else (xs.last() - xs[k]) / dt
    }

    /** cb5: a release at finger speed [v] (px/ms) flings the strip (light momentum; a slow release just stops where it is) */
    fun flings(v: Float): Boolean = kotlin.math.abs(v) * FLING_GAIN >= FLING_MIN

    /** cb5, the fling: the strip's scroll [tMs] after a release at [start] with finger speed [v]: friction-damped, the strip goes on
     *  the way the finger did (the scroll the other way), travel = v * GAIN * TAU * (1 - e^(-t / TAU)); held to the ends */
    fun flingAt(side: Side, bw: Float, start: Float, v: Float, tMs: Float): Float {
        val travel = v * FLING_GAIN * FLING_TAU_MS * (1f - kotlin.math.exp(-tMs / FLING_TAU_MS))
        return clampScroll(side, bw, start - travel)
    }

    /** cb5, the fling is over at [tMs]: its speed fell under [FLING_STOP], or the strip reached the end it was going to */
    fun flingDone(side: Side, bw: Float, start: Float, v: Float, tMs: Float): Boolean {
        if (kotlin.math.abs(v) * FLING_GAIN * kotlin.math.exp(-tMs / FLING_TAU_MS) < FLING_STOP) return true
        val o = flingAt(side, bw, start, v, tMs)
        return (o <= 0f && v > 0f) || (o >= maxScroll(side, bw) && v < 0f)
    }

    /** cb5: the scroll [o] px as slots' shares (kept for the app's run in [Live.pos]: it survives a new layout) and back */
    fun toPos(side: Side, bw: Float, o: Float): Float = pitch(side, bw).let { p -> if (p <= 0f) 0f else clampScroll(side, bw, o) / p }
    fun fromPos(side: Side, bw: Float, pos: Float): Float = clampScroll(side, bw, pos * pitch(side, bw))

    // ---------------------------------------------------------------- cb3: the Bind box's Cast bar tab (Book b56)

    /** the Bind box shows its third tab: the setting is on and the add-on takes a slot pick */
    fun bindTab(side: Side, bar: Bar?): Boolean = side != Side.OFF && bar?.slotPick == true

    /** the taps that open the box's Cast bar tab: padtab 39 then 26 */
    fun tabCells(): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to TAB_CODE)

    /** the taps that put the box's spell on slot [i] (0..7): padtab 39 then [slotCode] (20-25, 27-28); null = no such slot */
    fun slotCells(i: Int): List<Pair<String, Int>>? = if (i in 0 until SLOTS) listOf(PADTAB to PICK, PADTAB to slotCode(i)) else null

    /** the box's tab after LB ([dir] -1) or RB (+1) from [tab] (0 Normal, 1 In combat, 2 Cast bar); [three] = the Cast bar tab is there */
    fun stepTab(tab: Int, dir: Int, three: Boolean): Int = (tab + dir).coerceIn(0, if (three) 2 else 1)

    /** the cooldown sweep's part still to run at [nowMs] (elapsedRealtime) for a cooldown that arrived at [arrivalMs]: 1 = all, 0 = done */
    fun sweepLeft(cd: PadMapModel.FaceCd, arrivalMs: Long, nowMs: Long): Float {
        val start = PadMapModel.cdStartMs(cd, arrivalMs); val dur = PadMapModel.cdDurMs(cd)
        if (dur <= 0) return 0f
        return (1f - (nowMs - start).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

    /** cb5: the slots in view at once on a LEFT / RIGHT bar (the user's pick: "4 with an arrow on the sides") */
    const val VISIBLE = 4
    /** every mode's slot side (cb4: LEFT / RIGHT the same as BOTH's; cb3's LEFT / RIGHT were 84 at most, 78 on the Thor) */
    const val MAX_SIDE = 112f
    const val MIN_SIDE = 8f
    const val GAP = 8f
    const val PAD = 10f
    /** cb6: BOTH's room between its two wells (the page's middle: LEFT's bar ends 4 px left of it, RIGHT's starts 4 px right) */
    const val SPLIT = 8f
    const val TAP_SLOP_PX = 24f
    /** cb5: the edge arrow (px) and its fade: full once this part of a slot's share is hidden that way */
    const val ARROW_W = 12f
    const val ARROW_H = 28f
    const val ARROW_FADE = 0.5f
    /** cb5, the fling: light momentum (the finger's speed times [FLING_GAIN]), friction time [FLING_TAU_MS]; it starts at [FLING_MIN]
     *  px/ms and ends under [FLING_STOP]; the finger's speed is read over its last [VELOCITY_MS] */
    const val FLING_GAIN = 0.7f
    const val FLING_TAU_MS = 260f
    const val FLING_MIN = 0.15f
    const val FLING_STOP = 0.02f
    const val VELOCITY_MS = 100L

    /** the live bar (the host sets it from every `castbar` document); listeners hear each change */
    object Live {
        /** cb5: the LEFT / RIGHT strip's scroll in slots' shares (0 = slot 1 leftmost, 4 = slot 8 rightmost, any place between), kept
         *  for the app's run (not saved: a new run starts at slot 1) */
        @Volatile var pos: Float = 0f
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
