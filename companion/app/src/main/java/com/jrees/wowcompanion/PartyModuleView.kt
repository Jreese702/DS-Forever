package com.jrees.wowcompanion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.jrees.wowcompanion.PartyLayout.Box
import kotlin.math.abs

/**
 * PARTY MODE'S HOME MODULE (party_design.md C and the user's decisions 2026-11-11): ONE COLUMN of plates, you first - the top
 * screen's order - beside the Select tab, which always stands (off: the way in; on: lit gold, the B under it, the gold notch at the
 * pick; not bound: dim with a lock). NO health or mana here (the user: "give up on displaying HP/Mana down there").
 *
 * A plate: the class stripe, the class / role icon, the name in class colour with the level after it, a skull (dead) or a plug
 * (offline), the member's LB glyph at the right while Party mode is on; offline / out of range at 0.45 (the shared rng). The PICKED
 * plate wears the gold UiKit.focus box and the A / X / Y chips of the layer on show (ZL / ZR held swap them) - not tap targets.
 * TOUCH: a tap = the member's pick cell (the add-on decides: off = Target; another member = the pick moves; the picked one = the Touch
 * action); a long press = the player menu; a tap on the tab = on (off / not bound) or off (on).
 */
@SuppressLint("ViewConstructor")
class PartyModuleView(context: Context, private val live: PartyModel.State) : View(context) {
    /** Home sets these with every placement */
    var mode = PartyLayout.Mode.REST
    /** a tap on member [i]'s plate (unit index 0..4) */
    var onCellTap: ((Int) -> Unit)? = null
    /** a long press on member [i]'s plate */
    var onCellLong: ((Int) -> Unit)? = null
    /** the Select tab's tap: true = turn Party mode on, false = off */
    var onTag: ((Boolean) -> Unit)? = null
    /** the triggers held now (the strip's reader): ZL / ZR swap the picked plate's chips */
    var triggers: () -> Pair<Boolean, Boolean> = { false to false }

    /** the tab's look and motion */
    val tab = SelectTab()

    /** the module in this view's own px */
    fun module(): PartyLayout.Module = PartyLayout.module(mode, live.party?.n ?: 1, top = 0f, left = 0f, right = width.toFloat())

    /** a Select press the app's reader saw: the pressed look for 120 ms */
    fun selectPressed() { tab.press(SystemClock.uptimeMillis()); invalidate() }
    /** Home's rest page opened: grouped, the tab's edge pulses (never in a fight) */
    fun homeOpened() { live.party?.let { tab.homeOpened(it.grp, it.fight, SystemClock.uptimeMillis()) }; invalidate() }

    // ------------------------------------------------------------------------------------------------ paint

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val path = Path()

    private fun RectF.of(b: Box): RectF { set(b.l, b.t, b.r, b.b); return this }

    override fun onDraw(c: Canvas) {
        val p = live.party ?: return
        val now = SystemClock.uptimeMillis()
        val m = module()
        UiKit.module(c, RectF().of(m.well))
        val on = p.onLive
        val boxed = if (on) live.box.coerceIn(1, p.n) - 1 else -1
        tab.setLook(SelectTab.lookOf(p), now)
        tab.setNotch(if (on) PartyLayout.notchY(m, boxed + 1) else null, now)
        tab.groupSeen(p.grp, p.fight, now)
        val cfg = live.cfg
        val resOn = cfg?.slots?.values?.any { it != null && it.res && it.k } == true
        for ((k, cell) in m.cells.withIndex()) {
            val mem = p.m.getOrNull(k) ?: continue
            val save = if (mem.dim) c.saveLayerAlpha(cell.l, cell.t, cell.r, cell.b, (255 * PartyModel.DIM).toInt()) else -1
            drawPlate(c, cell, mem, on, k == boxed, cfg, p, boxed + 1, resOn)
            if (save >= 0) c.restoreToCount(save)
        }
        drawTab(c, m.tag, now)
        if (tab.moving(now)) postInvalidateOnAnimation()
    }

    private fun classColour(m: PartyModel.Member): Int {
        m.col?.let { s -> runCatching { return (s.toLong(16) or 0xFF000000L).toInt() } }
        return Theme.classColourOf(m.c)
    }

    private val nameP by lazy { UiKit.bodyPaint(24f, Color.WHITE) }
    private val lvP by lazy { UiKit.bodyPaint(18f, Theme.dim) }

    private fun drawPlate(c: Canvas, p: Box, mem: PartyModel.Member, on: Boolean, picked: Boolean, cfg: PartyModel.Cfg?,
                          party: PartyModel.Party, box: Int, resOn: Boolean) {
        pFill.shader = null; pFill.colorFilter = null; pFill.style = Paint.Style.FILL
        pFill.color = Color.argb(230, 14, 12, 10)
        c.drawRoundRect(r.of(p), 6f, 6f, pFill)
        val col = classColour(mem)
        pFill.color = if (mem.dead) Color.rgb(110, 110, 110) else col
        c.drawRect(r.of(PartyLayout.stripe(p)), pFill)
        if (picked) UiKit.focus(c, RectF().of(p))                      // the golden BOX (the user's ruling), under the words
        // a dead member and a res spell set: a soft green edge (the res is there to cast)
        if (mem.dead && resOn) {
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 3f; pFill.color = Color.argb(170, 96, 214, 90)
            c.drawRoundRect(RectF(p.l + 1.5f, p.t + 1.5f, p.r - 1.5f, p.b - 1.5f), 6f, 6f, pFill)
            pFill.style = Paint.Style.FILL
        }
        val ib = PartyLayout.icon(p, mode)
        val roleArt = when (mem.role) { "TANK" -> "role_small_tank"; "HEALER" -> "role_small_heal"; "DAMAGER" -> "role_small_dps"; else -> null }
        val icon = roleArt?.let { UiKit.tex(it) } ?: mem.c.takeIf { it.isNotEmpty() }?.let { UiKit.classIcon(it) }
        if (icon != null) bmp(c, icon, ib, if (mem.dead) 140 else 255)
        else { pFill.color = col; c.drawCircle(ib.cx, ib.cy, ib.w * 0.34f, pFill) }
        // the chips first (their count fixes the name's room): the picked plate only, the layer on show
        val chips = if (picked) chipPics(cfg, party, box) else emptyList()
        val glyphKey = if (on) LbMap.keyOf(mem.u) else null
        val nx = PartyLayout.nameX(p, mode)
        val room = PartyLayout.nameRoom(p, mode, chips.size, glyphKey != null)
        nameP.textSize = PartyLayout.nameSize(mode)
        nameP.color = if (mem.dead) Color.rgb(150, 150, 150) else col
        val lv = mem.lv?.let { " $it" } ?: ""
        lvP.textSize = PartyLayout.nameSize(mode) * 0.72f
        val lvW = if (lv.isEmpty()) 0f else lvP.measureText(lv)
        val name = android.text.TextUtils.ellipsize(Tr.t(mem.n), android.text.TextPaint(nameP), (room - lvW - 40f).coerceAtLeast(40f),
            android.text.TextUtils.TruncateAt.END).toString()
        val baseY = p.cy - (nameP.ascent() + nameP.descent()) / 2f
        c.drawText(name, nx, baseY, nameP)
        var x = nx + nameP.measureText(name)
        if (lv.isNotEmpty()) { c.drawText(lv, x, baseY, lvP); x += lvW }
        val st = PartyLayout.state(p, x)
        if (mem.dead) PartyArt.skull(c, st) else if (mem.off) PartyArt.plug(c, st)
        glyphKey?.let { UiKit.pad(c, LbPartyView.glyphOf(it), PartyLayout.glyph(p).cx, p.cy, 36f) }
        for ((j, b) in PartyLayout.chips(p, chips.size).withIndex()) {
            val (face, pic) = chips[j]
            val art = if (pic.kind == PartyStrip.Kind.INSPECT) UiKit.tex("fixed_inspect") else pic.spell?.let { Icons.get(it.i) }
            UiKit.spell(c, art, b.l, b.t, PartyLayout.CHIP, if (pic.lit) UiKit.Spell.KNOWN else UiKit.Spell.LEVEL, onDark = true)
            val bg = PartyLayout.badge(b)
            UiKit.pad(c, Glyphs.translate("rev_$face", CastSettings.buttonLayout), bg.cx, bg.cy, PartyLayout.BADGE)
        }
    }

    /** the picked plate's chips: A, X, Y of the layer the triggers show (ZL / ZR swap them; both held: none); Empty not drawn */
    private fun chipPics(cfg: PartyModel.Cfg?, party: PartyModel.Party, box: Int): List<Pair<String, PartyStrip.Pic>> {
        val (lt, rt) = triggers()
        val layer = PartyStrip.layer(lt, rt)
        if (layer == PartyStrip.Layer.BOTH) return emptyList()
        return listOf("a", "x", "y").map { it to PartyStrip.face(it, layer, cfg, party, box, party.fight) }
            .filter { it.second.kind != PartyStrip.Kind.EMPTY }
    }

    private fun bmp(c: Canvas, b: Bitmap, at: Box, alpha: Int) {
        pBmp.alpha = alpha; pBmp.colorFilter = null
        c.drawBitmap(b, null, RectF().of(at), pBmp)
        pBmp.alpha = 255
    }

    /**
     * THE SELECT TAB (party_design.md E): the same rectangle in every state, no words. Off: the dark panel, a 2 px gold edge, the
     * Select glyph and the gold arrow pointing into the column. On: SOFT_GOLD at 35 % (wiped in), the full-gold edge, the glyph bright
     * with the checked glow, the arrow turned to point out, a small B, the gold notch at the pick. Not bound: the glyph at 40 %, a lock.
     */
    private fun drawTab(c: Canvas, t: Box, now: Long) {
        val look = tab.look
        val w = tab.wipe(now)
        pFill.shader = null; pFill.colorFilter = null; pFill.style = Paint.Style.FILL
        pFill.color = Color.rgb(20, 17, 13)
        c.drawRoundRect(r.of(t), 6f, 6f, pFill)
        if (w > 0f) {
            // the gold wipe: from the top down while it turns on (and back up while it turns off)
            pFill.color = Color.argb((255 * 0.35f).toInt(), Color.red(UiKit.SOFT_GOLD), Color.green(UiKit.SOFT_GOLD), Color.blue(UiKit.SOFT_GOLD))
            c.save(); c.clipRect(t.l, t.t, t.r, t.t + t.h * w)
            c.drawRoundRect(r.of(t), 6f, 6f, pFill)
            c.restore()
        }
        val edge = when {
            tab.pressed(now) -> Color.rgb(196, 156, 72)
            look == SelectTab.Look.ON -> Color.rgb(255, 209, 0)
            else -> Color.rgb(140, 110, 52)
        }
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2f
        pFill.color = Color.argb((255 * tab.edgeAlpha(now)).toInt(), Color.red(edge), Color.green(edge), Color.blue(edge))
        c.drawRoundRect(RectF(t.l + 1f, t.t + 1f, t.r - 1f, t.b - 1f), 6f, 6f, pFill)
        pFill.style = Paint.Style.FILL
        val tp = PartyLayout.tabParts(t)
        val gs = tp.glyph * tab.glyphScale(now)
        val ga = if (look == SelectTab.Look.UNBOUND) (255 * 0.4f).toInt() else 255
        if (look == SelectTab.Look.ON) UiKit.checkedGlow(c, RectF(tp.glyphCx - gs / 2f, tp.glyphCy - gs / 2f, tp.glyphCx + gs / 2f, tp.glyphCy + gs / 2f))
        UiKit.pad(c, Glyphs.translate("rev_minus", CastSettings.buttonLayout), tp.glyphCx, tp.glyphCy, gs, ga)
        // the arrow: into the column (right) when off, turned 180 deg (out, left) when on
        UiKit.tex("move_arrow_right")?.let { a ->
            c.save(); c.rotate(tab.arrowDeg(now), tp.glyphCx, tp.arrowCy)
            bmp(c, a, Box(tp.glyphCx - tp.arrow / 2f, tp.arrowCy - tp.arrow / 2f, tp.glyphCx + tp.arrow / 2f, tp.arrowCy + tp.arrow / 2f), ga)
            c.restore()
        }
        if (look == SelectTab.Look.ON) tp.bCy?.let { UiKit.pad(c, Glyphs.translate("rev_b", CastSettings.buttonLayout), tp.glyphCx, it, tp.b) }
        if (look == SelectTab.Look.UNBOUND) UiKit.tex("talent_lock")?.let { l ->
            bmp(c, l, Box(tp.glyphCx - tp.lock / 2f, tp.lockCy - tp.lock / 2f, tp.glyphCx + tp.lock / 2f, tp.lockCy + tp.lock / 2f), 255)
        }
        // the gold notch on the right edge at the pick (it slides with it)
        tab.notchY(now)?.let { ny ->
            pFill.color = Theme.gold
            path.reset(); path.moveTo(t.r, ny - tp.notchW); path.lineTo(t.r + tp.notchW, ny); path.lineTo(t.r, ny + tp.notchW); path.close()
            c.drawPath(path, pFill)
        }
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
                if (downTag && PartyLayout.hitTag(m, e.x, e.y)) onTag?.invoke(live.party?.onLive != true)
                else if (downCell >= 0 && !longFired && PartyLayout.hit(m, e.x, e.y) == downCell) unitOf(downCell)?.let { onCellTap?.invoke(it) }
                downCell = -1; downTag = false
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); downCell = -1; downTag = false }
        }
        return true
    }

    /** the tab is under ([x], [y]) in this view's px (Home keeps its bars' touch off it) */
    fun tagAt(x: Float, y: Float): Boolean = live.party != null && PartyLayout.hitTag(module(), x, y)

    companion object {
        /** the view's top is the well's (the tab stands inside it now) */
        const val RISE = 0f
        /** a long press: the class wheel's CLASS_LONG_MS */
        const val LONG_MS = 450L
        /** a tap moves no more than this */
        const val SLOP = 24f
    }
}

/**
 * THE PARTY ART NOT YET IN THE KIT, in ONE place so UI Art can swap it: the raid-target skull (`t_ui_raidtargetingicon_8`), the
 * offline plug, the pointing gauntlet (`t_cursor_point`) and Touch = Target's ring. Each draws the kit's picture when `UiKit.tex(name)`
 * has it, else a drawn one. Never a crash. (m129: the bar fills are gone with the bottom's health.)
 */
object PartyArt {
    const val SKULL = "t_ui_raidtargetingicon_8"
    const val GAUNTLET = "t_cursor_point"
    const val PLUG = "t_ui_disconnected"

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

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

    /** an offline member's plug: the kit's picture, else a drawn plug (two prongs and a body, grey) */
    fun plug(c: Canvas, b: Box) {
        UiKit.tex(PLUG)?.let { bmp -> p.colorFilter = null; p.shader = null; c.drawBitmap(bmp, null, RectF(b.l, b.t, b.r, b.b), p); return }
        val s = b.w
        p.shader = null; p.colorFilter = null; p.style = Paint.Style.FILL; p.color = Color.rgb(170, 170, 176)
        c.drawRect(b.cx - s * 0.18f, b.t + s * 0.08f, b.cx - s * 0.08f, b.t + s * 0.34f, p)
        c.drawRect(b.cx + s * 0.08f, b.t + s * 0.08f, b.cx + s * 0.18f, b.t + s * 0.34f, p)
        c.drawRoundRect(b.cx - s * 0.30f, b.t + s * 0.34f, b.cx + s * 0.30f, b.t + s * 0.70f, s * 0.08f, s * 0.08f, p)
        c.drawRect(b.cx - s * 0.06f, b.t + s * 0.70f, b.cx + s * 0.06f, b.b - s * 0.04f, p)
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
        c.save(); c.rotate(-35f, cx, cy)
        c.drawRoundRect(cx - s * 0.08f, cy - s * 0.48f, cx + s * 0.08f, cy, s * 0.08f, s * 0.08f, p)
        c.drawRoundRect(cx - s * 0.22f, cy - s * 0.10f, cx + s * 0.22f, cy + s * 0.34f, s * 0.10f, s * 0.10f, p)
        p.color = Color.rgb(90, 92, 98); p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.03f
        c.drawRoundRect(cx - s * 0.22f, cy - s * 0.10f, cx + s * 0.22f, cy + s * 0.34f, s * 0.10f, s * 0.10f, p)
        p.style = Paint.Style.FILL
        c.restore()
    }
}
