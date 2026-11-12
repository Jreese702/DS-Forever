package com.jrees.wowcompanion

/**
 * PARTY MODE'S HOME MODULE, EVERY RECT (party_design.md C and the user's decisions 2026-11-11; it replaces PARTY_BUILD.md 4.2's grid).
 * Pure (no Android types: [Box], never RectF), JVM-tested (PartyLayoutTest). All px on the 1240 x 1080 bottom screen.
 *
 * m134 THE PLAYER / PET ROW (party_design.md "PLAYER / PET REVISION"; the user, 2026-11-11, after using 1428: "The size of the player
 * and the pet don't feel right. Like the player is too small and the pet is too big. I think the pet should be to the right of the
 * player"): the entries (you, your pet while one is out, party1..4) in [PartyModel.rows]: the FIRST row is you full width (no pet) or
 * you + the pet on your right, you the big cell ([PET_SHARE]: the pet about a third, clearly secondary); the party members fill the rows
 * under it by the adaptive rule among themselves (1-2: full rows; 3: a full row then a shared one; 4: 2x2, equal halves).
 *
 * m134 THE SELECT TAB OUTSIDE THE BAND (the user, 2026-11-11: "The side panel around the select button looks like an AI border. It
 * needs to use wow assets and sit outside of the player brand look more like its attached to the side of the screen"): the tab is a
 * drawer handle flush on the screen's left edge ([left], x 0 on Home), [TAB_W] wide and the well's full height; the well (the member
 * band) starts [TAB_GAP] right of it and runs to [right] (615 on Home), the cells [IN] inside it. Nothing moves when Party mode turns on
 * or off (the user: the tab STAYS and lights up while Party mode is on). Rows 64 high at rest, 88 in a fight with Party mode on (bigger
 * targets), 56 in the takeover (a fight with Party mode off, the well's top at y 398); 8 px between them.
 */
object PartyLayout {
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
        val cx: Float get() = (l + r) / 2f
        val cy: Float get() = (t + b) / 2f
        fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
        fun offset(dx: Float, dy: Float) = Box(l + dx, t + dy, r + dx, b + dy)
    }

    enum class Mode { REST, FIGHT_ON, FIGHT_OFF }

    /** [cells] = the plates in box order (index 0 = you); [tag] = the Select tab (always there); [rows] = each cell's row (0-based) */
    data class Module(val well: Box, val cells: List<Box>, val tag: Box, val chatTop: Float, val rows: List<Int> = cells.indices.toList())

    /** the screen's left edge (the tab's place) on Home */
    const val EDGE = 0f
    const val RIGHT = 615f
    /** the well's inside margin */
    const val IN = 12f
    const val GAP = 8f
    /** the left column's top at rest on the device (barsBottom + MODULE_GAP) */
    const val REST_TOP = 216f
    /** the left well's top under the fight takeover */
    const val FIGHT_TOP = 398f
    /** chat starts this far under the well's foot */
    const val CHAT_GAP = 8f
    /** m134: the Select tab, flush on the screen's edge (x 0..52), then 4 px to the well (x 56..615: the cells x 68..603) */
    const val TAB_W = 52f
    const val TAB_GAP = 4f
    const val REST_H = 64f
    const val FIGHT_H = 88f
    const val TAKEOVER_H = 56f
    /** m134 (the user, 2026-11-11: "the player is too small and the pet is too big"): the pet's share of the first row, you the rest */
    const val PET_SHARE = 0.33f

    fun defaultTop(mode: Mode) = if (mode == Mode.FIGHT_OFF) FIGHT_TOP else REST_TOP
    fun plateH(mode: Mode) = when (mode) { Mode.REST -> REST_H; Mode.FIGHT_ON -> FIGHT_H; Mode.FIGHT_OFF -> TAKEOVER_H }

    /**
     * the module for [n] entries (1..6) in [mode], its well's top at [top]; [pet] = the second entry is your pet (m134: on your right
     * in the first row, the smaller cell); [left] = the screen's edge where the tab stands, [right] = the well's right
     */
    fun module(mode: Mode, n: Int, pet: Boolean = false, top: Float = defaultTop(mode), left: Float = EDGE, right: Float = RIGHT): Module {
        val m = n.coerceIn(1, PartyModel.MAX_N)
        val h = plateH(mode)
        val wl = left + TAB_W + TAB_GAP
        val pl = wl + IN; val pr = right - IN
        val cells = ArrayList<Box>(m)
        val rowOf = ArrayList<Int>(m)
        for ((r, row) in PartyModel.rows(m, pet).withIndex()) {
            val t = top + IN + r * (h + GAP)
            if (row.size == 1) { cells.add(Box(pl, t, pr, t + h)); rowOf.add(r); continue }
            // the first row with the pet: you the big cell; two party members: equal halves
            val share = if (pet && row[1] == 2) 1f - PET_SHARE else 0.5f
            val mid = pl + (pr - pl - GAP) * share
            cells.add(Box(pl, t, mid, t + h)); cells.add(Box(mid + GAP, t, pr, t + h)); rowOf.add(r); rowOf.add(r)
        }
        val well = Box(wl, top, right, cells.last().b + IN)
        return Module(well, cells, Box(left, well.t, left + TAB_W, well.b), well.b + CHAT_GAP, rowOf)
    }

    // ------------------------------------------------------------------------------------------------ a plate's parts

    /** in a fight (Party mode on, or the takeover): the bigger icon and name */
    private fun big(mode: Mode) = mode != Mode.REST

    /** the class-colour stripe, 6 px down the plate's left (a person's plate only: m134, never the pet's) */
    fun stripe(p: Box) = Box(p.l, p.t, p.l + 6f, p.b)
    /** the class / role icon (the pet's portrait): 40 px (48 in a fight), 14 in, centred */
    fun icon(p: Box, mode: Mode): Box { val s = if (big(mode)) 48f else 40f; return Box(p.l + 14f, p.cy - s / 2f, p.l + 14f + s, p.cy + s / 2f) }
    /** the name's left and size (24 px, 28 in a fight), its vertical centre the plate's */
    fun nameX(p: Box, mode: Mode) = icon(p, mode).r + 12f
    fun nameSize(mode: Mode) = if (big(mode)) 28f else 24f
    /**
     * the room the name has before the plate's end. m134 (the user, 2026-11-11: "the a button glyph on them needs to be removed since
     * highlighting it should target and a will be whatever spell is bound"): no A / X / Y chips and no LB + button glyph on the plates
     */
    fun nameRoom(p: Box, mode: Mode): Float = p.r - 8f - 10f - nameX(p, mode)
    /** a state icon (skull: dead / ghost; plug: offline) after the name, 28 px */
    fun state(p: Box, x: Float) = Box(x + 8f, p.cy - 14f, x + 36f, p.cy + 14f)

    /** the cell index under ([x], [y]), else -1 */
    fun hit(m: Module, x: Float, y: Float): Int = m.cells.indexOfFirst { it.contains(x, y) }
    fun hitTag(m: Module, x: Float, y: Float): Boolean = m.tag.contains(x, y)

    // ------------------------------------------------------------------------------------------------ the Select tab

    /**
     * The tab's parts (party_design.md E): the 32 px Select glyph, the 20 px gold arrow under it, and (on) a small B under the arrow.
     * The user's decision 1 (2026-11-11) says the On look shows B, so B is never left out: on a tab too short for all three (the
     * takeover's one row) the B takes the ARROW's place while Party mode is on ([bOnArrow]: the arrow is drawn only off / unbound, the B
     * at [bCy] = [arrowCy]). [notch] = the game's small gold arrow on the tab's right edge at the picked plate's row.
     */
    data class TabParts(val glyphCx: Float, val glyphCy: Float, val glyph: Float, val arrowCy: Float, val arrow: Float,
                        val bCy: Float, val b: Float, val lockCy: Float, val lock: Float, val notch: Float, val bOnArrow: Boolean)
    fun tabParts(t: Box): TabParts {
        val tall = t.h >= FIGHT_H                                        // one rest row with the well's margins (88) holds all three
        return if (tall) TabParts(t.cx, t.cy - 24f, 32f, t.cy + 6f, 20f, t.cy + 32f, 20f, t.b - 14f, 18f, 16f, false)
        else TabParts(t.cx, t.cy - 10f, 30f, t.cy + 16f, 18f, t.cy + 16f, 18f, t.b - 11f, 16f, 14f, true)
    }

    /** the notch's centre y: the picked plate's (1-based [box]): its row's centre (a shared row's two cells share it) */
    fun notchY(m: Module, box: Int): Float = m.cells[(box - 1).coerceIn(0, m.cells.size - 1)].cy
}

/**
 * THE SELECT TAB'S STATES AND MOTION (party_design.md E and the user's decision 2026-11-11 "the Select tab STAYS and lights up gold
 * while party mode is on"). Pure, JVM-tested (SelectTabTest); [PartyModuleView] draws from it, the clock is the caller's (ms).
 *
 * The look follows the party document's `on` only (never ahead of it: a tap can be refused): OFF (Select bound), ON, UNBOUND (`sel`
 * off / dialog / aside: the glyph at 40 % and a lock; a tap still turns it on). On / off: the lit art wiped in / out and the arrow
 * turning 180 deg over 180 ms. A Select press seen by the app's reader: the pressed look for 120 ms (the glyph at 0.92). The notch
 * slides to the pick in 120 ms. The lit art pulses twice (300 ms each) when a group forms or Home opens grouped - never in a fight.
 * m134 (the user, 2026-11-11: "It needs to use wow assets"): every one of these is drawn with the game's side tab art
 * (UiKit.sideHandle), never a drawn edge or glow.
 */
class SelectTab {
    enum class Look { OFF, ON, UNBOUND }

    companion object {
        const val WIPE_MS = 180L
        const val PRESS_MS = 120L
        const val NOTCH_MS = 120L
        const val PULSE_MS = 300L
        const val PULSES = 2
        const val PRESS_SCALE = 0.92f

        /** the look for a party document (null = nothing known yet: off) */
        fun lookOf(p: PartyModel.Party?): Look = when {
            p?.onLive == true -> Look.ON
            p == null || p.sel == "bound" -> Look.OFF
            else -> Look.UNBOUND
        }
    }

    var look = Look.OFF
        private set
    private var changeAt = Long.MIN_VALUE / 4
    private var wipeFrom = 0f
    private var pressAt = Long.MIN_VALUE / 4
    private var notchFrom: Float? = null
    private var notchTo: Float? = null
    private var notchAt = Long.MIN_VALUE / 4
    private var pulseAt = Long.MIN_VALUE / 4
    private var wasGrouped: Boolean? = null

    private var seen = false

    /** the document's look now; a change starts the wipe from wherever it stands (the first look is shown as it is, no wipe) */
    fun setLook(l: Look, now: Long) {
        if (!seen) { seen = true; look = l; wipeFrom = if (l == Look.ON) 1f else 0f; changeAt = Long.MIN_VALUE / 4; return }
        if (l == look) return
        wipeFrom = wipe(now)
        look = l
        changeAt = now
    }

    /** 0 = off .. 1 = on (the gold's share and the arrow's turn) */
    fun wipe(now: Long): Float {
        val target = if (look == Look.ON) 1f else 0f
        val t = ((now - changeAt).toFloat() / WIPE_MS).coerceIn(0f, 1f)
        return wipeFrom + (target - wipeFrom) * t
    }
    /** the arrow: 0 deg = pointing into the column (off), 180 = pointing out (on) */
    fun arrowDeg(now: Long): Float = 180f * wipe(now)

    fun press(now: Long) { pressAt = now }
    fun pressed(now: Long): Boolean = now >= pressAt && now - pressAt < PRESS_MS
    fun glyphScale(now: Long): Float = if (pressed(now)) PRESS_SCALE else 1f

    /** the notch's target (the picked plate's centre y); null = Party mode off (no notch) */
    fun setNotch(y: Float?, now: Long) {
        if (y == notchTo) return
        notchFrom = if (y == null) null else (notchY(now) ?: y)
        notchTo = y
        notchAt = now
    }
    fun notchY(now: Long): Float? {
        val to = notchTo ?: return null
        val from = notchFrom ?: return to
        val t = ((now - notchAt).toFloat() / NOTCH_MS).coerceIn(0f, 1f)
        return from + (to - from) * t
    }

    /** the party document's group state: a group forming (out of a fight) pulses the edge */
    fun groupSeen(grouped: Boolean, fight: Boolean, now: Long) {
        if (grouped && wasGrouped == false && !fight) pulseAt = now
        wasGrouped = grouped
    }
    /** Home opened: grouped (out of a fight) pulses the edge */
    fun homeOpened(grouped: Boolean, fight: Boolean, now: Long) { if (grouped && !fight) pulseAt = now }

    /** the edge's alpha: 0.5 .. 1 .. 0.5 per 300 ms pulse, twice; else 1 */
    fun edgeAlpha(now: Long): Float {
        val dt = now - pulseAt
        if (dt < 0 || dt >= PULSE_MS * PULSES) return 1f
        val ph = (dt % PULSE_MS).toFloat() / PULSE_MS
        return 0.5f + 0.5f * kotlin.math.sin(Math.PI * ph).toFloat()
    }

    /** something moves now (the view keeps redrawing) */
    fun moving(now: Long): Boolean = now - changeAt < WIPE_MS || pressed(now) || (now - notchAt < NOTCH_MS && notchTo != null) ||
        (now - pulseAt in 0 until PULSE_MS * PULSES)
}
