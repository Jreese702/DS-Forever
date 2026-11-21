package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.json.JSONObject

/**
 * THE LB PARTY TARGETING, PICTURED (the user, 2026-11-08: "When holding LB i want the bottom screen to shift around like when opening a
 * radial, in the top part of the middle should be the players bar with the A button glyph, and below that should be a cross layout of the
 * other 4 party members with the dpad glyphs. Pressing a button should have visual feedback of that button press with the player bar.
 * This is the default controller behavior for selecting a party member to target. We are just visualizing it. This should shift over
 * from party mode if its on.").
 *
 * The game does the targeting (its own LB + A / D-pad bindings); nothing here presses anything. Which button is whose comes from the
 * game's own bindings ([LbMap], PadMap m104's "lbmap"; the user: "read it from the game"). Shown while LB alone is held on Home (CastView's
 * controller reader); Home's panels slide aside as for a wheel (the host's [onSlide]); a press of a mapped button lights its bar.
 * 1.0.19 (the user: "the "L target" text needs to be removed ... at the very top above the player it should show what I am currently soft
 * targeting and what LB will target if I release with no buttons ... I dont like the way everything fits together ... just enough to make
 * space for the selections"): no title; the top line = your soft target | LB alone's target; one centred column ([COL_W] bars: you, Up,
 * Left + Right side by side, Down), Home's sides moving only [SIDE_PX].
 */
class LbPartyView(context: Context) : View(context) {
    /** Home's panels' slide (the pause wheel's): 0 = at rest .. UiKit.PAUSE_OPEN_MS = out of the way */
    var onSlide: (Float) -> Unit = {}
    /** fix/menus-fit: where the picture stands on Home as laid now (HomeView.lbPlace, moved into this view's coordinates by the host);
     *  null = the old fixed place (the page's middle, under 1516's top) */
    var place: (() -> LbFit.Place?)? = null

    private var held = false
    private var changedAt = 0L
    private var fromT = 0f
    private var mask = 0
    /** the last press of each unit's button (uptime ms): the bar's feedback */
    private val pressedAt = HashMap<String, Long>()

    init { visibility = GONE; isClickable = false }

    val isShown2 get() = visibility == VISIBLE

    /** LB alone held (true) or let go (false) */
    fun lb(on: Boolean) {
        if (on == held) return
        fromT = tNow()
        held = on; changedAt = SystemClock.uptimeMillis()
        if (on) { visibility = VISIBLE; bringToFront() }
        invalidate()
    }

    /** fix/lb-home-only: Home is no longer in front (a room, an Interact window, a wheel...): gone at once, no fade back */
    fun drop() {
        held = false; fromT = 0f; changedAt = SystemClock.uptimeMillis()
        visibility = GONE
    }

    /** the controller's held buttons (PadInput slots): a new press of a mapped button lights its bar */
    fun buttons(m: Int) {
        val down = m and mask.inv()
        mask = m
        if (!held || down == 0) return
        val now = SystemClock.uptimeMillis()
        for ((slot, key) in SLOT_KEY) if (PadInput.isHeld(down, slot)) LbMap.unitOf(key)?.let { pressedAt[it] = now }
        invalidate()
    }

    /** the open's progress, 0..PAUSE_OPEN_MS (back down to 0 after LB is let go) */
    private fun tNow(): Float {
        val dt = (SystemClock.uptimeMillis() - changedAt) * HomePauseView.SPEED
        return if (held) (fromT + dt).coerceAtMost(UiKit.PAUSE_OPEN_MS) else (fromT - dt).coerceAtLeast(0f)
    }

    private val pText by lazy { UiKit.bodyPaint(30f, Color.WHITE) }
    private val pSmall by lazy { UiKit.bodyPaint(22f, Theme.dim) }

    override fun onDraw(c: Canvas) {
        val t = tNow()
        onSlide(t)
        if (!held && t <= 0f) { visibility = GONE; return }
        val k = (t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f)
        val a = (255 * k).toInt()
        val w = width.toFloat()
        val party = PartyModel.live.party
        val members = party?.m.orEmpty()
        fun member(unit: String) = members.firstOrNull { it.u == unit }
        val now = SystemClock.uptimeMillis()
        // under the controller (it stays where it is: the user, "don't make the top controller part shift away"); no line over you
        // (the user: "Scrap the soft target bit" ... "no remove the L target from that"). fix/menus-fit: on Home's page as laid
        // (LbFit: the band's bottom now, the line between the columns, scaled to fit above a bar that stays)
        val pl = place?.invoke() ?: run {
            val s0 = minOf(w / 1240f, height / 1080f)
            LbFit.Place(w / 2f, maxOf(236f * s0, UiKit.pauseBarsBottom + 16f * s0), s0, SIDE_PX, SIDE_PX)
        }
        val s = pl.k
        val boxes = LbFit.bars(pl)
        fun rf(b: LbFit.Box) = RectF(b.l, b.t, b.r, b.b)
        // YOU, with the A glyph, then the four as the game binds them: Up, Left + Right side by side, Down
        bar(c, rf(boxes.getValue("PAD1")), member("player"), "player", LbMap.keyOf("player") ?: "PAD1", a, now, s)
        for (key in listOf("PADDUP", "PADDLEFT", "PADDRIGHT", "PADDDOWN")) {
            val r = rf(boxes.getValue(key))
            val unit = LbMap.unitOf(key)
            bar(c, r, unit?.let { member(it) }, unit, key, a, now, s)
        }
        if (held && t < UiKit.PAUSE_OPEN_MS || !held || pressedAt.values.any { now - it < PRESS_MS }) postInvalidateOnAnimation()
    }

    /** one member's bar: the button's glyph at its left, the name in the class colour, the level / dead / offline (m129: no health or
     *  mana on the bottom); lit while its press shows */
    private fun bar(c: Canvas, r: RectF, m: PartyModel.Member?, unit: String?, key: String, a: Int, now: Long, s: Float) {
        val pressed = unit != null && (pressedAt[unit]?.let { now - it < PRESS_MS } ?: false)
        UiKit.pressed(c, r, pressed) {
            UiKit.module(c, r, a)
            val gx = r.left + 52f * s; val cy = r.centerY()
            UiKit.pad(c, glyphOf(key), gx, cy, 64f * s, if (m != null || unit == "player") a else a / 2)
            val x0 = r.left + 96f * s; val x1 = r.right - 22f * s
            if (m == null) {
                c.drawText(Tr.t(if (unit == null) "Not bound" else "Empty"), x0, cy + 8f * s, pSmall.apply { alpha = a / 2; textSize = 24f * s })
                return@pressed
            }
            val col = classColour(m)
            pText.textSize = 28f * s; pText.color = col; pText.alpha = a
            val name = android.text.TextUtils.ellipsize(m.n, android.text.TextPaint(pText), x1 - x0, android.text.TextUtils.TruncateAt.END).toString()
            // m129 (party_design.md A.5, the user 2026-11-11: no health or mana on the bottom): the name, the level, dead / offline
            pText.alpha = if (m.dead || m.dim) a / 2 else a
            c.drawText(name, x0, r.centerY() - 2f * s, pText)
            val sub = when { m.dead -> "Dead"; m.off -> "Offline"; else -> m.lv?.let { "Level $it" } }
            sub?.let { c.drawText(Tr.t(it), x0, r.centerY() + 28f * s, pSmall.apply { alpha = a; textSize = 22f * s }) }
        }
        if (pressed) UiKit.focus(c, r)
    }

    private fun classColour(m: PartyModel.Member): Int {
        m.col?.let { s -> runCatching { return (s.toLong(16) or 0xFF000000L).toInt() } }
        return Theme.classColourOf(m.c)
    }

    companion object {
        const val PRESS_MS = 220L
        /** 1.0.19: the column's bar width, and how far Home's sides move for it (the column with a margin, half each side) */
        const val COL_W = 300f
        const val SIDE_PX = 340f
        /** fix/menus-fit: the picture's height at full size (you 100, 30, Up 100, 16, Left + Right 100, 16, Down 100) */
        const val PICT_H = 462f

        /** fix/lb-home-only: the picture is up but Home is not in front ([onHome] false: a room; [covered]: the host's overlay gate,
         *  CompanionHost.overlayUp, or the target radial) - it goes at once instead of fading */
        fun dropNow(onHome: Boolean, covered: Boolean): Boolean = !onHome || covered

        private val SLOT_KEY = listOf(PadInput.SLOT_FACE_BOTTOM to "PAD1", PadInput.SLOT_DPAD_UP to "PADDUP", PadInput.SLOT_DPAD_RIGHT to "PADDRIGHT",
            PadInput.SLOT_DPAD_DOWN to "PADDDOWN", PadInput.SLOT_DPAD_LEFT to "PADDLEFT", PadInput.SLOT_FACE_RIGHT to "PAD2",
            PadInput.SLOT_FACE_LEFT to "PAD3", PadInput.SLOT_FACE_TOP to "PAD4")

        fun glyphOf(key: String): String = when (key) {
            "PADDUP" -> "gen_up"; "PADDDOWN" -> "gen_down"; "PADDLEFT" -> "gen_left"; "PADDRIGHT" -> "gen_right"
            "PAD1" -> UiKit.okGlyph()
            else -> "gen_" + key.removePrefix("PAD").lowercase()
        }
    }
}

/**
 * WHICH LB BUTTON TARGETS WHOM (PadMap m104's "lbmap", read from the game's own bindings; the user: "read it from the game"). Pure.
 * Until the game has said: A = you, and the D-pad clockwise from the top (Up party1, Right party2, Down party3, Left party4).
 */
object LbMap {
    private val DEFAULT = mapOf("player" to "PAD1", "party1" to "PADDUP", "party2" to "PADDRIGHT", "party3" to "PADDDOWN", "party4" to "PADDLEFT")
    @Volatile var byUnit: Map<String, String> = DEFAULT
        private set
    /** true once the game's own bindings have been read */
    @Volatile var fromGame = false
        private set

    /** m121: what LB tapped alone does (the game's binding for the modifier button by itself) and the game's name for it */
    @Volatile var tap: String? = null
        private set
    @Volatile var tapName: String? = null
        private set

    fun update(d: JSONObject) {
        tap = d.optString("tap", "").ifEmpty { null }
        tapName = d.optString("tapn", "").ifEmpty { null }
        val m = HashMap<String, String>()
        fun put(field: String, unit: String) = d.optString(field, "").takeIf { it.startsWith("PAD") }?.let { m[unit] = it }
        put("self", "player"); put("p1", "party1"); put("p2", "party2"); put("p3", "party3"); put("p4", "party4")
        if (m.isEmpty()) return                                        // nothing bound under LB: the defaults stand
        byUnit = m; fromGame = true
    }

    fun keyOf(unit: String): String? = byUnit[unit]
    fun unitOf(key: String): String? = byUnit.entries.firstOrNull { it.value == key }?.key
}

/**
 * fix/menus-fit (the user on 1516: "The LB menu doesn't fit right with the map swapped and bigger"): WHERE THE LB PICTURE STANDS on
 * Home's page as laid. Pure (the JVM tests run it over every record). It stood at the page's middle under a fixed or stale top
 * (UiKit.pauseBarsBottom is the last pause's) and both sides moved 340: at a 1/3 or 2/3 share the wider column covered a side bar by
 * 166 px, and a grown fight band ran under its top. Now:
 *  - its top is the band's bottom as laid now + 10 (Classic's 236 at rest);
 *  - it is centred on the line between the columns ([leftColR] .. [rightColL]), kept inside the page;
 *  - it scales ([Place.k], at least [K_MIN]) to fit above [bottom] (the top of a bar that stays in place: Both), else the frame's foot;
 *  - each side moves only as far as it needs to clear it with [MARGIN] ([Place.pushLeft] / [Place.pushRight]): 340 both in Classic.
 * [classic] (the laid record is Classic's): at rest 1516's place exactly (its top 236 in the picture's own coordinates, the page's
 * middle, 340 each side); under a band grown for a fight (its foot + 10 past 236) the fitted place like every layout (the user's pick
 * 2026-11-21, "Fix in Classic too": 1516 drew the picture's top over the grown band).
 */
object LbFit {
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float)
    /** [cx] the picture's middle, [top] its first bar's top, [k] its scale (1 = 300 px bars), each side's move */
    data class Place(val cx: Float, val top: Float, val k: Float, val pushLeft: Float, val pushRight: Float, val classic: Boolean = false)
    const val MARGIN = 36f
    const val K_MIN = 0.7f
    /** half the picture's widest row (Left + Right: a bar and half the 16 px gap) at scale [k] */
    fun halfW(k: Float): Float = (LbPartyView.COL_W + 8f) * k

    fun place(w: Float, barsBottom: Float, leftColR: Float, rightColL: Float, bottom: Float, classic: Boolean, staleBars: Float): Place {
        val top: Float; val k: Float; val cx: Float
        val held = classic && barsBottom + 10f <= CLASSIC_TOP + 0.5f
        if (held) { k = 1f; cx = w / 2f; top = maxOf(CLASSIC_TOP, staleBars + 16f) }
        else {
            top = barsBottom + 10f
            k = ((bottom - top) / LbPartyView.PICT_H).coerceIn(K_MIN, 1f)
            val hw = halfW(k)
            cx = ((leftColR + rightColL) / 2f).coerceIn(hw + 16f, maxOf(hw + 16f, w - hw - 16f))
        }
        val hw = halfW(k)
        return Place(cx, top, k, maxOf(0f, leftColR - (cx - hw - MARGIN)), maxOf(0f, (cx + hw + MARGIN) - rightColL), held)
    }
    /** Classic's (1516's) top at rest: the diamonds' foot (226) + 10 */
    const val CLASSIC_TOP = 236f

    /** the five bars ("PAD1" = you, then the D-pad's four) at [p] */
    fun bars(p: Place): Map<String, Box> {
        val s = p.k; val cx = p.cx
        val bw = LbPartyView.COL_W * s; val bh = 100f * s; val g = 16f * s
        val upT = p.top + bh + 30f * s
        val midT = upT + bh + g
        val downT = midT + bh + g
        return linkedMapOf("PAD1" to Box(cx - bw / 2f, p.top, cx + bw / 2f, p.top + bh),
            "PADDUP" to Box(cx - bw / 2f, upT, cx + bw / 2f, upT + bh),
            "PADDLEFT" to Box(cx - g / 2f - bw, midT, cx - g / 2f, midT + bh),
            "PADDRIGHT" to Box(cx + g / 2f, midT, cx + g / 2f + bw, midT + bh),
            "PADDDOWN" to Box(cx - bw / 2f, downT, cx + bw / 2f, downT + bh))
    }
}
