package com.jrees.wowcompanion

/**
 * PARTY MODE'S HOME MODULE, EVERY RECT (party_design.md C and the user's decisions 2026-11-11; it replaces PARTY_BUILD.md 4.2's grid).
 * Pure (no Android types: [Box], never RectF), JVM-tested (PartyLayoutTest). All px on the 1240 x 1080 bottom screen.
 *
 * ONE COLUMN, the top screen's order: you, then party1..4, one plate per member. The well is x 8..615; the Select tab always stands at
 * x 20..56, the column's full height, and the plates run x 64..603, so nothing moves when Party mode turns on or off (the user: the
 * tab STAYS and lights up gold while Party mode is on). Plates 64 high at rest, 88 in a fight with Party mode on (bigger targets), 56
 * in the takeover (a fight with Party mode off, the well's top at y 398); 8 px between them.
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

    /** [cells] = the plates in box order (index 0 = you); [tag] = the Select tab (always there) */
    data class Module(val well: Box, val cells: List<Box>, val tag: Box, val chatTop: Float)

    const val LEFT = 8f
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
    /** the Select tab: x 20..56 (the well's left + 12, 36 wide), then 8 px to the plates (x 64) */
    const val SIDE_W = 36f
    const val SIDE_GAP = 8f
    const val REST_H = 64f
    const val FIGHT_H = 88f
    const val TAKEOVER_H = 56f

    fun defaultTop(mode: Mode) = if (mode == Mode.FIGHT_OFF) FIGHT_TOP else REST_TOP
    fun plateH(mode: Mode) = when (mode) { Mode.REST -> REST_H; Mode.FIGHT_ON -> FIGHT_H; Mode.FIGHT_OFF -> TAKEOVER_H }

    /** the module for [n] members (1..5) in [mode], its well's top at [top] */
    fun module(mode: Mode, n: Int, top: Float = defaultTop(mode), left: Float = LEFT, right: Float = RIGHT): Module {
        val m = n.coerceIn(1, 5)
        val h = plateH(mode)
        val pl = left + IN + SIDE_W + SIDE_GAP; val pr = right - IN
        val cells = (0 until m).map { k -> val t = top + IN + k * (h + GAP); Box(pl, t, pr, t + h) }
        val well = Box(left, top, right, cells.last().b + IN)
        return Module(well, cells, Box(left + IN, well.t + IN, left + IN + SIDE_W, well.b - IN), well.b + CHAT_GAP)
    }

    // ------------------------------------------------------------------------------------------------ a plate's parts

    /** in a fight (Party mode on, or the takeover): the bigger icon and name */
    private fun big(mode: Mode) = mode != Mode.REST

    /** the class-colour stripe, 6 px down the plate's left */
    fun stripe(p: Box) = Box(p.l, p.t, p.l + 6f, p.b)
    /** the class / role icon: 40 px (48 in a fight), 14 in, centred */
    fun icon(p: Box, mode: Mode): Box { val s = if (big(mode)) 48f else 40f; return Box(p.l + 14f, p.cy - s / 2f, p.l + 14f + s, p.cy + s / 2f) }
    /** the name's left and size (24 px, 28 in a fight), its vertical centre the plate's */
    fun nameX(p: Box, mode: Mode) = icon(p, mode).r + 12f
    fun nameSize(mode: Mode) = if (big(mode)) 28f else 24f
    /** the member's LB + direction glyph at the right edge, 36 px (the top screen's glyph), while Party mode is on */
    fun glyph(p: Box) = Box(p.r - 8f - 36f, p.cy - 18f, p.r - 8f, p.cy + 18f)
    const val CHIP = 40f
    const val CHIP_STEP = 50f
    const val BADGE = 24f
    /** the picked plate's chips (A, X, Y of the layer on show), [k] of them, right-aligned before the LB glyph, 50 px apart */
    fun chips(p: Box, k: Int): List<Box> {
        val right = glyph(p).l - 12f
        return (0 until k).map { j ->
            val r = right - (k - 1 - j) * CHIP_STEP
            Box(r - CHIP, p.cy - CHIP / 2f, r, p.cy + CHIP / 2f)
        }
    }
    /** a chip's glyph badge (24 px) on its top-right corner */
    fun badge(chip: Box) = Box(chip.r - BADGE / 2f - 4f, chip.t - BADGE / 2f + 4f, chip.r + BADGE / 2f - 4f, chip.t + BADGE / 2f + 4f)
    /** the room the name has before the chips (or the glyph) */
    fun nameRoom(p: Box, mode: Mode, chips: Int, glyph: Boolean): Float {
        val stop = if (chips > 0) chips(p, chips).first().l else if (glyph) glyph(p).l else p.r - 8f
        return stop - 10f - nameX(p, mode)
    }
    /** a state icon (skull: dead / ghost; plug: offline) after the name, 28 px */
    fun state(p: Box, x: Float) = Box(x + 8f, p.cy - 14f, x + 36f, p.cy + 14f)

    /** the cell index under ([x], [y]), else -1 */
    fun hit(m: Module, x: Float, y: Float): Int = m.cells.indexOfFirst { it.contains(x, y) }
    fun hitTag(m: Module, x: Float, y: Float): Boolean = m.tag.contains(x, y)

    // ------------------------------------------------------------------------------------------------ the Select tab

    /**
     * The tab's parts (party_design.md E): the 32 px Select glyph, the 20 px gold arrow under it, and (on) a small B under the arrow.
     * The user's decision 1 (2026-11-11) says the On look shows B, so B is never left out: on a tab too short for all three (one plate
     * at rest 64 px, the takeover's 56 px) the B takes the ARROW's place while Party mode is on ([bOnArrow]: the arrow is drawn only
     * off / unbound, the B at [bCy] = [arrowCy]). [notchW] = the 6 px gold notch on the right edge at the picked plate's centre.
     */
    data class TabParts(val glyphCx: Float, val glyphCy: Float, val glyph: Float, val arrowCy: Float, val arrow: Float,
                        val bCy: Float, val b: Float, val lockCy: Float, val lock: Float, val notchW: Float, val bOnArrow: Boolean)
    fun tabParts(t: Box): TabParts {
        val tall = t.h >= FIGHT_H                                        // one fight plate (88) still holds all three
        return if (tall) TabParts(t.cx, t.cy - 24f, 32f, t.cy + 6f, 20f, t.cy + 32f, 20f, t.b - 14f, 18f, 6f, false)
        else TabParts(t.cx, t.cy - 10f, 30f, t.cy + 16f, 18f, t.cy + 16f, 18f, t.b - 11f, 16f, 6f, true)
    }

    /** the notch's centre y: the picked plate's (1-based [box]) */
    fun notchY(m: Module, box: Int): Float = m.cells[(box - 1).coerceIn(0, m.cells.size - 1)].cy
}

/**
 * THE SELECT TAB'S STATES AND MOTION (party_design.md E and the user's decision 2026-11-11 "the Select tab STAYS and lights up gold
 * while party mode is on"). Pure, JVM-tested (SelectTabTest); [PartyModuleView] draws from it, the clock is the caller's (ms).
 *
 * The look follows the party document's `on` only (never ahead of it: a tap can be refused): OFF (Select bound), ON, UNBOUND (`sel`
 * off / dialog / aside: the glyph at 40 % and a lock; a tap still turns it on). On / off: a gold wipe and the arrow turning 180 deg over
 * 180 ms. A Select press seen by the app's reader: the pressed look for 120 ms (the glyph at 0.92, the edge rgb(196, 156, 72)). The
 * gold notch slides to the pick in 120 ms. The edge pulses twice (alpha 0.5 to 1, 300 ms each) when a group forms or Home opens
 * grouped - never in a fight.
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
