package com.jrees.wowcompanion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.jrees.wowcompanion.PartyLayout.Box
import kotlin.math.abs

/**
 * PARTY MODE'S HOME MODULE (PARTY_BUILD.md 4.2; SPEC_DRAFT v10 / v17 / v18): the game's raid-style cells, you first. NEVER hidden while
 * the add-on sends `party` (on, off, solo); an older add-on sends nothing and Home has no module.
 *
 * Laid out by Home ([HomeView.places]) over the left column: the view's box is the WELL plus [PartyLayout.TAG_BELOW]..TAG_H above it,
 * where the (-) tag stands on the well's top edge while Party mode is off (or suspended). Every rect comes from [PartyLayout]; this view
 * only paints and takes touches: a TAP on a cell = Target / the touch spell, a LONG PRESS = that member's player menu, a tap on the tag
 * = Party mode on. A touch NEVER moves the box (the user's ruling); the box is the add-on's, moved by the D-pad.
 */
@SuppressLint("ViewConstructor")
class PartyModuleView(context: Context, private val live: PartyModel.State) : View(context) {
    /** Home sets these with every placement */
    var mode = PartyLayout.Mode.REST
    /** a tap on member [i]'s cell (unit index 0..4) */
    var onCellTap: ((Int) -> Unit)? = null
    /** a long press on member [i]'s cell */
    var onCellLong: ((Int) -> Unit)? = null
    /** the (-) tag's tap */
    var onTag: (() -> Unit)? = null

    /** the module in this view's own px (the well's top [RISE] under the view's top) */
    fun module(): PartyLayout.Module {
        val p = live.party
        val n = p?.n ?: 1
        return PartyLayout.module(mode, n, top = RISE, tagShown = p?.onLive != true, left = 0f, right = width.toFloat())
    }

    // ------------------------------------------------------------------------------------------------ paint

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()

    private fun RectF.of(b: Box): RectF { set(b.l, b.t, b.r, b.b); return this }

    override fun onDraw(c: Canvas) {
        val p = live.party ?: return
        val m = module()
        UiKit.module(c, RectF().of(m.well))
        val boxed = if (p.onLive) live.box.coerceIn(1, p.n) - 1 else -1
        for ((k, cell) in m.cells.withIndex()) {
            val mem = p.m.getOrNull(k) ?: continue
            val big = k == 0 && p.n >= 3 && mode != PartyLayout.Mode.FIGHT_OFF
            // offline, or out of range (2026-11-04, the user: "dimmed out like it is in game"; the game's own 55 % look)
            val save = if (mem.off) c.saveLayerAlpha(cell.l, cell.t, cell.r, cell.b, 128)
                else if (mem.rng == false && mem.u != "player") c.saveLayerAlpha(cell.l, cell.t, cell.r, cell.b, 140) else -1
            drawBars(c, cell, mem)
            if (k == boxed) UiKit.focus(c, RectF().of(cell))            // bars, then the box, then the words
            drawWords(c, cell, mem, big)
            if (save >= 0) c.restoreToCount(save)
        }
        m.tag?.let { drawTag(c, it) }
    }

    private fun drawBars(c: Canvas, cell: Box, mem: PartyModel.Member) {
        pFill.shader = null; pFill.colorFilter = null; pFill.style = Paint.Style.FILL
        pFill.color = Color.rgb(8, 8, 8)
        c.drawRect(r.of(cell), pFill)
        val bar = PartyLayout.hpBar(cell)
        val pw = PartyLayout.power(cell)
        if (mem.dead) {
            pFill.color = DEAD_GROUND
            c.drawRect(r.of(bar), pFill)
            c.drawRect(r.of(pw), pFill)
            PartyArt.skull(c, PartyLayout.skull(cell))
            return
        }
        pFill.color = Color.rgb(26, 26, 26)
        c.drawRect(r.of(bar), pFill)
        val fill = PartyLayout.hpFill(cell, mem.hpNow(), mem.fullNow())
        fill.box?.let { PartyArt.hpFill(c, it, classColour(mem), fill.fadeFrom) }
        // 2026-11-07 (the user: "The mana bars always show 100%"): filled to the combat log's value; the rest drawn as the empty bar
        val share = mem.powerNow()
        val pwFill = Box(pw.l, pw.t, pw.l + (pw.r - pw.l) * share, pw.b)
        pFill.color = Color.rgb(26, 26, 26)
        c.drawRect(r.of(pw), pFill)
        if (share > 0f) PartyArt.powerFill(c, pwFill, Color.rgb(0, 0, 0) or PartyLayout.powerRgb(mem.pt))
        if (PartyLayout.fullWidth(cell, 0f, width.toFloat())) PartyArt.role(c, PartyLayout.role(cell), mem.role)
    }

    private fun classColour(m: PartyModel.Member): Int {
        m.col?.let { s -> runCatching { return (s.toLong(16) or 0xFF000000L).toInt() } }
        return Theme.classColourOf(m.c)
    }

    private val nameP by lazy { UiKit.bodyPaint(30f, Color.WHITE) }
    private val pctP by lazy { android.text.TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = UiKit.number } }

    private fun measure(s: String, size: Float): Float { nameP.textSize = size; return nameP.measureText(s) }

    private fun drawWords(c: Canvas, cell: Box, mem: PartyModel.Member, big: Boolean) {
        val pct = PartyLayout.pctText(mem.hpNow(), mem.off)
        val w = PartyLayout.words(cell, mem.n, pct, mem.dead, big, ::measure, 0f, width.toFloat())
        val nameColour = if (mem.dead) Color.rgb(200, 200, 200) else Color.WHITE
        for ((i, line) in w.lines.withIndex()) edged(c, line, w.x, w.ys[i], w.size, nameColour, nameP, Paint.Align.LEFT)
        if (w.dead) {
            edged(c, "Dead", cell.cx, w.deadY, w.deadSize, Color.rgb(190, 190, 190), nameP, Paint.Align.CENTER)
        } else w.pct?.let { edged(c, it, w.pctRight, w.pctY, w.pctSize, Color.WHITE, pctP, Paint.Align.RIGHT) }
    }

    /** the game's shadow plus a 1 px dark edge (white must read on the priest's white bar), centred on [cy] */
    private fun edged(c: Canvas, s: String, x: Float, cy: Float, size: Float, colour: Int, p: Paint, align: Paint.Align) {
        p.textSize = size; p.textAlign = align
        val y = cy - (p.ascent() + p.descent()) / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.strokeJoin = Paint.Join.ROUND; p.color = Color.rgb(8, 6, 4)
        p.setShadowLayer(1f, size / 18f, size / 18f, Color.BLACK)
        c.drawText(Tr.t(s), x, y, p)
        p.clearShadowLayer()
        p.style = Paint.Style.FILL; p.color = colour
        c.drawText(Tr.t(s), x, y, p)
    }

    /** the side tab (the game's raid-manager tab: a dark panel, a gold edge) with the Select glyph; tapped = Party mode on */
    private fun drawTag(c: Canvas, t: Box) {
        pFill.shader = null; pFill.style = Paint.Style.FILL; pFill.color = Color.rgb(20, 17, 13)
        c.drawRoundRect(r.of(t), 6f, 6f, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2f; pFill.color = Color.rgb(140, 110, 52)
        c.drawRoundRect(RectF(t.l + 1f, t.t + 1f, t.r - 1f, t.b - 1f), 6f, 6f, pFill)
        pFill.style = Paint.Style.FILL
        val tp = PartyLayout.tagParts(t)
        UiKit.pad(c, "rev_minus", tp.glyphCx, tp.glyphCy, tp.glyph)
    }

    // ------------------------------------------------------------------------------------------------ touch

    private var downCell = -1
    private var downTag = false
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private val longPress = Runnable {
        val k = downCell
        if (k >= 0) { longFired = true; unitOf(k)?.let { onCellLong?.invoke(it) } }
    }

    private fun unitOf(k: Int): Int? = live.party?.m?.getOrNull(k)?.i

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (live.party == null) return false
                val m = module()
                downTag = PartyLayout.hitTag(m, e.x, e.y)
                downCell = if (downTag) -1 else PartyLayout.hit(m, e.x, e.y)
                if (!downTag && downCell < 0) return false                 // the rest of the box: the page under it
                downX = e.x; downY = e.y; longFired = false
                if (downCell >= 0) postDelayed(longPress, LONG_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) { removeCallbacks(longPress); downCell = -1; downTag = false }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val m = module()
                if (downTag && PartyLayout.hitTag(m, e.x, e.y)) onTag?.invoke()
                else if (downCell >= 0 && !longFired && PartyLayout.hit(m, e.x, e.y) == downCell) unitOf(downCell)?.let { onCellTap?.invoke(it) }
                downCell = -1; downTag = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); downCell = -1; downTag = false }
        }
        return true
    }

    /** the tag is under ([x], [y]) in this view's px (Home keeps its bars' touch off it) */
    fun tagAt(x: Float, y: Float): Boolean = live.party != null && PartyLayout.hitTag(module(), x, y)

    companion object {
        /** the view's top stands this far above the well's top edge: the tag's room (TAG_H - TAG_BELOW) */
        const val RISE = PartyLayout.TAG_H - PartyLayout.TAG_BELOW
        /** a long press: the class wheel's CLASS_LONG_MS */
        const val LONG_MS = 450L
        /** a tap moves no more than this */
        const val SLOP = 24f
        val DEAD_GROUND = Color.rgb(48, 46, 44)
    }
}

/**
 * THE PARTY ART NOT YET IN THE KIT, in ONE place so UI Art can swap it (PARTY_BUILD.md 4.2 "MISSING KIT ART"): the raid frame's bar
 * fills (`m_raidframe_hp_fill`, `m_raidframe_resource_fill`), the raid-target skull (`t_ui_raidtargetingicon_8`) and the pointing
 * gauntlet (`t_cursor_point`). Each draws the kit's picture when `UiKit.tex(name)` has it, else a flat fill / a drawn glyph. Never a
 * crash.
 */
object PartyArt {
    const val HP_FILL = "m_raidframe_hp_fill"
    const val RESOURCE_FILL = "m_raidframe_resource_fill"
    const val SKULL = "t_ui_raidtargetingicon_8"
    const val GAUNTLET = "t_cursor_point"

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    private fun tinted(c: Canvas, bmp: Bitmap, b: Box, colour: Int) {
        p.shader = null
        p.colorFilter = PorterDuffColorFilter(colour, PorterDuff.Mode.MULTIPLY)
        c.drawBitmap(bmp, null, RectF(b.l, b.t, b.r, b.b), p)
        p.colorFilter = null
    }

    /** the health fill in [colour]; its soft edge from [fadeFrom] (the estimate's look: the last 40 px fade to the bar's ground) */
    fun hpFill(c: Canvas, b: Box, colour: Int, fadeFrom: Float?) {
        val bmp = UiKit.tex(HP_FILL)
        if (bmp != null) tinted(c, bmp, b, colour)
        else { p.shader = null; p.colorFilter = null; p.style = Paint.Style.FILL; p.color = colour; c.drawRect(b.l, b.t, b.r, b.b, p) }
        if (fadeFrom != null && fadeFrom < b.r) {
            val ground = Color.rgb(26, 26, 26)
            p.colorFilter = null
            p.shader = LinearGradient(fadeFrom, 0f, b.r, 0f, Color.argb(0, 26, 26, 26), ground, Shader.TileMode.CLAMP)
            c.drawRect(fadeFrom, b.t, b.r, b.b, p)
            p.shader = null
        }
    }

    /** the 6 px power line in the type's colour (full: no amount is ever sent) */
    fun powerFill(c: Canvas, b: Box, colour: Int) {
        val bmp = UiKit.tex(RESOURCE_FILL)
        if (bmp != null) { tinted(c, bmp, b, colour); return }
        p.shader = null; p.colorFilter = null; p.style = Paint.Style.FILL; p.color = colour
        c.drawRect(b.l, b.t, b.r, b.b, p)
    }

    /** the role icon (kit role_small_tank / _heal / _dps); NONE = nothing */
    fun role(c: Canvas, b: Box, role: String) {
        val name = when (role) { "TANK" -> "role_small_tank"; "HEALER" -> "role_small_heal"; "DAMAGER" -> "role_small_dps"; else -> return }
        val bmp = UiKit.tex(name) ?: return
        p.colorFilter = null; p.shader = null
        c.drawBitmap(bmp, null, RectF(b.l, b.t, b.r, b.b), p)
    }

    /** the dead member's skull: the kit's raid-target skull, else a drawn one (a pale round head, two dark eyes, teeth) */
    fun skull(c: Canvas, b: Box) {
        UiKit.tex(SKULL)?.let { bmp -> p.colorFilter = null; p.shader = null; c.drawBitmap(bmp, null, RectF(b.l, b.t, b.r, b.b), p); return }
        val s = b.w
        p.shader = null; p.colorFilter = null; p.style = Paint.Style.FILL
        p.color = Color.rgb(226, 222, 210)
        c.drawCircle(b.cx, b.t + s * 0.42f, s * 0.40f, p)
        c.drawRoundRect(b.cx - s * 0.22f, b.t + s * 0.58f, b.cx + s * 0.22f, b.b - s * 0.04f, s * 0.06f, s * 0.06f, p)
        p.color = Color.rgb(20, 18, 16)
        c.drawCircle(b.cx - s * 0.15f, b.t + s * 0.44f, s * 0.11f, p)
        c.drawCircle(b.cx + s * 0.15f, b.t + s * 0.44f, s * 0.11f, p)
        path.reset(); path.moveTo(b.cx, b.t + s * 0.56f); path.lineTo(b.cx - s * 0.05f, b.t + s * 0.66f); path.lineTo(b.cx + s * 0.05f, b.t + s * 0.66f)
        path.close(); c.drawPath(path, p)
        p.strokeWidth = s * 0.03f; p.style = Paint.Style.STROKE
        for (k in -1..1) c.drawLine(b.cx + k * s * 0.08f, b.t + s * 0.74f, b.cx + k * s * 0.08f, b.b - s * 0.06f, p)
        p.style = Paint.Style.FILL
    }

    /** Touch = Target's picture: the game's "target a friend" green ring (the friendly reticle), drawn (no kit piece of its own) */
    fun targetRing(c: Canvas, cx: Float, cy: Float, r: Float) {
        p.shader = null; p.colorFilter = null; p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.28f; p.color = Color.rgb(20, 40, 20)
        c.drawCircle(cx, cy, r, p)
        p.strokeWidth = r * 0.18f; p.color = Color.rgb(96, 214, 90)
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
        for (k in 0 until 4) {
            c.save(); c.rotate(90f * k, cx, cy)
            c.drawRect(cx - r * 0.09f, cy - r * 1.25f, cx + r * 0.09f, cy - r * 0.78f, p)
            c.restore()
        }
    }

    /** the game's pointing gauntlet (the Touch row's glyph), else a drawn pointing hand */
    fun gauntlet(c: Canvas, cx: Float, cy: Float, size: Float) {
        UiKit.tex(GAUNTLET)?.let { bmp ->
            p.colorFilter = null; p.shader = null
            c.drawBitmap(bmp, null, RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f), p); return
        }
        val s = size
        p.shader = null; p.colorFilter = null; p.style = Paint.Style.FILL; p.color = Color.rgb(196, 200, 206)
        // the finger, up and to the left (the game's cursor points that way), and the fist
        c.save(); c.rotate(-35f, cx, cy)
        c.drawRoundRect(cx - s * 0.08f, cy - s * 0.48f, cx + s * 0.08f, cy, s * 0.08f, s * 0.08f, p)
        c.drawRoundRect(cx - s * 0.22f, cy - s * 0.10f, cx + s * 0.22f, cy + s * 0.34f, s * 0.10f, s * 0.10f, p)
        p.color = Color.rgb(90, 92, 98); p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.03f
        c.drawRoundRect(cx - s * 0.22f, cy - s * 0.10f, cx + s * 0.22f, cy + s * 0.34f, s * 0.10f, s * 0.10f, p)
        p.style = Paint.Style.FILL
        c.restore()
    }
}
