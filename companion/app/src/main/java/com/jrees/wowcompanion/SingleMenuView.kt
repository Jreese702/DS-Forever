package com.jrees.wowcompanion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.jrees.wowcompanion.SingleMenu.Box
import com.jrees.wowcompanion.SingleMenu.Move
import com.jrees.wowcompanion.SingleMenu.Phase

/**
 * THE DS MENU ON THE TOP SCREEN, DRAWN (SingleMenu; the 1536 pass 1, direction C "the row rises"). Two layers over the game in the top
 * window, round the rooms column (CompanionHost lays the column on the stage):
 *  - [back], UNDER the column: the stage's frame (its dark ground, its edge - red in a fight -, the room's name in its header, "In a fight"),
 *    growing out of the room's disc as the room unfolds and back into it as it folds; it keeps a finger on the stage off the dim;
 *  - [front], OVER the column: the row (Home's swirl - the bar's own live disc, red with B in a fight - and the rooms' round icons on the
 *    radial's plates along a shallow arc, the lit one larger with its name), the ZL / ZR neighbours and the hints, the red edges in a fight,
 *    and the GHOST: the room's last picture folding into its disc / sliding out as the next room slides in.
 * One motion at a time ([play]), each between two real places (SingleMenu.Move); the column follows through [onPage].
 */
class SingleMenuView(ctx: Context) {
    val back: View = Back(ctx)
    val front: View = Front(ctx)

    /** the screen's size (the top window's) */
    var w = SingleMenu.BASE_W
    var h = SingleMenu.BASE_H
    /** the row's order (SingleMenu.order of the bar's shown rooms) */
    var order: List<Room> = SingleMenu.order(RoomBar.rooms(RoomBar.DEFAULT))
    /** the row's cursor while the row alone shows (the bar cursor's) */
    var cursorOf: () -> Room? = { null }
    var fightNow: () -> Boolean = { false }
    /** ZL / ZR step from the room shown (the neighbours' names show by the triggers) */
    var triggersOn: () -> Boolean = { false }
    /** a room's round picture (the bar's: Strip.drawRoomPicture) */
    var drawRoom: ((Canvas, Room, Float, Float, Float) -> Unit)? = null
    /** Home's swirl (the bar's live disc: Strip.drawDiscAt); false = not drawn (a stand-in is) */
    var drawSwirl: ((Canvas, Float, Float, Float) -> Boolean)? = null
    /** a finger on a disc of the row */
    var onDisc: ((Room) -> Unit)? = null
    /** the room's page now: where it is drawn ([Box], null = no page) and its alpha, each frame of a motion and at its end */
    var onPage: ((Box?, Float) -> Unit)? = null
    var clock: () -> Long = { SystemClock.uptimeMillis() }
    /** the red edges' pulse clock (the shots hold it at the pulse's height) */
    var pulse: () -> Long = { SystemClock.uptimeMillis() }

    /** the state drawn */
    var phase = Phase.CLOSED; private set
    var room: Room? = null; private set
    /** the last motion played, its time */
    var move = Move.NONE; private set
    private var dir = 1
    private var dock: Box? = null

    // ---- the motion: [k] 0..1 eased, [tMs] its own time
    private var anim: ValueAnimator? = null
    private var k = 1f
    private var tMs = 0L
    private var msNow = 0L
    private val ease = Motion.curve()

    // the ghost: a room's last picture ([ghostSrc] its own px) drawn from [ghostFrom] to [ghostTo], alpha [ghostA0] -> [ghostA1]
    private var ghost: Picture? = null
    private var ghostSrc: Box? = null
    private var ghostFrom: Box? = null
    private var ghostTo: Box? = null
    private var ghostA0 = 1f
    private var ghostA1 = 0f
    // the stage's frame and the page: from -> to over the motion
    private var stageFrom: Box? = null
    private var stageTo: Box? = null
    private var pageFrom: Box? = null
    private var pageTo: Box? = null
    private var pageA0 = 1f
    // the row: up (1) / down (0) over the motion
    private var rowFrom = 0f
    private var rowTo = 0f

    // the lit disc's glide
    private var litNow: Room? = null
    private var litFrom: Pair<Float, Float>? = null
    private var litAt = 0L

    fun stageBox(): Box = SingleMenu.stage(w, h)
    fun pageBox(r: Room): Box = SingleMenu.place(r, w, h).drawn
    fun discs(lit: Room? = litRoom()): List<SingleMenu.Disc> = SingleMenu.discs(order, lit, w, h)
    private fun disc(r: Room?): SingleMenu.Disc? = r?.let { x -> discs().firstOrNull { it.room == x } }

    /** the lit disc: the cursor while the row alone shows, the room on the stage */
    fun litRoom(): Room? = when (phase) { Phase.ROW -> cursorOf(); Phase.ROOM -> room; Phase.CLOSED -> null }

    /**
     * the state now: [to] / [r] (the room on the stage, Home for Home's stage), the motion there [m] (SingleMenu.Machine), [pic] the room
     * just left as it looked ([picSrc] its own px), [dockBox] the docked row's place (where the row rises from / drops to; null = under
     * the screen's edge)
     */
    fun play(m: Move, to: Phase, r: Room?, pic: Picture? = null, picSrc: Box? = null, dockBox: Box? = null) {
        val fromRoom = room
        val fromPage = room?.let { pageBox(it) }
        dir = SingleMenu.slideDir(order, fromRoom, r)
        anim?.cancel(); anim = null
        phase = to; room = r; move = m; dock = dockBox
        ghost = null; ghostSrc = null; ghostFrom = null; ghostTo = null
        stageFrom = null; stageTo = null; pageFrom = null; pageTo = null; pageA0 = 1f
        val stage = stageBox()
        val page = r?.let { pageBox(it) }
        val rowUp = to != Phase.CLOSED
        rowFrom = if (rowUp) 1f else 0f; rowTo = rowFrom
        when (m) {
            Move.RISE -> { rowFrom = 0f; rowTo = 1f }
            Move.DROP -> { rowFrom = 1f; rowTo = 0f }
            Move.UNFOLD, Move.RISE_UNFOLD -> {
                if (m == Move.RISE_UNFOLD) { rowFrom = 0f; rowTo = 1f }
                val d = disc(r) ?: disc(Room.HOME)
                if (page != null && d != null) {
                    stageFrom = SingleMenu.folded(d, stage); stageTo = stage
                    pageFrom = SingleMenu.folded(d, page); pageTo = page; pageA0 = 0f
                }
            }
            Move.FOLD, Move.FOLD_DROP -> {
                if (m == Move.FOLD_DROP) { rowFrom = 1f; rowTo = 0f }
                val d = disc(fromRoom)
                val src = fromPage ?: stage
                val end = if (d != null) SingleMenu.folded(d, src)
                    else Box(src.cx - src.w * 0.3f, src.cy - src.h * 0.3f, src.cx + src.w * 0.3f, src.cy + src.h * 0.3f)
                stageFrom = stage; stageTo = if (d != null) SingleMenu.folded(d, stage) else end
                ghost(pic, picSrc, src, end, 1f, 0f)
            }
            Move.SLIDE -> {
                val dx = stage.w * SingleMenu.SLIDE_SHARE * dir
                stageFrom = stage; stageTo = stage
                if (page != null) { pageFrom = shift(page, dx); pageTo = page; pageA0 = 0f }
                if (fromPage != null) ghost(pic, picSrc, fromPage, shift(fromPage, -dx), 1f, 0f)
            }
            Move.NONE -> { if (to == Phase.ROOM) { stageFrom = stage; stageTo = stage } }
        }
        if (to == Phase.ROOM && stageTo == null) { stageFrom = stage; stageTo = stage }
        if (to == Phase.ROOM && page != null && pageTo == null) { pageFrom = page; pageTo = page }
        val ms = SingleMenu.ms(m)
        msNow = ms
        if (ms <= 0L) { settle(); return }
        k = 0f; tMs = 0L
        frame()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = LinearInterpolator()
            addUpdateListener { a -> tMs = (a.animatedFraction * ms).toLong(); k = ease.getInterpolation(a.animatedFraction); frame() }
            addListener(object : AnimatorListenerAdapter() {
                private var cut = false
                override fun onAnimationCancel(animation: Animator) { cut = true }
                override fun onAnimationEnd(animation: Animator) { if (!cut) { anim = null; settle() } }
            })
            start()
        }
    }

    /** the motion lands at once (the tests' last frame; a state change in the middle of one) */
    fun settle() {
        k = 1f; tMs = msNow
        ghost = null
        rowFrom = rowTo
        if (phase != Phase.ROOM) { stageFrom = null; stageTo = null; pageFrom = null; pageTo = null }
        frame()
    }

    /** the motion at [ms] in, held there (the shots' frames) */
    fun seek(ms: Long) {
        anim?.cancel(); anim = null
        tMs = ms.coerceIn(0L, msNow)
        k = if (msNow <= 0L) 1f else ease.getInterpolation(tMs.toFloat() / msNow)
        frame()
    }

    val running: Boolean get() = anim != null

    private fun shift(b: Box, dx: Float) = Box(b.l + dx, b.t, b.r + dx, b.b)
    private fun ghost(p: Picture?, src: Box?, from: Box, to: Box, a0: Float, a1: Float) {
        if (p == null || src == null) return
        ghost = p; ghostSrc = src; ghostFrom = from; ghostTo = to; ghostA0 = a0; ghostA1 = a1
    }

    /** the page's place and alpha now ([onPage]) and both layers drawn again */
    private fun frame() {
        val pf = pageFrom; val pt = pageTo
        if (pf != null && pt != null) {
            val a = if (pageA0 >= 1f) 1f else {
                val from = SingleMenu.CONTENT_FROM
                ((k - from) / (1f - from)).coerceIn(0f, 1f)
            }
            onPage?.invoke(SingleMenu.lerp(pf, pt, k), a)
        } else onPage?.invoke(null, 0f)
        back.invalidate(); front.invalidate()
    }

    /** the row's share up now (0 down .. 1 up) for disc [i] ([hub] = the swirl's index): the fan staggers it from the swirl outward */
    private fun rowK(i: Int, hub: Int): Float {
        if (rowFrom == rowTo) return rowTo
        val f = ease.getInterpolation(SingleMenu.fanK(i, hub, tMs, msNow.coerceAtLeast(1L)))
        return rowFrom + (rowTo - rowFrom) * f
    }

    // ---------------------------------------------------------------- drawing

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pEdge = Paint()
    private val rf = RectF()
    private val RED = Color.rgb(222, 52, 40)

    private fun name(r: Room): String = Tr.t(when (r) {
        Room.HOME -> "Home"; Room.CHAT -> "Chat"; Room.CALENDAR -> "Calendar"; Room.MAP -> "Map"
        else -> RoomBar.word(r.name)
    })

    private fun drawStage(c: Canvas) {
        val a = stageFrom ?: return
        val b = stageTo ?: return
        val r = SingleMenu.lerp(a, b, k)
        val s = h / 1080f
        val full = stageBox()
        val grown = (r.w / full.w).coerceIn(0f, 1f)
        val folding = move == Move.FOLD || move == Move.FOLD_DROP
        val alpha = if (folding) (1f - ((k - 0.5f) / 0.5f)).coerceIn(0f, 1f) else 1f
        if (alpha <= 0.01f) return
        val fight = fightNow()
        rf.set(r.l, r.t, r.r, r.b)
        val rad = SingleType.R * s * grown.coerceAtLeast(0.3f)           // the tidy pass: every plate's corner (was 18)
        pFill.style = Paint.Style.FILL; pFill.shader = null
        pFill.color = Color.argb((242 * alpha).toInt(), 16, 12, 9)
        c.drawRoundRect(rf, rad, rad, pFill)
        pLine.strokeWidth = 3f * s
        pLine.color = if (fight) RED else Theme.bronze
        pLine.alpha = (255 * alpha).toInt()
        c.drawRoundRect(rf, rad, rad, pLine)
        if (grown < 0.9f || !fight) return
        // a fight: "In a fight" on a small plate at the frame's foot, centred (the rooms' own title rows stay theirs)
        val ta = (255 * alpha * ((grown - 0.9f) / 0.1f).coerceIn(0f, 1f)).toInt()
        val fp = UiKit.bodyPaint(SingleType.BODY * s, RED).apply { this.alpha = ta; textAlign = Paint.Align.LEFT }
        val words = Tr.t("In a fight")
        val tw = fp.measureText(words)
        val pw = tw + 64f * s; val ph = 44f * s
        rf.set(r.cx - pw / 2f, r.b - ph / 2f, r.cx + pw / 2f, r.b + ph / 2f)
        pFill.color = Color.argb((235 * alpha).toInt(), 20, 8, 6)
        c.drawRoundRect(rf, ph / 2f, ph / 2f, pFill)
        pLine.strokeWidth = 2.5f * s; pLine.color = RED; pLine.alpha = ta
        c.drawRoundRect(rf, ph / 2f, ph / 2f, pLine)
        pFill.color = RED; pFill.alpha = ta
        c.drawCircle(rf.left + 24f * s, rf.centerY(), 7f * s, pFill)
        c.drawText(words, rf.left + 40f * s, rf.centerY() - (fp.ascent() + fp.descent()) / 2f, fp)
    }

    private fun drawEdges(c: Canvas) {
        if (!fightNow()) return
        // a drop fades the flash out with the row
        val rowShare = when {
            SingleMenu.edgeFlash(true, phase) -> 1f
            running && (move == Move.DROP || move == Move.FOLD_DROP) -> 1f - k
            else -> 0f
        }
        if (rowShare <= 0.01f) return
        val a = SingleMenu.edgeAlpha(pulse()) * rowShare
        val d = SingleMenu.EDGE_W * h / 1080f
        val col = Color.argb((255 * a).toInt(), 230, 30, 20)
        val clear = Color.argb(0, 230, 30, 20)
        val W = w.toFloat(); val H = h.toFloat()
        pEdge.shader = LinearGradient(0f, 0f, 0f, d, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, W, d, pEdge)
        pEdge.shader = LinearGradient(0f, H, 0f, H - d, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, H - d, W, H, pEdge)
        pEdge.shader = LinearGradient(0f, 0f, d, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, d, H, pEdge)
        pEdge.shader = LinearGradient(W, 0f, W - d, 0f, col, clear, Shader.TileMode.CLAMP); c.drawRect(W - d, 0f, W, H, pEdge)
        pEdge.shader = null
    }

    private fun drawGhost(c: Canvas) {
        val p = ghost ?: return
        val src = ghostSrc ?: return
        val r = SingleMenu.lerp(ghostFrom ?: return, ghostTo ?: return, k)
        val fade = if (move == Move.SLIDE) (k / 0.5f).coerceIn(0f, 1f) else ((k - 0.7f) / 0.3f).coerceIn(0f, 1f)
        val a = ghostA0 + (ghostA1 - ghostA0) * fade
        if (a <= 0.01f || r.w <= 1f) return
        val save = c.save()
        c.translate(r.l, r.t)
        c.scale(r.w / src.w, r.h / src.h)
        val l = c.saveLayerAlpha(0f, 0f, src.w, src.h, (255 * a).toInt())
        try { c.drawPicture(p) } catch (t: Throwable) { ghost = null }
        c.restoreToCount(l)
        c.restoreToCount(save)
    }

    private fun drawRow(c: Canvas) {
        val ds = discs()
        if (ds.isEmpty()) return
        val hub = ds.indexOfFirst { it.room == Room.HOME }.coerceAtLeast(0)
        val s = h / 1080f
        if (rowTo == 0f && rowFrom == 0f) return
        val bandK = rowK(hub, hub)
        // the band the row stands on: the game darkens toward the bottom edge
        val top = SingleMenu.BAND_TOP * s
        pFill.shader = LinearGradient(0f, top, 0f, h.toFloat(), Color.argb(0, 8, 6, 4), Color.argb((225 * bandK).toInt(), 8, 6, 4), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, w.toFloat(), h.toFloat(), pFill)
        pFill.shader = null
        val lit = litRoom()
        val now = clock()
        // the lit place glides from disc to disc (the bar cursor's glide)
        if (lit != litNow) {
            val was = litNow?.let { r -> ds.firstOrNull { it.room == r } }
            litFrom = was?.let { it.cx to it.cy }; litAt = now; litNow = lit
        }
        for ((i, d) in ds.withIndex()) {
            val rk = rowK(i, hub)
            if (rk <= 0.01f) continue
            val (fx, fy) = SingleMenu.riseFrom(d, dock, w, h)
            val cx = fx + (d.cx - fx) * rk; val cy = fy + (d.cy - fy) * rk
            val sc = 0.55f + 0.45f * rk
            val alpha = (255 * rk).toInt()
            if (d.room == Room.HOME) {
                val dd = d.d * sc
                val ok = drawSwirl?.invoke(c, cx, cy, dd) == true
                if (!ok) { pFill.color = Color.rgb(40, 70, 120); pFill.alpha = alpha; c.drawCircle(cx, cy, dd / 2f, pFill) }
                if (lit == Room.HOME && phase == Phase.ROW) {
                    pLine.strokeWidth = 4f * s; pLine.color = Theme.gold; pLine.alpha = alpha
                    c.drawCircle(cx, cy, dd / 2f + 4f * s, pLine)
                }
                continue
            }
            val on = d.room == lit
            val dd = d.d * sc
            if (on) {
                // the cursor's gold light under the lit plate (gliding from the last lit disc)
                val gk = litFrom?.let { ((now - litAt).toFloat() / SingleMenu.CURSOR_MS).coerceIn(0f, 1f) } ?: 1f
                val gx = litFrom?.let { it.first + (cx - it.first) * ease.getInterpolation(gk) } ?: cx
                val gy = litFrom?.let { it.second + (cy - it.second) * ease.getInterpolation(gk) } ?: cy
                if (gk < 1f) front.postInvalidateOnAnimation()
                pFill.shader = RadialGradient(gx, gy, dd * 0.78f, intArrayOf(Color.argb((120 * rk).toInt(), 255, 200, 60), Color.argb(0, 255, 200, 60)),
                    floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(gx, gy, dd * 0.78f, pFill)
                pFill.shader = null
            }
            val plate = UiKit.tex(if (on) "radial_place_on" else "radial_place")
            if (plate != null) {
                pBmp.alpha = alpha
                c.drawBitmap(plate, null, RectF(cx - dd / 2f, cy - dd / 2f, cx + dd / 2f, cy + dd / 2f), pBmp)
                pBmp.alpha = 255
            } else {
                pFill.color = Color.rgb(16, 12, 9); pFill.alpha = alpha; c.drawCircle(cx, cy, dd / 2f, pFill)
                pLine.strokeWidth = 2.5f * s; pLine.color = if (on) Theme.gold else Theme.bronze; pLine.alpha = alpha
                c.drawCircle(cx, cy, dd / 2f, pLine)
            }
            val icon = (if (on) SingleMenu.ICON_ON else SingleMenu.ICON) * s * sc
            val l = if (alpha < 250) c.saveLayerAlpha(cx - dd, cy - dd, cx + dd, cy + dd, alpha) else -1
            if (!standIn(c, d.room, cx, cy, icon)) drawRoom?.invoke(c, d.room, cx, cy, icon)
            if (l >= 0) c.restoreToCount(l)
            if (on && rk > 0.6f) {
                val tp = UiKit.titlePaint(SingleType.TITLE * s, Theme.gold).apply { textAlign = Paint.Align.CENTER; this.alpha = alpha }
                val ty = minOf(cy + SingleMenu.NAME_DY * s, h - 10f * s)
                c.drawText(name(d.room), cx, ty, tp)
            }
        }
        if (bandK > 0.6f) drawHints(c, s, (255 * bandK).toInt())
    }

    /** the controls' words over the row, right; ZL / ZR's neighbours at the ends while a room shows */
    private fun drawHints(c: Canvas, s: Float, alpha: Int) {
        val fight = fightNow()
        val y = SingleMenu.HINT_Y * s
        val save = c.saveLayerAlpha(0f, y - 40f * s, w.toFloat(), y + 40f * s, alpha)
        val right = w - 260f * s
        when (phase) {
            Phase.ROW -> {
                val onSwirl = cursorOf() == Room.HOME
                UiKit.hints(c, right, y, aGlyph() to (if (onSwirl) "Home" else "Open"), UiKit.backGlyph() to (if (fight) "Game" else "Close"))
            }
            Phase.ROOM -> {
                val r0 = room
                if (r0 != null && SingleMenu.tabsHint(r0))
                    UiKit.hints(c, right, y, UiKit.backGlyph() to (if (fight) "Game" else "Back"), UiKit.shoulderGlyph(false) to "",
                        UiKit.shoulderGlyph(true) to "Tabs")
                else UiKit.hints(c, right, y, UiKit.backGlyph() to (if (fight) "Game" else "Back"))
                val r = room
                if (r != null && r != Room.HOME && triggersOn()) {
                    val p = UiKit.bodyPaint(SingleType.BODY * s, Theme.text)
                    SingleMenu.step(order, r, -1)?.let { prev ->
                        UiKit.pad(c, "${padSet()}_ltrigger", SingleMenu.ZL_X * s, y, 46f * s)
                        c.drawText(name(prev), (SingleMenu.ZL_X + 32f) * s, y - (p.ascent() + p.descent()) / 2f, p)
                    }
                    SingleMenu.step(order, r, 1)?.let { next ->
                        UiKit.pad(c, "${padSet()}_rtrigger", w - SingleMenu.ZL_X * s, y, 46f * s)
                        val pr = UiKit.bodyPaint(SingleType.BODY * s, Theme.text).apply { textAlign = Paint.Align.RIGHT }
                        c.drawText(name(next), w - (SingleMenu.ZL_X + 32f) * s, y - (pr.ascent() + pr.descent()) / 2f, pr)
                    }
                }
            }
            Phase.CLOSED -> {}
        }
        c.restoreToCount(save)
    }

    /** the study's stand-in for a room with no round icon yet: the Controller tab's own controller picture (the kit's tab_controller,
     *  its pad cropped), until UI Art draws ricon_controller; false = the bar's own picture */
    private val ctlSrc = android.graphics.Rect(40, 20, 290, 200)
    private fun standIn(c: Canvas, r: Room, cx: Float, cy: Float, size: Float): Boolean {
        if (r != Room.CONTROLLER || UiKit.tex("ricon_controller") != null) return false
        val b = UiKit.tex("tab_controller") ?: return false
        val src = if (b.width >= 290 && b.height >= 200) ctlSrc else android.graphics.Rect(0, 0, b.width, b.height)
        val k = minOf(size * 1.15f / src.width(), size * 1.15f / src.height())
        val w = src.width() * k; val hh = src.height() * k
        c.drawBitmap(b, src, RectF(cx - w / 2f, cy - hh / 2f, cx + w / 2f, cy + hh / 2f), pBmp)
        return true
    }

    private fun padSet(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "gen"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp"
        else -> "rev"
    }
    private fun aGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_a"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_cross"
        else -> "rev_a"
    }

    /** the disc under ([x], [y]), or null */
    fun discAt(x: Float, y: Float): Room? {
        if (phase == Phase.CLOSED || running && (move == Move.DROP || move == Move.FOLD_DROP)) return null
        val pad = 10f * h / 1080f
        return discs().firstOrNull { d -> val dx = x - d.cx; val dy = y - d.cy; dx * dx + dy * dy <= (d.d / 2f + pad) * (d.d / 2f + pad) }?.room
    }

    // ---------------------------------------------------------------- the two layers

    private inner class Back(ctx: Context) : View(ctx) {
        init { isClickable = false; isFocusable = false }
        override fun onDraw(c: Canvas) { if (phase == Phase.ROOM || stageFrom != null) drawStage(c) }
        /** a finger on the stage's frame (beside a fallback room's page) is the stage's, never the dim's (which would close the menu) */
        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean = phase == Phase.ROOM && stageBox().contains(e.x, e.y)
    }

    private inner class Front(ctx: Context) : View(ctx) {
        private var down: Room? = null
        init { isClickable = false; isFocusable = false }
        override fun onDraw(c: Canvas) {
            drawEdges(c)
            drawRow(c)
            drawGhost(c)
            // the live swirl breathes, the edges pulse: drawn again while the menu shows (15 / 30 a second)
            if (phase != Phase.CLOSED) postInvalidateDelayed(if (SingleMenu.edgeFlash(fightNow(), phase)) 33L else 66L)
        }
        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = discAt(e.x, e.y); return down != null }
                MotionEvent.ACTION_UP -> {
                    val d = down; down = null
                    if (d != null && discAt(e.x, e.y) == d) onDisc?.invoke(d)
                    return d != null
                }
                MotionEvent.ACTION_CANCEL -> { down = null; return false }
            }
            return down != null
        }
    }
}
