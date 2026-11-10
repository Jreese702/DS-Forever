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

    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText by lazy { UiKit.bodyPaint(30f, Color.WHITE) }
    private val pSmall by lazy { UiKit.bodyPaint(22f, Theme.dim) }

    override fun onDraw(c: Canvas) {
        val t = tNow()
        onSlide(t)
        if (!held && t <= 0f) { visibility = GONE; return }
        val k = (t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f)
        val a = (255 * k).toInt()
        val w = width.toFloat(); val cx = w / 2f
        val s = minOf(w / 1240f, height / 1080f)
        val party = PartyModel.live.party
        val members = party?.m.orEmpty()
        fun member(unit: String) = members.firstOrNull { it.u == unit }
        val now = SystemClock.uptimeMillis()
        val bw = COL_W * s; val bh = 100f * s; val g = 16f * s
        // THE TOP LINE: your soft target | what LB alone would target
        val top = RectF(cx - bw - g / 2f, 112f * s, cx + bw + g / 2f, 196f * s)
        topLine(c, top, a, s) { u -> member(u)?.n }
        // YOU, with the A glyph, then the four as the game binds them: Up, Left + Right side by side, Down
        val you = RectF(cx - bw / 2f, top.bottom + 22f * s, cx + bw / 2f, top.bottom + 22f * s + bh)
        bar(c, you, member("player"), "player", LbMap.keyOf("player") ?: "PAD1", a, now, s)
        val upT = you.bottom + 30f * s
        val midT = upT + bh + g
        val downT = midT + bh + g
        val spots = mapOf("PADDUP" to RectF(cx - bw / 2f, upT, cx + bw / 2f, upT + bh),
            "PADDLEFT" to RectF(cx - g / 2f - bw, midT, cx - g / 2f, midT + bh),
            "PADDRIGHT" to RectF(cx + g / 2f, midT, cx + g / 2f + bw, midT + bh),
            "PADDDOWN" to RectF(cx - bw / 2f, downT, cx + bw / 2f, downT + bh))
        for ((key, r) in spots) {
            val unit = LbMap.unitOf(key)
            bar(c, r, unit?.let { member(it) }, unit, key, a, now, s)
        }
        if (held && t < UiKit.PAUSE_OPEN_MS || !held || pressedAt.values.any { now - it < PRESS_MS }) postInvalidateOnAnimation()
    }

    /** the top line: the soft target (enemy, else friend, else interact; in its kind's colour) | the LB glyph and whom LB alone targets */
    private fun topLine(c: Canvas, r: RectF, a: Int, s: Float, nameOf: (String) -> String?) {
        UiKit.module(c, r, a)
        val half = r.centerX()
        pBar.style = Paint.Style.FILL; pBar.color = Theme.dim; pBar.alpha = a / 3
        c.drawRect(half - 1f, r.top + 14f * s, half + 1f, r.bottom - 14f * s, pBar)
        val x0 = r.left + 22f * s
        pSmall.textSize = 20f * s; pSmall.color = Theme.dim; pSmall.alpha = a
        c.drawText(Tr.t("Soft target"), x0, r.top + 30f * s, pSmall)
        val (soft, col) = SoftNow.e?.let { it to SOFT_ENEMY } ?: SoftNow.f?.let { it to SOFT_FRIEND } ?: SoftNow.i?.let { it to SOFT_INTERACT }
            ?: (null to Theme.dim)
        pText.textSize = 28f * s; pText.color = col; pText.alpha = if (soft == null) a / 2 else a
        c.drawText(fit(soft ?: Tr.t("None"), half - x0 - 16f * s), x0, r.top + 66f * s, pText)
        // LB alone
        val gx = half + 44f * s
        UiKit.pad(c, UiKit.shoulderGlyph(false), gx, r.centerY(), 52f * s, a)
        val who = releaseName(nameOf)
        val tx = gx + 40f * s
        pText.textSize = 28f * s; pText.color = if (who == null) Theme.dim else Theme.gold; pText.alpha = if (who == null) a / 2 else a
        c.drawText(fit(who ?: Tr.t("None"), r.right - 18f * s - tx), tx, r.centerY() + 10f * s, pText)
    }

    private fun fit(t: String, w: Float): String =
        android.text.TextUtils.ellipsize(t, android.text.TextPaint(pText), w, android.text.TextUtils.TruncateAt.END).toString()

    /** whom LB tapped alone would target: by the game's own binding for it ([LbMap.tap]), the soft target of that kind; else its name */
    private fun releaseName(nameOf: (String) -> String?): String? {
        val act = LbMap.tap ?: return null
        val up = act.uppercase()
        return when {
            up.contains("INTERACT") -> SoftNow.i
            up.contains("FRIEND") -> SoftNow.f
            up.contains("ENEMY") -> SoftNow.e
            up == "TARGETSELF" -> nameOf("player")
            up.startsWith("TARGETPARTYMEMBER") -> nameOf("party" + up.removePrefix("TARGETPARTYMEMBER"))
            else -> LbMap.tapName ?: act
        }
    }

    /** one member's bar: the button's glyph at its left, the name in the class colour, health and power; lit while its press shows */
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
            c.drawText(name, x0, r.top + 36f * s, pText)
            // health (green; grey when dead) and power (the power type's colour)
            val hb = RectF(x0, r.top + 48f * s, x1, r.top + 70f * s)
            val pb = RectF(x0, r.top + 76f * s, x1, r.top + 86f * s)
            fill(c, hb, if (m.dead) 0f else m.hpNow() / 100f, if (m.dead) Color.GRAY else HEALTH, a)
            fill(c, pb, m.powerNow(), powerColour(m.pt), a)
            if (m.dead) c.drawText(Tr.t("Dead"), x1 - 70f * s, r.top + 66f * s, pSmall.apply { alpha = a; textSize = 20f * s })
        }
        if (pressed) UiKit.focus(c, r)
    }

    private fun fill(c: Canvas, r: RectF, f: Float, colour: Int, a: Int) {
        pBar.style = Paint.Style.FILL
        pBar.color = Color.argb(a * 200 / 255, 10, 10, 12); c.drawRect(r, pBar)
        pBar.color = colour; pBar.alpha = a
        c.drawRect(RectF(r.left, r.top, r.left + r.width() * f.coerceIn(0f, 1f), r.bottom), pBar)
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
        private val SOFT_ENEMY = Color.rgb(230, 70, 60)
        private val SOFT_FRIEND = Color.rgb(80, 210, 90)
        private val SOFT_INTERACT = Color.rgb(240, 200, 90)
        private val HEALTH = Color.rgb(32, 180, 40)
        private val SLOT_KEY = listOf(PadInput.SLOT_FACE_BOTTOM to "PAD1", PadInput.SLOT_DPAD_UP to "PADDUP", PadInput.SLOT_DPAD_RIGHT to "PADDRIGHT",
            PadInput.SLOT_DPAD_DOWN to "PADDDOWN", PadInput.SLOT_DPAD_LEFT to "PADDLEFT", PadInput.SLOT_FACE_RIGHT to "PAD2",
            PadInput.SLOT_FACE_LEFT to "PAD3", PadInput.SLOT_FACE_TOP to "PAD4")

        fun glyphOf(key: String): String = when (key) {
            "PADDUP" -> "gen_up"; "PADDDOWN" -> "gen_down"; "PADDLEFT" -> "gen_left"; "PADDRIGHT" -> "gen_right"
            "PAD1" -> UiKit.okGlyph()
            else -> "gen_" + key.removePrefix("PAD").lowercase()
        }

        fun powerColour(pt: String): Int = when (pt.uppercase()) {
            "RAGE" -> Color.rgb(200, 30, 30); "ENERGY" -> Color.rgb(230, 210, 40); "FOCUS" -> Color.rgb(230, 130, 40)
            "RUNIC_POWER" -> Color.rgb(0, 200, 230); else -> Color.rgb(40, 90, 220)
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

/** PadMap m121's "soft": the names of the game's soft enemy / friend / interact units now (null = none, or not readable). Pure. */
object SoftNow {
    @Volatile var e: String? = null
        private set
    @Volatile var f: String? = null
        private set
    @Volatile var i: String? = null
        private set

    fun update(d: JSONObject) {
        e = d.optString("e", "").ifEmpty { null }
        f = d.optString("f", "").ifEmpty { null }
        i = d.optString("i", "").ifEmpty { null }
    }
}
