package com.jrees.wowcompanion

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MOVABLE HOME, PHASE 2: EDIT MODE'S CHROME (SPEC.txt sections 3, 4, 7, 8; final_02 .. final_06, final_09). A child of [HomeView] over every
 * part, the frame's size: Home stays LIVE and FULL SIZE under it (D6) - what you edit is what you get, touch targets keep their size.
 *
 *  - every widget that is not selected is dimmed (black 70 / 255) with a soft dashed ink frame and its terse tag; a rider inside a
 *    widget (the quest card on the map) a lighter inner frame; the party list (Party mode's module) is a widget like the others since
 *    the user's ruling 2026-11-22 after 1518 (the top or the foot of either column), its tag clear of its Select tab;
 *  - THE TOP BAR STAYS STILL (1518 build 3, the user's ruling 2026-11-21; the user's ruling 2026-11-22 after 1518: "What the hell is the
 *    dpad swapping buttons or clock move? ... remove that"): the band wears nothing - no dim, no dashed frame, no tag, no Clock box, no
 *    wiggle, no badge - and has no stops: a finger on it finds nothing; its options live in Settings > Bottom screen;
 *  - the selected widget wears the GOLDEN BOX (the app's cursor look: a thick gold box with its glow, never a thin outline), at once;
 *    lifted, the kit's gold arrows point at the docks it can reach, and a finger's drop well shows the dock under it (red: refused);
 *  - the divider's handle between the columns, the red X chip on a selected removable widget ("Remove?" first);
 *  - a widget's menu over the other column, the Add tray;
 *  - THE ROOM BAR'S ROOMS AS APPS (1518 build 2, the user's ruling 2026-11-21): no frame, tag or menu round the bar - each room wiggles
 *    on its own with a small "-" badge (Settings none), a "+" spot at the bar's end while a room is hidden, its small tray of the hidden
 *    rooms rising from it ([apps], [drawBar], [barTouch]);
 *  - THE EDIT RIBBON in the XP bar's place (drawn by the Strip through [drawRibbon]; test build 1518, the user's ruling 2026-11-21: no
 *    title, no layout chip): "+" at its left while something is off Home, the hints one word each, and "Done" at its right - a tap is
 *    Done (every change is saved already), a long press ([HomeEditModel.DISCARD_HOLD_MS], its gold ring filling from
 *    [HomeEditModel.RING_FROM_MS]) Discard; the controller reaches Done as a stop too (Right from the room bar's end: A Done, X Discard).
 * Every input goes to [HomeEditModel]; its effects go back to the host ([Host.run]).
 */
class HomeEditView(context: Context, private val host: Host) : View(context) {

    /** what the chrome needs of Home */
    interface Host {
        /** the model the chrome draws: the live session's, or the ended one's while its chrome fades out */
        val model: HomeEditModel?
        /** the LIVE session only (null while an ended session's chrome fades): touches go to it, never to the ended one */
        val liveModel: HomeEditModel? get() = model
        /** the chrome's alpha (its 120 ms fade in, 200 out, the fight's park) */
        val chromeAlpha: Float
        /** every part's box now in Home's px (logical: the lifted widget at its laid place; [pose] moves and turns it). The host's own
         *  map and boxes (read every frame): good until the next call, never kept or changed */
        fun parts(): Map<HomePart, RectF>
        /** the lifted widget's lift (0 .. 1: the shadow) */
        fun liftK(): Float
        /** the iPhone-style motion: [p]'s pose now as a matrix in Home's px (the shrink, the wiggle, the lift, the drop, the finger's
         *  carry; false = none). The chrome on a widget is drawn through its part's pose, so it shrinks and wiggles with it */
        fun pose(p: HomePart, out: android.graphics.Matrix): Boolean = false
        /** the gold cursor's flight: its offset now per edge (l, t, r, b) from the widget's box */
        fun cursorOffset(out: FloatArray) { out.fill(0f) }
        /** the gold cursor's turn now over its widget's (deg, about the box's centre): its flight from the old widget's angle */
        fun cursorTurn(): Float = 0f
        /** a pop's progress (HomeEditMotion.MENU / SHEET / CHIP / RIBBON; 1 = landed) */
        fun popK(kind: Int): Float = 1f
        /** Done's hold (a finger on it, B held on the direct feed): 0 .. 1 while it runs towards Discard, null = none (a short press: the
         *  first [HomeEditModel.RING_FROM_MS] show nothing) */
        fun holdK(): Float? = null
        /** a finger went down on Done ([down]) or up / away from it: the host's hold timer (true on the up = the hold fired already:
         *  Discard ran, the up does nothing) */
        fun holdDone(down: Boolean): Boolean = false
        /** a room's pose on the bar (scale, turn deg, dx, dy into [out]); false = none */
        fun roomPose(r: Room, out: FloatArray): Boolean = false
        /** the bar's "+" spot's pose (its own wiggle: HomeEditMotion.PLUS_ROOM), as [roomPose] */
        fun plusPose(out: FloatArray): Boolean = false
        /** an input reached the chrome (the edit ticker runs on: the wiggle never rests while edit mode is open) */
        fun poke() {}
        /** the bars each widget has in the state now, for [l] (the drop well's box) */
        fun solve(l: HomeLayout): Map<HomePart, HomeLayoutSolver.Box>
        val geo: HomeEditModel.Geo
        fun run(fx: List<HomeEditModel.Fx>)
        fun menuRows(s: EditStop): List<MenuRow>
        /** the room bar's height (the ribbon sits in its top TRACKS px) */
        val stripH: Int
        /** a red pulse on a widget's edge (a refusal, a fight): the widget and when */
        val flash: Pair<EditStop, Long>?
        /** phase 4: the room bar under Home (null = none: no Room bar stop) */
        fun bar(): Strip? = null
        /** THE TOP BAR'S PIECES now in Home's px (P1 - P4; null = the band is not laid on Home now) */
        fun band(): BandGeo? = null
        /** P2: extra [key]'s wiggle ("plus" = the extras' "+" spot) as a matrix in Home's px; false = none */
        fun extraPose(key: String, out: android.graphics.Matrix): Boolean = false
    }

    /**
     * THE TOP BAR'S PIECES (the user's rulings 2026-11-22, the picker's ALL FOUR), Home's px: the band, the D-pad's and the faces' button
     * groups ([pad], [face]: a finger on either opens its pencil's bubble too), the two pencils, the Look chip (null in the fight preview),
     * the fight pencil (the preview only), the clock's extras shown in edit mode (key, box: the chip's gold line "gold" among them) and the
     * extras' "+" spot (null = none hidden, or no extras row: the thin row, the preview); the clock chip as drawn (null = none)
     */
    class BandGeo(val band: RectF, val pad: RectF, val face: RectF, val padPen: RectF, val facePen: RectF, val look: RectF?,
                  val fightPen: RectF?, val extras: List<Pair<String, RectF>>, val plus: RectF?, val diamonds: Boolean,
                  val chip: RectF? = null, val keys: List<RectF> = emptyList())

    init { isClickable = true }

    /** Home draws this view itself, LAST (over its own paint: HomeView.dispatchDraw), so a redraw of it is a redraw of Home */
    override fun invalidate() { super.invalidate(); (parent as? View)?.invalidate() }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) }
    /** the lifted widget's deep shadow (one blur, made once: the ticker draws it every frame while a widget is lifted) */
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL) }

    /**
     * THE CHROME'S TEXT PAINTS, made once per face / size / colour / alignment: the wiggle's ticker redraws the chrome (its tags, the
     * ribbon, an open menu) every frame the whole time edit mode is open, so no TextPaint (and its shadow layer) is made per draw.
     * A paint handed out is shared: its colour (alpha included) is set again on every fetch; nothing else of it may be changed.
     */
    private class TextKey(val face: android.graphics.Typeface, val size: Float, val colour: Int, val align: Paint.Align, val p: android.text.TextPaint)
    private val textCache = ArrayList<TextKey>()
    private fun textP(kind: Int, size: Float, colour: Int, align: Paint.Align): android.text.TextPaint {
        val face = when (kind) { 0 -> UiKit.body; 1 -> UiKit.title; else -> UiKit.number }
        for (i in textCache.indices) {
            val k = textCache[i]
            if (k.face === face && k.size == size && k.colour == colour && k.align == align) { k.p.color = colour; return k.p }
        }
        val p = when (kind) { 0 -> UiKit.bodyPaint(size, colour); 1 -> UiKit.titlePaint(size, colour); else -> UiKit.numberPaint(size, colour) }
        p.textAlign = align
        if (textCache.size >= TEXT_CACHE) textCache.removeAt(0)
        textCache += TextKey(face, size, colour, align, p)
        return p
    }
    private fun bodyP(size: Float, colour: Int = Theme.text, align: Paint.Align = Paint.Align.LEFT) = textP(0, size, colour, align)
    private fun titleP(size: Float, colour: Int = Theme.gold, align: Paint.Align = Paint.Align.LEFT) = textP(1, size, colour, align)
    private fun numberP(size: Float, colour: Int = Theme.text, align: Paint.Align = Paint.Align.LEFT) = textP(2, size, colour, align)
    /** the fight pencil's own colours (the readability pass): a fight-red ring and tip on a dark red disc */
    private val FIGHT_RED = Color.rgb(200, 60, 40)
    private val FIGHT_INK = Color.rgb(58, 16, 12)
    private val dash = DashPathEffect(floatArrayOf(14f, 9f), 0f)
    private val dashFine = DashPathEffect(floatArrayOf(8f, 7f), 0f)
    private val dashPlus = DashPathEffect(floatArrayOf(5f, 4f), 0f)
    private val path = Path()
    /** the motion's scratch (no allocation per frame) */
    private val mtx = android.graphics.Matrix()
    private val cur = FloatArray(4)

    // ---------------------------------------------------------------- where everything is

    /** the widgets' boxes as drawn (CAST = the bar's first half; [castBoxes] both), in Home's px */
    fun widgetBoxes(): Map<EditStop, RectF> = LinkedHashMap<EditStop, RectF>().also { o -> for ((s, r) in boxesNow(host.parts())) o[s] = RectF(r) }

    /**
     * [widgetBoxes] into this view's own map and boxes (the drawing's: the wiggle's ticker draws the chrome every frame, so nothing is
     * made per frame); good until the next call, never kept
     */
    private fun boxesNow(p: Map<HomePart, RectF>): Map<EditStop, RectF> {
        wbNow.clear()
        // the band is no widget (1518 build 3) and has no stops (the user's ruling 2026-11-22: it stays still)
        p[HomePart.CHAT]?.let { putBox(EditStop.CHAT, it) }
        p[HomePart.MAP]?.let { putBox(EditStop.MAP, it) }
        p[HomePart.QUESTS]?.let { putBox(EditStop.QUEST, it) }
        // after 1518: the party list is a widget (the top or the foot of either column)
        p[HomePart.PARTY]?.let { putBox(EditStop.PARTY, it) }
        val bs = castNow(p)
        if (bs.isNotEmpty()) { val o = putBox(EditStop.CAST, bs[0]); for (i in 1 until bs.size) o.union(bs[i]) }
        return wbNow
    }
    private val wbNow = LinkedHashMap<EditStop, RectF>()
    private val wbPool = java.util.EnumMap<EditStop, RectF>(EditStop::class.java)
    private fun putBox(s: EditStop, r: RectF): RectF {
        val o = wbPool[s] ?: RectF().also { wbPool[s] = it }
        o.set(r); wbNow[s] = o
        return o
    }

    /** the tap-to-cast bar's box(es) on the page (none while it is slid away: below the page, or up behind the band for a top bar) */
    fun castBoxes(p: Map<HomePart, RectF> = host.parts()): List<RectF> = castNow(p).map { RectF(it) }
    /** [castBoxes] into this view's own list (the drawing's; good until the next call) */
    private fun castNow(p: Map<HomePart, RectF>): List<RectF> {
        castOut.clear()
        val h = height.toFloat().takeIf { it > 0f } ?: 935f
        val band = p[HomePart.BARS]?.bottom ?: 0f
        for (part in CAST_PARTS) {
            val r = p[part] ?: continue
            if (r.top < h - 1f && r.bottom > band + 1f) { val o = castPool[castOut.size]; o.set(r); castOut += o }
        }
        return castOut
    }
    private val castOut = ArrayList<RectF>(2)
    private val CAST_PARTS = arrayOf(HomePart.STRIP, HomePart.CAST2)
    private val castPool = arrayOf(RectF(), RectF())

    /** the bar laid at the top of a column (Above chat / Above map): its box, else null */
    private fun topCastBox(p: Map<HomePart, RectF>): RectF? =
        if (host.model?.draft?.cast?.top == true) castNow(p).firstOrNull()?.let { RectF(it) } else null

    /** the divider's handle: centred in the 8 px between the columns, halfway down them */
    fun dividerBox(p: Map<HomePart, RectF> = host.parts()): RectF? = dividerNow(p)?.let { RectF(it) }
    private val divBox = RectF()
    private fun dividerNow(p: Map<HomePart, RectF>): RectF? {
        val c = p[HomePart.CHAT] ?: return null; val m = p[HomePart.MAP] ?: return null
        val left = if (c.left < m.left) c else m; val right = if (left === c) m else c
        val x = (left.right + right.left) / 2f
        val top = max(left.top, right.top); val bottom = min(left.bottom, right.bottom)
        val cy = (top + bottom) / 2f
        divBox.set(x - HANDLE_W / 2f, cy - HANDLE_H / 2f, x + HANDLE_W / 2f, cy + HANDLE_H / 2f)
        return divBox
    }

    /** the cursor's stops in Home's px (the D-pad walks between them spatially; the band has none) */
    fun stops(): Map<EditStop, HomeLayoutSolver.Box> {
        val out = LinkedHashMap<EditStop, HomeLayoutSolver.Box>()
        val wb = widgetBoxes()
        for ((s, r) in wb) out[s] = HomeLayoutSolver.Box(r.left, r.top, r.right, r.bottom)
        // the divider's D-pad stop is the columns' top (Up from either column's top reaches it; Left / Right there step the width)
        dividerBox()?.let { d -> val top = listOfNotNull(host.parts()[HomePart.CHAT], host.parts()[HomePart.MAP]).minOfOrNull { it.top } ?: d.top
            // only the gap's middle: never straight above / below a column
            out[EditStop.DIVIDER] = HomeLayoutSolver.Box(d.centerX() - 2f, top, d.centerX() + 2f, top + 40f) }
        val rib = ribbon(width.toFloat().takeIf { it > 0f } ?: 1240f)
        val y0 = height.toFloat()
        // "+" on the ribbon while something is off Home
        if (host.model?.plusShown == true) out[EditStop.PLUS] = HomeLayoutSolver.Box(rib.plus.left, y0 + rib.plus.top, rib.plus.right, y0 + rib.plus.bottom)
        // Done (B and Start are Done anywhere; the stop is the relayed feed's way to Discard, which sees no held B): Right only
        out[EditStop.DONE] = HomeLayoutSolver.Box(rib.done.left, y0 + rib.done.top, rib.done.right, y0 + rib.done.bottom)
        // phase 4: the room bar's rooms' row, under the ribbon (Down from the columns; Left / Right there walk its rooms)
        if (host.bar() != null) out[EditStop.ROOMS] = HomeLayoutSolver.Box(8f, y0 + Strip.TRACKS, (width.toFloat().takeIf { it > 0f } ?: 1240f) - 8f, y0 + Strip.BAR)
        // P4: the ribbon's Fight toggle (between "+" and Done: Right from "+", Left from Done, Up from the room bar)
        out[EditStop.FIGHT] = HomeLayoutSolver.Box(rib.fight.left, y0 + rib.fight.top, rib.fight.right, y0 + rib.fight.bottom)
        // THE TOP BAR'S PIECES (P1 - P4): the two pencils, the Look chip or the fight pencil, the extras' row (one stop: its own cursor)
        host.band()?.let { g ->
            // (Top screen's preview: the band slid up off the page - its pieces with it are no stops; the fight pencil stands on the page)
            fun put(s: EditStop, r: RectF) { if (r.bottom > 0f) out[s] = HomeLayoutSolver.Box(r.left, r.top, r.right, r.bottom) }
            put(EditStop.PAD_PEN, g.padPen); put(EditStop.FACE_PEN, g.facePen)
            g.look?.let { put(EditStop.LOOK, it) }
            g.fightPen?.let { put(EditStop.FIGHT_PEN, it) }
            val cells = extrasUnion(g)
            if (cells != null) put(EditStop.EXTRAS, cells)
        }
        // P4 (decision D4): while the fight preview shows, only the band, the ribbon and the room bar are edited (the columns are laid
        // under the grown band: nothing of theirs is reached)
        if (host.model?.fightPreview == true) for (s in PREVIEW_GONE) out.remove(s)
        return out
    }
    /** the extras' cells and their "+" spot as one box (null = no extras row shown) */
    private fun extrasUnion(g: BandGeo): RectF? {
        if (g.extras.isEmpty() && g.plus == null) return null
        val u = RectF(g.plus ?: g.extras.first().second)
        for ((_, r) in g.extras) u.union(r)
        return u
    }
    /** the box of the extra under the stop's cursor (or of "+"), as drawn: null = none */
    private fun extraBox(g: BandGeo, m: HomeEditModel): RectF? =
        if (m.extraOnPlus) g.plus?.let { extraPicture(EXTRA_PLUS, it) }
        else m.extra?.let { k -> g.extras.firstOrNull { it.first == k }?.let { (_, r) -> extraPicture(k, r) } }
    /** [s]'s box on the band now (a pencil, the Look chip, the extra under the cursor: its picture - the readability pass: the cursor
     *  hugs the 28 px picture, not the taller cell under it), null = none */
    fun bandBox(s: EditStop): RectF? {
        val g = host.band() ?: return null
        val m = host.model
        return when (s) {
            EditStop.PAD_PEN -> g.padPen; EditStop.FACE_PEN -> g.facePen; EditStop.LOOK -> g.look; EditStop.FIGHT_PEN -> g.fightPen
            EditStop.EXTRAS -> m?.let { extraBox(g, it) } ?: extrasUnion(g)
            else -> null
        }?.let { RectF(it) }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val m = host.model ?: return
        val a = host.chromeAlpha
        if (a <= 0.01f) return
        val save = if (a < 0.99f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * a).toInt()) else -1
        drawWidgets(c, m)
        drawBandPieces(c, m)
        // the menu pops out of its widget's side (0.92 -> 1 with a little overshoot), the Add tray rises; a pencil's bubble out of its tail
        m.menu?.let { s ->
            val k = host.popK(HomeEditMotion.MENU)
            val sv = c.save()
            if (abs(k - 1f) > 0.001f) {
                val box = menuBox(s)
                if (s.pencil) popIn(c, k, box, bubbleTailX(s, box), box.top, MENU_FROM)
                else {
                    val wb = boxesNow(host.parts())[s] ?: RectF(box.centerX(), height.toFloat(), box.centerX(), height.toFloat())
                    popIn(c, k, box, wb.centerX().coerceIn(box.left, box.right), wb.centerY().coerceIn(box.top, box.bottom), MENU_FROM)
                }
            }
            drawMenu(c, m)
            c.restoreToCount(sv)
        }
        if (m.sheet == HomeEditModel.Sheet.EXTRAS) {
            // P2: the tray of the hidden extras pops out of the extras' "+" spot (as the room bar's out of its own)
            val k = host.popK(HomeEditMotion.SHEET)
            val sv = c.save()
            val lay = extraTray(m)
            if (lay != null && abs(k - 1f) > 0.001f) popIn(c, k, lay.panel, lay.ax, lay.panel.top, MENU_FROM)
            drawExtraTray(c, m, lay)
            c.restoreToCount(sv)
        } else if (m.sheet == HomeEditModel.Sheet.ROOMS) {
            // the bar's tray of hidden rooms pops out of its "+" spot (0.92 -> 1, as a menu out of its widget)
            val k = host.popK(HomeEditMotion.SHEET)
            val sv = c.save()
            val lay = roomTray(m)
            if (lay != null && abs(k - 1f) > 0.001f) popIn(c, k, lay.panel, lay.ax, lay.panel.bottom, MENU_FROM)
            drawRoomTray(c, m, lay)
            c.restoreToCount(sv)
        } else if (m.sheet != null) {
            val k = host.popK(HomeEditMotion.SHEET)
            val sv = c.save()
            if (abs(k - 1f) > 0.001f) {
                c.translate(0f, (1f - k) * SHEET_RISE)
                val a = (k / 0.6f).coerceIn(0f, 1f)
                if (a < 1f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * a).toInt())
            }
            drawTray(c, m)
            c.restoreToCount(sv)
        }
        if (save >= 0) c.restoreToCount(save)
        val fl = host.flash
        if (fl != null && SystemClock.uptimeMillis() - fl.second < HomeEditModel.FLASH_MS) postInvalidateOnAnimation()
        if (m.drag != null) postInvalidateOnAnimation()
    }

    /** a pop: scaled from [from] to 1 about [ax], [ay] as [k] goes 0 -> 1 (its overshoot past 1 too), faded in over its first 60 % */
    private fun popIn(c: Canvas, k: Float, box: RectF?, ax: Float, ay: Float, from: Float) {
        val s = from + (1f - from) * k
        c.scale(s, s, ax, ay)
        val a = (k / 0.6f).coerceIn(0f, 1f)
        if (a < 1f) {
            if (box != null) c.saveLayerAlpha(box.left - 40f, box.top - 40f, box.right + 40f, box.bottom + 40f, (255 * a).toInt())
            else c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * a).toInt())
        }
    }

    /** the part whose pose [s]'s chrome follows (a rider: its host's, which HomeView resolves) */
    private fun poseOf(s: EditStop): HomePart? = when (s) {
        EditStop.CHAT -> HomePart.CHAT
        EditStop.MAP -> HomePart.MAP
        EditStop.QUEST -> HomePart.QUESTS
        EditStop.CAST -> HomePart.STRIP
        EditStop.PARTY -> HomePart.PARTY
        else -> null
    }
    /** a canvas save with [s]'s pose on it (restore with the count returned) */
    private fun posed(c: Canvas, s: EditStop): Int = posed(c, poseOf(s))
    private fun posed(c: Canvas, p: HomePart?): Int {
        val sv = c.save()
        if (p != null && host.pose(p, mtx)) c.concat(mtx)
        return sv
    }
    /** [r] (a box of [s]'s) as drawn now: its pose applied (the bounds of it), into [o] */
    private fun posedRect(s: EditStop, r: RectF, o: RectF = RectF()): RectF {
        o.set(r)
        val p = poseOf(s)
        if (p != null && host.pose(p, mtx)) mtx.mapRect(o)
        return o
    }

    /** the lifted widget's drawn boxes (the drawing's own: good until the next frame) */
    private val liftOut = ArrayList<RectF>(2)
    private val liftPool = arrayOf(RectF(), RectF())
    /** the cursor's box this frame */
    private val curBox = RectF()

    private fun drawWidgets(c: Canvas, m: HomeEditModel) {
        val p = host.parts()
        val wb = boxesNow(p)
        val cast = castNow(p)
        val sel = m.cursor
        val lifted = m.lifted
        val card = wb[EditStop.QUEST]
        val cardOnMap = m.draft.card == HomeLayout.CardDock.ON_MAP
        val lk = host.liftK()
        // the lifted widget lies over everything (its depth): the chrome of the others is never drawn on it - only round it
        liftOut.clear()
        if (lifted == EditStop.CAST) for (b in cast) liftOut += posedRect(lifted, b, liftPool[liftOut.size])
        else if (lifted != null) wb[lifted]?.let { liftOut += posedRect(lifted, it, liftPool[0]) }
        val under = c.save()
        for (i in liftOut.indices) c.clipOutRect(liftOut[i])
        if (lk > 0f) for (i in liftOut.indices) shadow(c, liftOut[i], lk)
        // 1. the dim over every widget that is not the selected one (black 70): a rider is dimmed with its host unless picked; the band
        // never (it is no widget: it shows the physical buttons, undimmed)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(DIM, 0, 0, 0)
        for ((s, r) in wb) {
            if (s == EditStop.QUEST && cardOnMap) continue                       // riders: inside their host's dim
            if (s == lifted) continue
            val keep = when (s) {
                EditStop.MAP -> if (sel == EditStop.QUEST && cardOnMap) card else if (sel == EditStop.MAP) r else null
                else -> if (sel == s) r else null
            }
            if (keep === r) continue
            val sv = posed(c, s)
            if (s == EditStop.CAST) { for (i in cast.indices) dimRect(c, cast[i], null) } else dimRect(c, r, keep)
            c.restoreToCount(sv)
        }
        // 2. the dashed ink frames and the tags (where they hide nothing: the map's bottom left, the card's top right, the bar's on its top
        // edge); a rider moves with its lifted host
        tagN = 0
        // P4 (D4): with the fight preview the page's widgets are not edited: dimmed only, no frame, no tag, no handle
        if (!m.fightPreview) for ((s, r) in wb) {
            val rider = s == EditStop.QUEST && cardOnMap
            val sv = posed(c, s)                                            // a rider rides its host's pose (HomeView resolves it)
            if (s != sel) { if (s == EditStop.CAST) for (i in cast.indices) frame(c, cast[i], light = rider) else frame(c, r, light = rider) }
            c.restoreToCount(sv)
            addTag(s, if (s == EditStop.CAST && cast.isNotEmpty()) cast[0] else if (s == EditStop.PARTY) partyRows(r) else r, s == sel)
        }
        // the divider's handle
        if (!m.fightPreview) dividerNow(p)?.let { d -> handle(c, d, sel == EditStop.DIVIDER || m.drag?.stop == EditStop.DIVIDER) }
        // 3. a finger's drop well: the dock under it (gold; red = refused, it would break a size rule)
        m.drag?.let { d -> d.cand?.let { cand -> wellFor(d.stop, cand)?.forEach { well(c, it, d.bad) } } }
        c.restoreToCount(under)
        // 4. the golden box on the selected widget (riding the lifted one), the arrows to the docks a lifted one reaches
        val one = when (sel) {
            EditStop.CAST, EditStop.DIVIDER, EditStop.PLUS, EditStop.DONE -> null
            else -> wb[sel]
        }
        val selN = if (sel == EditStop.CAST) cast.size else if (one != null) 1 else 0
        // the golden box rides its widget's pose and flies from the last one (the cursor's spring: its offset per edge)
        host.cursorOffset(cur)
        // its turn too (about the box's centre; a Split bar's two boxes about their union's, the box its flight was seated on)
        val turn = host.cursorTurn()
        val pivot = if (sel == EditStop.CAST) wb[EditStop.CAST] else null
        for (i in 0 until selN) {
            val b0 = if (sel == EditStop.CAST) cast[i] else one!!
            val b = curBox
            if (selN > 1) { b.set(b0); b.offset((cur[0] + cur[2]) / 2f, (cur[1] + cur[3]) / 2f) }
            else b.set(b0.left + cur[0], b0.top + cur[1], b0.right + cur[2], b0.bottom + cur[3])
            val sv = posed(c, sel)
            if (turn != 0f) {
                if (selN > 1 && pivot != null) c.rotate(turn, pivot.centerX() + (cur[0] + cur[2]) / 2f, pivot.centerY() + (cur[1] + cur[3]) / 2f)
                else c.rotate(turn, b.centerX(), b.centerY())
            }
            goldBox(c, b)
            c.restoreToCount(sv)
        }
        if (lifted != null && m.drag == null && selN > 0) (if (sel == EditStop.CAST) cast[0] else one!!).let { b ->
            val sv = posed(c, lifted)
            val canL = m.nextDock(lifted, m.draft, -1, true) != null
            val canR = m.nextDock(lifted, m.draft, 1, true) != null
            if (canL || canR) UiKit.moveArrows(c, RectF(b.left, b.centerY() - 40f, b.right, b.centerY() + 40f), canL, canR, words = false)
            // the card's and the bar's main moves are Up / Down (On map <-> Above chat; Under chat -> ... -> Split): their arrows too
            val canU = m.nextDock(lifted, m.draft, -1, false) != null
            val canD = m.nextDock(lifted, m.draft, 1, false) != null
            if (canU || canD) UiKit.moveArrowsUpDown(c, b, canU, canD, around = true)
            c.restoreToCount(sv)
        }
        // 5. the red X chip on a selected removable widget, "Remove?" after its first tap
        if (sel.removable && lifted == null && m.menu == null && m.sheet == null && selN > 0)
            (if (sel == EditStop.CAST) cast[selN - 1] else one!!).let { b ->
                val x = removeChip(b)
                val sv = posed(c, sel)
                val k = host.popK(HomeEditMotion.CHIP)                          // the chip pops in as the cursor lands
                if (abs(k - 1f) > 0.001f) c.scale(k.coerceAtLeast(0f), k.coerceAtLeast(0f), x.centerX(), x.centerY())
                closeChip(c, x)
                if (m.removeAsk == sel) pill(c, removePill(b), "Remove?")
                c.restoreToCount(sv)
            }
        // the tags last (no golden box, dim or neighbour's frame hides a widget's name): the others' still under the lifted widget
        val overLift = c.save()
        for (i in liftOut.indices) c.clipOutRect(liftOut[i])
        for (i in 0 until tagN) {
            val t = tagPool[i]
            if (!liftedTag(t.s, lifted, cardOnMap)) { val sv = posed(c, t.s); tag(c, t.r, t.s.tag, t.gold, cornerOf(t.s, t.r)); c.restoreToCount(sv) }
        }
        c.restoreToCount(overLift)
        for (i in 0 until tagN) {
            val t = tagPool[i]
            if (liftedTag(t.s, lifted, cardOnMap)) { val sv = posed(c, t.s); tag(c, t.r, t.s.tag, t.gold, cornerOf(t.s, t.r)); c.restoreToCount(sv) }
        }
        tagN = 0
        // a refusal / a fight: the widget's edge red for FLASH_MS
        host.flash?.let { (s, at) ->
            val t = SystemClock.uptimeMillis() - at
            if (t in 0 until HomeEditModel.FLASH_MS) (if (s == EditStop.CAST) cast else listOfNotNull(wb[s])).forEach {
                val sv = posed(c, s)
                edgeColour(c, it, Color.rgb(220, 40, 30), (255 * (1f - t.toFloat() / HomeEditModel.FLASH_MS)).toInt())
                c.restoreToCount(sv)
            }
        }
    }
    /** the party list's box less its Select tab (its tag on the rows' end, clear of the tab on the screen's edge: PartyLayout's) */
    private fun partyRows(r: RectF): RectF {
        val inner = scratch3; inner.set(r)
        val w = width.toFloat().takeIf { it > 0f } ?: 1240f
        if (r.right > w - UiKit.MODULE_GAP - 8f) inner.right -= PartyLayout.TAB_W + PartyLayout.TAB_GAP
        else if (r.left < UiKit.MODULE_GAP + 8f) inner.left += PartyLayout.TAB_W + PartyLayout.TAB_GAP
        return inner
    }
    private val scratch3 = RectF()
    /** a tag drawn over the lifted widget (its own, or the card's riding the lifted map) */
    private fun liftedTag(s: EditStop, lifted: EditStop?, cardOnMap: Boolean) =
        s == lifted || (lifted == EditStop.MAP && s == EditStop.QUEST && cardOnMap)

    /** the well(s) [stop] would take in [l] */
    private fun wellFor(stop: EditStop, l: HomeLayout): List<RectF>? {
        val b = host.solve(l)
        fun r(x: HomeLayoutSolver.Box) = RectF(x.l, x.t, x.r, x.b)
        return when (stop) {
            EditStop.CHAT -> b[HomePart.CHAT]?.let { listOf(r(it)) }
            EditStop.MAP -> b[HomePart.MAP]?.let { listOf(r(it)) }
            EditStop.QUEST -> b[HomePart.QUESTS]?.let { listOf(r(it)) }
            EditStop.CAST -> listOfNotNull(b[HomePart.STRIP], b[HomePart.CAST2]).map { r(it) }
            EditStop.PARTY -> b[HomePart.PARTY]?.let { listOf(r(it)) }
            else -> null
        }
    }

    private fun dimRect(c: Canvas, r: RectF, keep: RectF?) {
        val s = c.save()
        if (keep != null) c.clipOutRect(keep)
        c.drawRoundRect(r, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        c.restoreToCount(s)
    }

    /** the soft dashed ink frame (a rider's lighter and finer) */
    private fun frame(c: Canvas, r: RectF, light: Boolean) {
        val o = scratch; o.set(r); o.inset(if (light) 4f else 2f, if (light) 4f else 2f)
        pLine.pathEffect = if (light) dashFine else dash
        pLine.strokeWidth = if (light) 2f else 3f
        pLine.color = Theme.bronze; pLine.alpha = if (light) 110 else 170
        c.drawRoundRect(o, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
        pLine.pathEffect = null
    }

    /** this frame's tags (a pool: the ticker draws every frame) */
    private class Tag { var s = EditStop.CHAT; val r = RectF(); var gold = false }
    private val tagPool = ArrayList<Tag>()
    private var tagN = 0
    private fun addTag(s: EditStop, r: RectF, gold: Boolean) {
        if (tagPool.size <= tagN) tagPool += Tag()
        val t = tagPool[tagN++]
        t.s = s; t.r.set(r); t.gold = gold
    }
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, BOTTOM_CENTRE, FOOT_LEFT, TOP_EDGE, TOP_EDGE_RIGHT }
    /** where each widget's tag stands (clear of what it shows) */
    private val CORNERS = mapOf(EditStop.CHAT to Corner.TOP_RIGHT, EditStop.MAP to Corner.BOTTOM_LEFT, EditStop.QUEST to Corner.TOP_RIGHT,
        EditStop.CAST to Corner.TOP_EDGE, EditStop.PARTY to Corner.TOP_RIGHT)

    /** fix round 1: where [s]'s tag stands on its box [r]: the chat's and the card's top right inside a wide column, ON the frame's top
     *  edge in a narrow one (a third: the card's title and the chat's lines reach that corner there; the tag must hide nothing) */
    fun cornerOf(s: EditStop, r: RectF): Corner {
        // a bar at the top of its column: its tag on its FOOT edge (the top edge is the band's foot 8 px over it)
        if (s == EditStop.CAST && host.model?.draft?.cast?.top == true) return Corner.FOOT_LEFT
        // the party list's inside its rows' top right at any width (its top edge may be the band's foot, 8 px over it)
        if (s == EditStop.PARTY) return Corner.TOP_RIGHT
        val at = CORNERS[s] ?: Corner.TOP_LEFT
        return if (at == Corner.TOP_RIGHT && r.width() < NARROW_TAG_W) Corner.TOP_EDGE_RIGHT else at
    }

    /** the widget's name on a small plate (gold on the selected one) at [at] */
    private fun tag(c: Canvas, r: RectF, word: String, gold: Boolean, at: Corner = Corner.TOP_LEFT) {
        if (word.isEmpty()) return
        val p = bodyP(TAG_SIZE, if (gold) Theme.gold else Theme.text)
        val t = Tr.t(word)
        val w = p.measureText(t) + 26f
        val x = when (at) {
            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> r.right - 12f - w
            Corner.TOP_EDGE_RIGHT -> r.right - 22f - w
            Corner.BOTTOM_CENTRE -> r.centerX() - w / 2f
            Corner.FOOT_LEFT, Corner.TOP_EDGE -> r.left + 22f
            else -> r.left + 12f
        }
        val y = when (at) {
            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> r.bottom - 12f - TAG_H
            Corner.BOTTOM_CENTRE -> r.bottom - 6f - TAG_H
            Corner.FOOT_LEFT -> r.bottom - TAG_H / 2f
            Corner.TOP_EDGE, Corner.TOP_EDGE_RIGHT -> r.top - TAG_H / 2f
            else -> r.top + 10f
        }
        val b = scratch; b.set(x, y, x + w, y + TAG_H)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 225
        c.drawRoundRect(b, 8f, 8f, pFill)
        pLine.strokeWidth = 2f; pLine.color = if (gold) Theme.gold else Theme.bronze; pLine.alpha = 255
        c.drawRoundRect(b, 8f, 8f, pLine)
        c.drawText(t, b.left + 13f, b.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    /**
     * THE GOLDEN BOX (the app's cursor, the user's ruling: "a thick gold box with a glow, never a gold outline"; visible at once): a wide
     * soft gold glow round the widget, a 7 px gold box, the gold washed a little into its edge
     */
    private fun goldBox(c: Canvas, r: RectF) {
        val o = scratch2; o.set(r); o.inset(-4f, -4f)
        pGlow.color = Theme.focusA(170); pGlow.strokeWidth = 16f
        c.drawRoundRect(o, UiKit.MODULE_R + 4f, UiKit.MODULE_R + 4f, pGlow)
        // the inner wash: a gold edge band fading inward
        val s = c.save()
        c.clipRect(r)
        pLine.strokeWidth = 22f; pLine.color = Theme.focusA(46)
        c.drawRoundRect(r, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
        c.restoreToCount(s)
        pLine.strokeWidth = 7f; pLine.color = Theme.focusA(255)
        c.drawRoundRect(o, UiKit.MODULE_R + 4f, UiKit.MODULE_R + 4f, pLine)
        pLine.strokeWidth = 2f; pLine.color = Color.argb(150, 90, 50, 0)
        scratch.set(o); scratch.inset(-4.5f, -4.5f)
        c.drawRoundRect(scratch, UiKit.MODULE_R + 8f, UiKit.MODULE_R + 8f, pLine)
    }

    /** the deep shadow under a lifted widget (its translationZ is above the others) */
    private fun shadow(c: Canvas, r: RectF, k: Float) {
        pShadow.color = Color.argb((140 * k).toInt(), 0, 0, 0)
        scratch.set(r); scratch.offset(10f, 16f)
        c.drawRoundRect(scratch, UiKit.MODULE_R, UiKit.MODULE_R, pShadow)
    }
    /** a frame's own box (the shadow, a frame's inset, a tag's plate, the gold box's rings): drawn at once, never kept */
    private val scratch = RectF()
    private val scratch2 = RectF()

    private fun edgeColour(c: Canvas, r: RectF, col: Int, alpha: Int) {
        pLine.strokeWidth = 6f; pLine.color = col; pLine.alpha = alpha
        c.drawRoundRect(RectF(r).apply { inset(-2f, -2f) }, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
    }

    /** the drop well: the dock filling gold (red when the drop would break a size rule) */
    private fun well(c: Canvas, r: RectF, bad: Boolean) {
        pFill.style = Paint.Style.FILL
        pFill.color = if (bad) Color.argb(70, 220, 40, 30) else Theme.focusA(60)
        c.drawRoundRect(r, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        pLine.pathEffect = dash; pLine.strokeWidth = 4f
        pLine.color = if (bad) Color.rgb(220, 40, 30) else Theme.focusA(230)
        c.drawRoundRect(RectF(r).apply { inset(3f, 3f) }, UiKit.MODULE_R, UiKit.MODULE_R, pLine)
        pLine.pathEffect = null
    }

    /** the divider's handle: a slim plate with three studs (gold under the cursor or a finger) */
    private fun handle(c: Canvas, r: RectF, lit: Boolean) {
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 235
        c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, pFill)
        pLine.strokeWidth = 3f; pLine.color = if (lit) Theme.focusA(255) else Theme.bronze; pLine.alpha = 255
        c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, pLine)
        pFill.color = if (lit) Theme.gold else Theme.bronze
        for (i in -1..1) c.drawCircle(r.centerX(), r.centerY() + i * 18f, 3.5f, pFill)
        if (lit) goldBox(c, RectF(r).apply { inset(-2f, -2f) })
    }

    fun removeChip(b: RectF): RectF = RectF(b.right - CHIP_R * 2f + 8f, b.top - 8f, b.right + 8f, b.top + CHIP_R * 2f - 8f)
    fun removePill(b: RectF): RectF {
        val x = removeChip(b)
        return RectF(x.left - 170f, x.top + 4f, x.left - 8f, x.bottom - 4f)
    }

    /** the red X chip (the kit's close button look) */
    private fun closeChip(c: Canvas, r: RectF) {
        UiKit.closeX(c, r.centerX(), r.centerY(), r.width())
    }

    private fun pill(c: Canvas, r: RectF, word: String) {
        pFill.style = Paint.Style.FILL; pFill.color = Color.rgb(120, 18, 12); pFill.alpha = 240
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pFill)
        pLine.strokeWidth = 2.5f; pLine.color = Theme.gold; pLine.alpha = 255
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pLine)
        val p = bodyP(26f, Color.WHITE, align = Paint.Align.CENTER)
        c.drawText(Tr.t(word), r.centerX(), r.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    // ---------------------------------------------------------------- the menu (over the OTHER column or area)

    /** a menu laid out: its box, each row's rect, which segmented rows wrap (label above, buttons below), the buttons' text size */
    private class MenuLay(val box: RectF, val rows: List<RectF>, val wrap: List<Boolean>, val segText: List<Float>,
                          /** a pencil's bubble hangs right under the band (its tail up to its part); false = a bar pushed it down */
                          val tail: Boolean = false)

    /** a segmented row's button widths at text size [ts] */
    private fun segWidths(r: MenuRow, ts: Float): List<Float> {
        val bp = bodyP(ts)
        return r.words.map { max(70f * ts / SEG_TEXT, bp.measureText(Tr.t(it)) + 34f * ts / SEG_TEXT) }
    }
    /** the width row [r] needs on ONE line (its label, the gap, its buttons) */
    private fun rowNeed(r: MenuRow): Float {
        val label = bodyP(28f).measureText(Tr.t(r.label)) + 18f + 24f
        return when (r.kind) {
            MenuRow.Kind.SEG -> label + segWidths(r, SEG_TEXT).sum() + 6f * (r.words.size - 1) + 10f
            MenuRow.Kind.STEP -> label + 2 * 64f + 150f + 10f
            MenuRow.Kind.BUTTON -> (if (r.label != r.words.first()) label else 0f) + 300f + 10f
        }
    }

    /**
     * where [s]'s menu opens and how its rows lie: over the other column (the band's and the clock's over the columns, the bar's over the
     * columns above it). A column menu is at least [MENU_MIN_W] wide (narrower, it overhangs its own widget's column towards the middle)
     * and ends at its HOST column's bottom; it, the band's and the clock's never lie on a tap-to-cast bar they cross ([clearBars]: under a
     * top bar, over a foot bar, whichever column it is in); a row whose words do not fit on one line puts its
     * buttons under its label (the buttons' text smaller only if even that line is too short); rows tighten when the column is short.
     */
    private fun menuLay(s: EditStop, rows: List<MenuRow>): MenuLay {
        val p = host.parts()
        val w = width.toFloat(); val h = height.toFloat()
        val g = UiKit.MODULE_GAP
        val chat = p[HomePart.CHAT]; val map = p[HomePart.MAP]
        val bars = p[HomePart.BARS]
        // (Top screen's preview: the band laid above the page - the page's own top)
        val top = max(bars?.bottom ?: 226f, 0f) + g
        val cardHost = if (host.model?.draft?.card == HomeLayout.CardDock.ABOVE_CHAT) map else chat
        val column = s == EditStop.CHAT || s == EditStop.MAP || s == EditStop.QUEST || s == EditStop.PARTY
        val bubbleOf = if (s.pencil) bandBox(s) else null
        // the party list's over the column it is not in
        val partyHost = if (host.model?.draft?.party?.column == HomeModule.MAP) chat else map
        val hostCol = when (s) { EditStop.CHAT -> map; EditStop.MAP -> chat; EditStop.QUEST -> cardHost; EditStop.PARTY -> partyHost; else -> null }
        val topBar = topCastBox(p)
        // the horizontal extent and the room it has vertically
        var box: RectF
        var tail = false
        when {
            column -> {
                box = hostCol?.let { RectF(it.left, top, it.right, it.bottom) } ?: RectF(g, top, w / 2f, h - g)
                if (box.width() < MENU_MIN_W) {
                    if (box.centerX() < w / 2f) box.right = min(w - g, box.left + MENU_MIN_W) else box.left = max(g, box.right - MENU_MIN_W)
                }
                clearBars(box, p, top, g)
            }
            // P1 / P4: a pencil's speech bubble hangs under the band from its part (its tail up to the pencil), 16 px clear of the screen's
            // edges, never on a tap-to-cast bar it crosses
            bubbleOf != null -> {
                val bw = min(BUBBLE_W, w - 2f * BUBBLE_EDGE)
                val cx = bubbleOf.centerX().coerceIn(BUBBLE_EDGE + bw / 2f, w - BUBBLE_EDGE - bw / 2f)
                // (under the band - or under its pencil when that stands lower: Top screen's preview, the band above the page)
                box = RectF(cx - bw / 2f, max(top, bubbleOf.bottom + 4f) + BUBBLE_TAIL, cx + bw / 2f, h - g)
                val hung = box.top
                // fix round 3 (the review: the 640 px bubble overhangs the other column by about 12 px, so a top bar THERE pushed it about
                // 140 px down, tailless, over its own empty column): a bar wholly to one side of the pencil narrows the bubble to end a gap
                // short of it while the bubble stays MENU_MIN_W wide; only a bar it still crosses moves it ([clearBars])
                val ax = bubbleOf.centerX()
                for (b in castNow(p)) {
                    if (b.left >= box.right || box.left >= b.right) continue
                    if (b.left > ax && b.left - g - box.left >= MENU_MIN_W) box.right = b.left - g
                    else if (b.right < ax && box.right - (b.right + g) >= MENU_MIN_W) box.left = b.right + g
                }
                clearBars(box, p, top, g)
                // fix round (the review: under a tap-to-cast bar at a column's top the tail landed on that bar): a bubble a bar pushed down
                // has no tail - it would point at the bar, not at the D-pad or the buttons
                tail = box.top == hung
            }
            // the bar's menu over the columns above it; a top bar's opens BELOW it, down to the page's foot
            s == EditStop.CAST -> box = if (topBar != null) RectF(g + 100f, topBar.bottom + g, w - g - 100f, h - g) else {
                val bt = castNow(p).minOfOrNull { it.top } ?: (h - g)
                RectF(g + 100f, top, w - g - 100f, bt - g)
            }
            else -> box = RectF(w / 2f - 380f, top, w / 2f + 380f, h - g).also { clearBars(it, p, top, g) }
        }
        val avail = box.bottom - box.top
        val rowW = box.width() - 32f
        // a segmented row: one line at the full text size; else one line a little smaller (down to SEG_TEXT_MIN); else wrapped
        fun segNeed(r: MenuRow, ts: Float) = segWidths(r, ts).sum() + 6f * (r.words.size - 1)
        val wrap = ArrayList<Boolean>(); val segText = ArrayList<Float>()
        for (r in rows) {
            if (r.kind != MenuRow.Kind.SEG) { wrap += false; segText += SEG_TEXT; continue }
            val room = rowW - (rowNeed(r) - segNeed(r, SEG_TEXT))
            when {
                segNeed(r, SEG_TEXT) <= room -> { wrap += false; segText += SEG_TEXT }
                segNeed(r, SEG_TEXT_MIN) <= room -> {
                    var ts = SEG_TEXT
                    while (ts > SEG_TEXT_MIN && segNeed(r, ts) > room) ts -= 0.5f
                    wrap += false; segText += ts
                }
                else -> {
                    wrap += true
                    val line = rowW - 20f
                    segText += if (segNeed(r, SEG_TEXT) <= line) SEG_TEXT else max(18f, SEG_TEXT * line / segNeed(r, SEG_TEXT))
                }
            }
        }
        val unit = wrap.map { if (it) MENU_ROW * WRAP_K else MENU_ROW }
        val sum = unit.sum()
        // short of room: the rows tighten (to MENU_K_MIN); past that the menu runs on below its column (the words stay readable)
        val k = if (MENU_HEAD + sum + 24f <= avail || sum <= 0f) 1f else ((avail - MENU_HEAD - 24f) / sum).coerceIn(MENU_K_MIN, 1f)
        val boxH = MENU_HEAD + sum * k + 24f
        box = when (s) {
            EditStop.CAST -> if (topBar != null) RectF(box.left, box.top, box.right, box.top + boxH)
                else RectF(box.left, max(top, box.bottom - boxH), box.right, box.bottom)
            else -> RectF(box.left, box.top, box.right, box.top + boxH)
        }
        val out = ArrayList<RectF>()
        var y = box.top + MENU_HEAD
        for (u in unit) { out += RectF(box.left + 16f, y, box.right - 16f, y + u * k - 10f); y += u * k }
        return MenuLay(box, out, wrap, segText, tail)
    }
    /**
     * fix round 2: a menu box never lies on a tap-to-cast bar its FINAL width crosses (a column menu widened to MENU_MIN_W reaches into
     * the other column; the band's and the clock's are centred): a bar at a column's top ([top] = the columns' top) - the menu starts
     * under it; a bar at a column's foot - it ends over it
     */
    private fun clearBars(box: RectF, p: Map<HomePart, RectF>, top: Float, g: Float) {
        val bars = castNow(p)
        for (i in bars.indices) {
            val b = bars[i]
            if (b.left >= box.right || box.left >= b.right) continue
            if (b.top <= top + 1f) box.top = max(box.top, b.bottom + g) else box.bottom = min(box.bottom, b.top - g)
        }
    }

    /** where [s]'s menu opens with its rows now (the tests' and the shots' reading of [menuLay]) */
    fun menuBox(s: EditStop): RectF = menuLay(s, host.menuRows(s)).box
    /** [s]'s bubble tail as it is drawn now (its tip's box, the Strip-free Home px), null = none (no pencil, or a bar pushed the bubble) */
    fun bubbleTailBox(s: EditStop): RectF? {
        if (!s.pencil) return null
        val lay = menuLay(s, host.menuRows(s))
        if (!lay.tail) return null
        val ax = bubbleTailX(s, lay.box)
        return RectF(ax - 16f, lay.box.top - BUBBLE_TAIL + 2f, ax + 16f, lay.box.top + 2f)
    }

    /** a pencil's bubble: its tail's x on the bubble's top (under its pencil, inside the bubble's straight edge) */
    private fun bubbleTailX(s: EditStop, box: RectF): Float =
        (bandBox(s)?.centerX() ?: box.centerX()).coerceIn(box.left + UiKit.MODULE_R + 16f, box.right - UiKit.MODULE_R - 16f)

    /** the speech bubble's tail: from the bubble's top edge up towards its pencil ([BUBBLE_TAIL] tall), the bubble's own ink and edge */
    private fun bubbleTail(c: Canvas, box: RectF, ax: Float) {
        path.reset()
        path.moveTo(ax - 16f, box.top + 2f); path.lineTo(ax, box.top - BUBBLE_TAIL + 2f); path.lineTo(ax + 16f, box.top + 2f); path.close()
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 245
        c.drawPath(path, pFill)
        pLine.strokeWidth = 3f; pLine.color = Theme.bronze; pLine.alpha = 255; pLine.pathEffect = null
        c.drawLine(ax - 16f, box.top + 1f, ax, box.top - BUBBLE_TAIL + 2f, pLine)
        c.drawLine(ax, box.top - BUBBLE_TAIL + 2f, ax + 16f, box.top + 1f, pLine)
    }

    private fun drawMenu(c: Canvas, m: HomeEditModel) {
        val s = m.menu ?: return
        val rows = host.menuRows(s)
        val lay = menuLay(s, rows)
        val box = lay.box
        menuBoxes.clear(); menuRowBoxes.clear()
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 245
        c.drawRoundRect(box, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        UiKit.module(c, box)
        if (s.pencil && lay.tail) bubbleTail(c, box, bubbleTailX(s, box))
        val tp = titleP(40f, Theme.gold)
        c.drawText(Tr.t(HomeWidgetMenus.title(s)), box.left + 28f, box.top + 56f, tp)
        UiKit.paneRule(c, box.left + 20f, box.top + MENU_HEAD - 12f, box.right - 20f)
        for ((i, r) in rows.withIndex()) {
            val rr = lay.rows[i]
            pFill.color = Theme.raised; pFill.alpha = 220
            c.drawRoundRect(rr, 8f, 8f, pFill)
            val lp = bodyP(28f, if (r.enabled) Theme.text else Theme.dim)
            val btns = ArrayList<RectF>()
            // a wrapped row: the label on its upper line, the buttons along its foot
            val btnH = if (lay.wrap[i]) min(58f, rr.height() * 0.5f) else rr.height() - 16f
            val btnTop = rr.bottom - 8f - btnH
            val labelY = if (lay.wrap[i]) (rr.top + btnTop) / 2f else rr.centerY()
            when (r.kind) {
                MenuRow.Kind.BUTTON -> {
                    // a row named apart from its button shows its name as the other rows do
                    if (r.label != r.words.first()) c.drawText(Tr.t(r.label), rr.left + 18f, labelY - (lp.ascent() + lp.descent()) / 2f, lp)
                    val b = RectF(rr.right - 300f, btnTop, rr.right - 10f, btnTop + btnH)
                    UiKit.button(c, b, r.words.first(), UiKit.okGlyph())
                    btns += b
                }
                MenuRow.Kind.STEP -> {
                    c.drawText(Tr.t(r.label), rr.left + 18f, labelY - (lp.ascent() + lp.descent()) / 2f, lp)
                    val bw = 64f
                    val plus = RectF(rr.right - 10f - bw, btnTop, rr.right - 10f, btnTop + btnH)
                    val minus = RectF(plus.left - 150f - bw, plus.top, plus.left - 150f, plus.bottom)
                    // fix round 3: the app's steppers' look (Settings > Bottom screen's Zoom): neither plate lit as a pick, the signs big
                    UiKit.choice(c, minus, r.words[0], false, textSize = STEP_TEXT); UiKit.choice(c, plus, r.words[1], false, textSize = STEP_TEXT)
                    val vp = numberP(30f, Theme.text, align = Paint.Align.CENTER)
                    c.drawText(r.value ?: "", (minus.right + plus.left) / 2f, plus.centerY() - (vp.ascent() + vp.descent()) / 2f, vp)
                    btns += minus; btns += plus
                }
                MenuRow.Kind.SEG -> {
                    c.drawText(Tr.t(r.label), rr.left + 18f, labelY - (lp.ascent() + lp.descent()) / 2f, lp)
                    val ts = lay.segText[i]
                    val sizes = segWidths(r, ts)
                    var x = rr.right - 10f - sizes.sum() - 6f * (sizes.size - 1)
                    for ((k, word) in r.words.withIndex()) {
                        val b = RectF(x, btnTop, x + sizes[k], btnTop + btnH)
                        UiKit.choice(c, b, word, k == r.picked, r.enabled && k !in r.off, textSize = ts)
                        btns += b
                        x += sizes[k] + 6f
                    }
                }
            }
            if (i == m.menuRow) UiKit.focus(c, rr)
            menuBoxes += btns
            menuRowBoxes += rr
        }
        lastMenuBox.set(box)
    }
    /** the last drawn menu's buttons per row (touch) */
    private val menuBoxes = ArrayList<List<RectF>>()
    private val menuRowBoxes = ArrayList<RectF>()
    private val lastMenuBox = RectF()

    // ---------------------------------------------------------------- the Add tray ("+")

    private fun trayBoxes(m: HomeEditModel): List<RectF> {
        val w = width.toFloat(); val h = height.toFloat()
        val n = m.removed.size
        val bw = 520f; val bh = 84f
        val top = h - 40f - n * (bh + 16f) - 90f
        return (0 until n).map { i -> RectF(w / 2f - bw / 2f, top + 80f + i * (bh + 16f), w / 2f + bw / 2f, top + 80f + i * (bh + 16f) + bh) }
    }

    private fun drawTray(c: Canvas, m: HomeEditModel) {
        val boxes = trayBoxes(m)
        if (boxes.isEmpty()) return
        val panel = RectF(boxes.first().left - 30f, boxes.first().top - 76f, boxes.first().right + 30f, boxes.last().bottom + 26f)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 245
        c.drawRoundRect(panel, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        UiKit.module(c, panel)
        c.drawText(Tr.t("Add"), panel.left + 24f, panel.top + 52f, titleP(40f, Theme.gold))
        for ((i, s) in m.removed.withIndex()) {
            val b = boxes[i]
            UiKit.button(c, b, if (s == EditStop.CAST) "Tap-to-cast bar" else "Quest card", UiKit.okGlyph())
            if (i == m.sheetSel) UiKit.focus(c, b)
        }
        trayHit.clear(); trayHit.addAll(boxes)
    }
    private val trayHit = ArrayList<RectF>()

    // ---------------------------------------------------------------- the room bar's tray of hidden rooms (its "+" spot)

    /** the tray laid out (this view's px): its panel, each row's box, the "+" spot's centre x it rises from */
    private class RoomTray(val panel: RectF, val rows: List<RectF>, val ax: Float)

    /**
     * the tray of the hidden rooms over Home's foot, rising from the bar's "+" spot: right-aligned to it, 16 px clear of the screen's
     * edges, one row each (the room's bar picture and its name; a second column past [TRAY_ROWS]); null = nothing hidden / no "+"
     */
    private fun roomTray(m: HomeEditModel): RoomTray? {
        val names = m.hiddenRooms
        if (names.isEmpty()) return null
        val w = width.toFloat().takeIf { it > 0f } ?: 1240f
        val h = height.toFloat().takeIf { it > 0f } ?: 935f
        val plus = host.bar()?.plusBox()
        val ax = plus?.centerX() ?: (w - 60f)
        val cols = if (names.size > TRAY_ROWS) 2 else 1
        val rowsN = (names.size + cols - 1) / cols
        val colW = TRAY_W
        val pw = cols * colW + (cols - 1) * 12f + 32f
        val avail = h - 16f - TRAY_HEAD - 20f
        val rowH = min(TRAY_ROW, (avail / rowsN) - 10f).coerceAtLeast(56f)
        val ph = TRAY_HEAD + rowsN * (rowH + 10f) + 10f
        val right = min(w - 16f, (plus?.right ?: (w - 16f)) + 4f)
        val left = max(16f, right - pw)
        val bottom = h - 8f
        val panel = RectF(left, bottom - ph, left + pw, bottom)
        val rows = names.indices.map { i ->
            val col = i / rowsN; val row = i % rowsN
            val x = panel.left + 16f + col * (colW + 12f)
            val y = panel.top + TRAY_HEAD + row * (rowH + 10f)
            RectF(x, y, x + colW, y + rowH)
        }
        return RoomTray(panel, rows, ax.coerceIn(panel.left, panel.right))
    }

    /** the tray's box (the tests' and the shots' reading; null = not open) */
    fun roomTrayBox(): RectF? = host.model?.takeIf { it.sheet == HomeEditModel.Sheet.ROOMS }?.let { roomTray(it)?.panel?.let { p -> RectF(p) } }

    private fun drawRoomTray(c: Canvas, m: HomeEditModel, lay: RoomTray?) {
        trayHit.clear()
        if (lay == null) return
        val panel = lay.panel
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 245
        c.drawRoundRect(panel, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        UiKit.module(c, panel)
        c.drawText(Tr.t("Add"), panel.left + 24f, panel.top + 52f, titleP(40f, Theme.gold))
        val bar = host.bar()
        val names = m.hiddenRooms
        for ((i, n) in names.withIndex()) {
            val b = lay.rows[i]
            pFill.color = Theme.raised; pFill.alpha = 230
            c.drawRoundRect(b, 8f, 8f, pFill)
            pLine.strokeWidth = 2f; pLine.color = Theme.bronze; pLine.alpha = 255
            c.drawRoundRect(b, 8f, 8f, pLine)
            val pic = b.height() - 12f
            val room = Room.values().firstOrNull { it.name == n }
            if (room != null && bar != null) bar.drawRoomPicture(c, room, b.left + 10f + pic / 2f, b.centerY(), pic)
            val tp = bodyP(28f, Theme.text)
            c.drawText(Tr.t(RoomBar.word(n)), b.left + 22f + pic, b.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
            if (i == m.sheetSel) UiKit.focus(c, b)
        }
        trayHit.addAll(lay.rows)
    }

    // ---------------------------------------------------------------- the top bar's pieces (P1 - P4)

    /**
     * THE TOP BAR'S PIECES (the user's rulings 2026-11-22): the D-pad's and the ABXY's pencils (still: no wiggle), the Look chip (a thin
     * row's pictogram or two diamonds - the look a press gives), the fight pencil while the preview shows, each extra's "-" badge and the
     * extras' "+" spot (those wiggle with their extra: [Host.extraPose]), the golden cursor on the piece under it, a refusal's red pulse.
     * Nothing while parked; nothing of the band itself is dimmed, framed or moved
     */
    private fun drawBandPieces(c: Canvas, m: HomeEditModel) {
        if (m.parked) return
        val g = host.band() ?: return
        val free = m.menu == null && m.sheet == null && m.lifted == null
        pencil(c, g.padPen, m.menu == EditStop.PAD_PEN)
        pencil(c, g.facePen, m.menu == EditStop.FACE_PEN)
        g.fightPen?.let { pencil(c, it, m.menu == EditStop.FIGHT_PEN, fight = true) }
        g.look?.let { lookChip(c, it, if (g.diamonds) HomeRest.DIAMONDS else HomeRest.ROW) }
        for ((k, r) in g.extras) {
            val sv = c.save()
            if (host.extraPose(k, mtx)) c.concat(mtx)
            // the gold line's badge at its left end (its top left is the battery's line), a cell's on its top left
            if (k == "gold") minusBadge(c, r.left - EXTRA_BADGE_R - 2f, r.centerY()) else minusBadge(c, r.left + BADGE_IN, r.top + BADGE_IN)
            c.restoreToCount(sv)
        }
        g.plus?.let { r ->
            val sv = c.save()
            if (host.extraPose("plus", mtx)) c.concat(mtx)
            // the readability pass: on the cell's picture square (the blank frame the clock draws there), not the cell's middle
            val pic = extraPicture(EXTRA_PLUS, r)
            plusSpot(c, pic.centerX(), pic.centerY(), min(pic.width(), pic.height()))
            c.restoreToCount(sv)
        }
        val cur = m.cursor
        if (cur.band && (free || m.menu == cur || m.sheet == HomeEditModel.Sheet.EXTRAS)) bandBox(cur)?.let { b ->
            val box = scratch2; box.set(b); box.inset(-6f, -6f)
            UiKit.focus(c, box)
        }
        host.flash?.let { (s, at) ->
            val t = SystemClock.uptimeMillis() - at
            if (s.band && t in 0 until HomeEditModel.FLASH_MS) bandBox(s)?.let {
                edgeColour(c, it, Color.rgb(220, 40, 30), (255 * (1f - t.toFloat() / HomeEditModel.FLASH_MS)).toInt())
            }
        }
    }

    /** a pencil badge (P1, drawn in vectors: no pencil art in the kit): an ink disc in a bronze ring (gold while its bubble is open), a
     *  cream pencil across it; [fight] = the fight look's: the readability pass (the shots: it read as a third copy of the other two,
     *  only its tip red) - a fight-red disc and ring, the pencil's tip red too, so it reads as the fight's own at a glance */
    private fun pencil(c: Canvas, r: RectF, open: Boolean, fight: Boolean = false) {
        val cx = r.centerX(); val cy = r.centerY(); val rad = min(r.width(), r.height()) / 2f
        pFill.style = Paint.Style.FILL; pFill.color = if (fight) FIGHT_INK else Theme.ink; pFill.alpha = 235
        c.drawCircle(cx, cy, rad, pFill)
        pLine.pathEffect = null; pLine.strokeWidth = if (fight) 3f else 2.5f
        pLine.color = if (open) Theme.gold else if (fight) FIGHT_RED else Theme.bronze; pLine.alpha = 255
        c.drawCircle(cx, cy, rad - 1.5f, pLine)
        val sv = c.save()
        c.rotate(-45f, cx, cy)
        val len = rad * 1.15f; val th = rad * 0.30f
        // the body, the ferrule, the sharpened tip and its lead
        pFill.color = Theme.text; pFill.alpha = 255
        scratch.set(cx - len / 2f + th, cy - th / 2f, cx + len / 2f, cy + th / 2f); c.drawRect(scratch, pFill)
        pFill.color = if (fight) FIGHT_RED else Theme.gold
        scratch.set(cx + len / 2f - th * 0.9f, cy - th / 2f, cx + len / 2f, cy + th / 2f); c.drawRect(scratch, pFill)
        path.reset(); path.moveTo(cx - len / 2f + th, cy - th / 2f); path.lineTo(cx - len / 2f, cy); path.lineTo(cx - len / 2f + th, cy + th / 2f); path.close()
        pFill.color = Theme.bronze; c.drawPath(path, pFill)
        pFill.color = Theme.ink; c.drawCircle(cx - len / 2f + th * 0.25f, cy, th * 0.18f, pFill)
        c.restoreToCount(sv)
    }

    /**
     * the Look chip (P3): a raised plate split in two like the bubbles' own two-word switch (Rest controller: Thin row / Diamonds) - a thin
     * row's pictogram on its left, two diamonds on its right, the look the band has [now] lit (gold on a gold wash), the other dim. The
     * readability pass (the shots: a plate of dots did not read as a switch): both looks shown, so it reads as "this or that", no text
     */
    private fun lookChip(c: Canvas, r: RectF, now: HomeRest) {
        pFill.style = Paint.Style.FILL; pFill.color = Theme.raised; pFill.alpha = 240
        c.drawRoundRect(r, 8f, 8f, pFill)
        val mid = r.centerX()
        // the lit half's wash
        val litRow = now == HomeRest.ROW
        scratch.set(if (litRow) r.left else mid, r.top, if (litRow) mid else r.right, r.bottom); scratch.inset(3f, 3f)
        pFill.color = Theme.gold; pFill.alpha = 70
        c.drawRoundRect(scratch, 6f, 6f, pFill)
        pLine.pathEffect = null; pLine.strokeWidth = 2f; pLine.color = Theme.bronze; pLine.alpha = 255
        c.drawRoundRect(r, 8f, 8f, pLine)
        pLine.strokeWidth = 1.5f; pLine.alpha = 200
        c.drawLine(mid, r.top + 6f, mid, r.bottom - 6f, pLine)
        val cy = r.centerY(); val half = r.width() / 2f
        // the thin row: four buttons in a row
        pFill.color = if (litRow) Theme.gold else Theme.dim; pFill.alpha = 255
        val n = 4; val d = (half - 8f) / n; val sq = min(d * 0.72f, r.height() * 0.30f)
        for (i in 0 until n) { val x = r.left + 4f + d * (i + 0.5f); scratch.set(x - sq / 2f, cy - sq / 2f, x + sq / 2f, cy + sq / 2f); c.drawRoundRect(scratch, 1.5f, 1.5f, pFill) }
        // the diamonds: two of four
        pFill.color = if (litRow) Theme.dim else Theme.gold
        val ds = min(r.height() * 0.065f, half * 0.06f)
        for (side in 0..1) {
            val gx = mid + half * (if (side == 0) 0.28f else 0.72f)
            for ((dx, dy) in DIAMOND) c.drawCircle(gx + dx * ds * 2f, cy + dy * ds * 2f, ds, pFill)
        }
    }

    /** an extra's "-" badge (the room bar's look: an ink disc, a bronze ring, a cream bar) */
    private fun minusBadge(c: Canvas, bx: Float, by: Float) {
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 255
        c.drawCircle(bx, by, EXTRA_BADGE_R, pFill)
        pLine.strokeWidth = 2f; pLine.color = Theme.bronze; pLine.alpha = 255; pLine.pathEffect = null
        c.drawCircle(bx, by, EXTRA_BADGE_R - 1f, pLine)
        pFill.color = Theme.text
        scratch2.set(bx - EXTRA_BADGE_R * 0.5f, by - 1.8f, bx + EXTRA_BADGE_R * 0.5f, by + 1.8f)
        c.drawRoundRect(scratch2, 1.8f, 1.8f, pFill)
    }

    /** the extras' "+" spot: an empty picture slot (the readability pass: a rounded square over the blank frame the clock draws, the
     *  same size as the pictures beside it - the circle left the frame's corners showing under it), a dashed bronze edge, a gold "+" */
    private fun plusSpot(c: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.5f
        scratch2.set(cx - r, cy - r, cx + r, cy + r)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.raised; pFill.alpha = 255
        c.drawRoundRect(scratch2, 4f, 4f, pFill)
        pLine.pathEffect = dashPlus; pLine.strokeWidth = 2f; pLine.color = Theme.bronze; pLine.alpha = 255
        scratch2.inset(1f, 1f)
        c.drawRoundRect(scratch2, 3f, 3f, pLine)
        pLine.pathEffect = null
        pFill.color = Theme.gold
        val arm = r * 0.5f; val th = 2.6f
        scratch2.set(cx - arm, cy - th, cx + arm, cy + th); c.drawRoundRect(scratch2, th, th, pFill)
        scratch2.set(cx - th, cy - arm, cx + th, cy + arm); c.drawRoundRect(scratch2, th, th, pFill)
    }

    /** the extras' tray laid out (Home's px): its panel, each row's box, the "+" spot's centre x it pops out of */
    private class ExtraTray(val panel: RectF, val rows: List<RectF>, val ax: Float)

    /** P2: the tray of the hidden extras under the band, under the extras' "+" spot (16 px clear of the screen's edges, never on a
     *  tap-to-cast bar it crosses), one row each (its Settings icon and name); null = nothing hidden */
    private fun extraTray(m: HomeEditModel): ExtraTray? {
        val keys = m.extrasOff
        if (keys.isEmpty()) return null
        val w = width.toFloat().takeIf { it > 0f } ?: 1240f
        val h = height.toFloat().takeIf { it > 0f } ?: 935f
        val p = host.parts()
        val g = host.band()
        val ax = g?.plus?.centerX() ?: (w / 2f)
        val top0 = (p[HomePart.BARS]?.bottom ?: 226f) + UiKit.MODULE_GAP
        val pw = TRAY_W + 32f
        val left = (ax - pw / 2f).coerceIn(16f, w - 16f - pw)
        val probe = RectF(left, top0, left + pw, h - UiKit.MODULE_GAP)
        clearBars(probe, p, top0, UiKit.MODULE_GAP)
        val rowH = min(TRAY_ROW, (probe.height() - TRAY_HEAD - 10f) / keys.size - 10f).coerceAtLeast(56f)
        val panel = RectF(left, probe.top, left + pw, probe.top + TRAY_HEAD + keys.size * (rowH + 10f) + 10f)
        val rows = keys.indices.map { i -> val y = panel.top + TRAY_HEAD + i * (rowH + 10f); RectF(panel.left + 16f, y, panel.right - 16f, y + rowH) }
        return ExtraTray(panel, rows, ax.coerceIn(panel.left, panel.right))
    }

    /** the extras' tray's box (the tests' and the shots' reading; null = not open) */
    fun extraTrayBox(): RectF? = host.model?.takeIf { it.sheet == HomeEditModel.Sheet.EXTRAS }?.let { extraTray(it)?.panel?.let { p -> RectF(p) } }

    private fun drawExtraTray(c: Canvas, m: HomeEditModel, lay: ExtraTray?) {
        trayHit.clear()
        if (lay == null) return
        val panel = lay.panel
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 245
        c.drawRoundRect(panel, UiKit.MODULE_R, UiKit.MODULE_R, pFill)
        UiKit.module(c, panel)
        c.drawText(Tr.t("Add"), panel.left + 24f, panel.top + 52f, titleP(40f, Theme.gold))
        for ((i, k) in m.extrasOff.withIndex()) {
            val b = lay.rows[i]
            pFill.color = Theme.raised; pFill.alpha = 230
            c.drawRoundRect(b, 8f, 8f, pFill)
            pLine.strokeWidth = 2f; pLine.color = Theme.bronze; pLine.alpha = 255
            c.drawRoundRect(b, 8f, 8f, pLine)
            val line = BottomTiles.CLOCK_LINES.firstOrNull { it.first == k }
            val pic = b.height() - 20f
            val ib = scratch2; ib.set(b.left + 12f, b.centerY() - pic / 2f, b.left + 12f + pic, b.centerY() + pic / 2f)
            val icon = line?.let { Icons.get(it.third) }
            if (icon != null) c.drawBitmap(icon, null, ib, null)
            else { pFill.color = Theme.bronze; pFill.alpha = 140; c.drawRoundRect(ib, 4f, 4f, pFill) }
            val tp = bodyP(28f, Theme.text)
            c.drawText(Tr.t(line?.second ?: k), ib.right + 14f, b.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
            if (i == m.sheetSel) UiKit.focus(c, b)
        }
        trayHit.addAll(lay.rows)
    }

    // ---------------------------------------------------------------- the edit ribbon (in the XP bar's place, drawn by the Strip)

    /** the ribbon's pieces (the Strip's px): "+" at its left (drawn only while something is off Home), the Fight toggle left of the
     *  disc (P4), Done at its right; their touch boxes [plusHit] / [fightHit] / [doneHit] are [TOUCH_MIN] square at least (the review:
     *  the ribbon is only [Strip.TRACKS] tall, the Strip's top edge - a down just below it over "+" or Done, the rooms' row's top
     *  [Strip.RIBBON_SLOP], is the ribbon's too) */
    class Ribbon(val plus: RectF, val done: RectF, val fight: RectF) {
        val plusHit: RectF get() = grown(plus)                           // made on a touch only (the ribbon is made every frame)
        val doneHit: RectF get() = grown(done)
        val fightHit: RectF get() = grown(fight)
        private fun grown(r: RectF): RectF {
            val dx = max(8f, (TOUCH_MIN - r.width()) / 2f)
            return RectF(r.left - dx, 0f, r.right + dx, max(r.bottom + 6f, TOUCH_MIN))
        }
    }

    /** the ribbon's pieces in the Strip's px (its top [Strip.TRACKS]) for a bar [w] wide */
    fun ribbon(w: Float): Ribbon {
        val h = Strip.TRACKS.toFloat()
        val plus = RectF(14f, 2f, 14f + 46f, h - 2f)
        val tp = bodyP(RIBBON_TEXT)
        val dw = max(DONE_MIN_W, max(tp.measureText(Tr.t("Done")), tp.measureText(Tr.t("Discard"))) + GLYPH + DONE_PAD)
        val done = RectF(w - 10f - dw, 2f, w - 10f, h - 2f)
        // P4: the Fight toggle. Fix round (the review: right of the disc it took about 130 px of the hints, so "B Put back" - and in French
        // a bubble's "B Back" - no longer fitted): just LEFT of the disc's gap, where the ribbon has nothing but "+" at its far left - still
        // left of the hints, and the ribbon row reads "+", Fight, the disc, Done left to right as the controller walks it
        val fw = max(FIGHT_MIN_W, tp.measureText(Tr.t("Fight")) + 2f * FIGHT_DOT + 34f)
        val f1 = w / 2f - Strip.GAP_HALF - HINT_GAP
        val fight = RectF(max(plus.right + HINT_GAP + 8f, f1 - fw), 2f, f1, h - 2f)
        return Ribbon(plus, done, fight)
    }

    /** the Fight toggle: a raised plate, a dot (gold while the preview shows) and its word (gold while on) */
    private fun drawFight(c: Canvas, b: RectF, h: Float, on: Boolean) {
        pFill.style = Paint.Style.FILL; pFill.color = if (on) Theme.gold else Theme.raised; pFill.alpha = if (on) 90 else 255
        c.drawRoundRect(b, 6f, 6f, pFill)
        if (on) { pFill.color = Theme.raised; pFill.alpha = 200; scratch.set(b); scratch.inset(3f, 3f); c.drawRoundRect(scratch, 5f, 5f, pFill) }
        pLine.strokeWidth = 2f; pLine.color = if (on) Theme.gold else Theme.bronze; pLine.alpha = 255; pLine.pathEffect = null
        c.drawRoundRect(b, 6f, 6f, pLine)
        val tp = bodyP(RIBBON_TEXT, if (on) Theme.gold else Theme.text, align = Paint.Align.LEFT)
        val word = Tr.t("Fight")
        val tw = tp.measureText(word)
        val x0 = b.centerX() - (2f * FIGHT_DOT + 8f + tw) / 2f
        pFill.color = if (on) Theme.gold else Theme.dim; pFill.alpha = 255
        c.drawCircle(x0 + FIGHT_DOT, h / 2f, FIGHT_DOT, pFill)
        pLine.color = Theme.bronze; c.drawCircle(x0 + FIGHT_DOT, h / 2f, FIGHT_DOT, pLine)
        c.drawText(word, x0 + 2f * FIGHT_DOT + 8f, h / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
    }

    /** the ribbon's slide (1 = up over the XP track; the Strip draws the track under it while it is below 1) */
    fun ribbonK(): Float = host.popK(HomeEditMotion.RIBBON)
    /** a room's pose on the bar while editing (the Strip: scale, turn, dx, dy into [out]); false = none */
    fun roomPose(r: Room, out: FloatArray): Boolean = host.roomPose(r, out)
    /** the bar's "+" spot's pose (its own wiggle), as [roomPose] */
    fun plusPose(out: FloatArray): Boolean = host.plusPose(out)

    /** the Strip draws the ribbon in its tracking bar's place ([top] its top, [g0] .. [g1] the disc's gap) */
    fun drawRibbon(c: Canvas, w: Float, top: Float, g0: Float, g1: Float) {
        val m = host.model ?: return
        val a = host.chromeAlpha
        if (a <= 0.01f) return
        val h = Strip.TRACKS.toFloat()
        val save = c.saveLayerAlpha(0f, top, w, top + h, (255 * a).toInt())
        // it slides up over the XP track as edit mode opens (the Strip draws the track under it meanwhile) and down as it leaves
        c.translate(0f, top + (1f - host.popK(HomeEditMotion.RIBBON)) * h)
        pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = 255
        c.drawRect(0f, 0f, w, h, pFill)
        pLine.strokeWidth = 2f; pLine.color = Theme.gold; pLine.alpha = 200
        c.drawLine(0f, 1f, g0, 1f, pLine); c.drawLine(g1, 1f, w, 1f, pLine)
        c.drawLine(0f, h - 1f, g0, h - 1f, pLine); c.drawLine(g1, h - 1f, w, h - 1f, pLine)
        val r = ribbon(w)
        if (m.plusShown) {
            pLine.strokeWidth = 2f; pLine.alpha = 255
            pFill.color = Theme.raised; c.drawRoundRect(r.plus, 6f, 6f, pFill)
            pLine.color = if (m.cursor == EditStop.PLUS) Theme.gold else Theme.bronze; c.drawRoundRect(r.plus, 6f, 6f, pLine)
            val pp = bodyP(30f, Theme.gold, align = Paint.Align.CENTER)
            c.drawText("+", r.plus.centerX(), h / 2f - (pp.ascent() + pp.descent()) / 2f, pp)
            if (m.cursor == EditStop.PLUS && m.sheet == null) UiKit.focus(c, r.plus)
        }
        drawDone(c, r.done, h, doneGlyph(m))
        if (m.cursor == EditStop.DONE && m.menu == null && m.sheet == null && m.lifted == null) UiKit.focus(c, r.done)
        drawFight(c, r.fight, h, m.fightPreview)
        if (m.cursor == EditStop.FIGHT && m.menu == null && m.sheet == null && m.lifted == null) UiKit.focus(c, r.fight)
        // the hints, one word each, right of the disc and left of Done (the review: never under the disc - the least needed go first, B's last)
        hints(c, g1 + HINT_GAP, r.done.left - 18f, h / 2f, hintsFor(m, m.menu?.let { host.menuRows(it).getOrNull(m.menuRow)?.kind }))
        c.restoreToCount(save)
    }

    /** Done's glyph: B's only where B IS Done (the top level: no menu, tray or lift - else B is Back / Put back, the review found two
     *  B's side by side); Start's elsewhere (Start is Done in every state) */
    fun doneGlyph(m: HomeEditModel): String =
        if (m.menu == null && m.sheet == null && m.lifted == null) UiKit.backGlyph() else Glyphs.start()

    /**
     * the ribbon's one button: "Done" with [glyph] (B's at the top level, Start's while B is Back / Put back; the comic-ink kit's raised
     * button, a gold edge); held past [HomeEditModel.RING_FROM_MS] (the host's [Host.holdK]), its gold ring fills round it towards Discard
     * and its word reads "Discard" (the hold's own feedback: no text elsewhere; a short tap or B never shows it)
     */
    private val ringMeasure = android.graphics.PathMeasure()
    private val ringPart = Path()
    private fun drawDone(c: Canvas, b: RectF, h: Float, glyph: String) {
        val k = host.holdK()
        pFill.style = Paint.Style.FILL; pFill.color = Theme.raised; pFill.alpha = 255
        c.drawRoundRect(b, 6f, 6f, pFill)
        if (k != null && k > 0f) {
            // the hold fills the button from its left (gold, see-through), the ring traced round it as it goes
            val sv = c.save()
            c.clipRect(b.left, b.top, b.left + b.width() * k.coerceIn(0f, 1f), b.bottom)
            pFill.color = Theme.gold; pFill.alpha = 90; c.drawRoundRect(b, 6f, 6f, pFill); pFill.alpha = 255
            c.restoreToCount(sv)
            pLine.strokeWidth = 4f; pLine.color = Theme.gold; pLine.alpha = 255
            path.reset(); path.addRoundRect(b, 6f, 6f, Path.Direction.CW)
            ringMeasure.setPath(path, false)
            ringPart.reset()
            ringMeasure.getSegment(0f, ringMeasure.length * k.coerceIn(0f, 1f), ringPart, true)
            c.drawPath(ringPart, pLine)
        }
        pLine.strokeWidth = 2f; pLine.color = Theme.gold; pLine.alpha = 255
        c.drawRoundRect(b, 6f, 6f, pLine)
        val word = Tr.t(if (k != null && k > 0f) "Discard" else "Done")
        val tp = bodyP(RIBBON_TEXT, Theme.gold, align = Paint.Align.LEFT)
        val tw = tp.measureText(word)
        val x0 = b.centerX() - (GLYPH + 6f + tw) / 2f
        UiKit.pad(c, glyph, x0 + GLYPH / 2f, h / 2f, GLYPH)
        c.drawText(word, x0 + GLYPH + 6f, h / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
    }

    /** the hints for the state: glyph names (the chosen set's) and one word each */
    fun hintsFor(m: HomeEditModel, row: MenuRow.Kind? = null): List<Pair<String, String>> {
        val a = UiKit.okGlyph(); val b = UiKit.backGlyph(); val x = UiKit.xGlyph(); val y = UiKit.yGlyph()
        return when {
            m.sheet != null -> listOf(a to "Add", b to "Back")
            // fix round 3: a stepper row (the Map's Zoom out / in) changes with Left / Right only: the D-pad's glyph, never A's
            m.menu != null && row == MenuRow.Kind.STEP -> listOf(DPAD to "Change", b to "Back")
            m.menu != null -> listOf(a to "Change", b to "Back")
            m.lifted == EditStop.ROOMS -> listOf(DPAD to "Move", a to "Drop", b to "Put back")
            // a room is an app: A carries it, X hides it (Settings stays); the "+" spot: A its tray
            m.cursor == EditStop.ROOMS && m.barOnPlus -> listOf(a to "Add")
            m.cursor == EditStop.ROOMS && m.barRoom?.let { RoomBar.canHide(it) } == true -> listOf(a to "Move", x to "Hide")
            m.cursor == EditStop.ROOMS -> listOf(a to "Move")
            // the chat and the map move sideways only; the card, the bar and the party list all four ways (after 1518: the bar's way to
            // the top was never shown)
            m.lifted == EditStop.CHAT || m.lifted == EditStop.MAP -> listOf(DPAD to "Side", a to "Drop", b to "Put back")
            m.lifted != null -> listOf(DPAD4 to "Move", a to "Drop", b to "Put back")
            // THE TOP BAR'S PIECES (P1 - P4): a pencil its bubble, the Look chip and the Fight toggle switch, an extra hides, "+" adds
            m.cursor.pencil -> listOf(a to "Options")
            m.cursor == EditStop.LOOK || m.cursor == EditStop.FIGHT -> listOf(a to "Switch")
            m.cursor == EditStop.EXTRAS && m.extraOnPlus -> listOf(a to "Add")
            m.cursor == EditStop.EXTRAS -> listOf(x to "Hide")
            // at the top level B is Done: the Done button wears its glyph (no "Done" hint beside it)
            m.cursor == EditStop.PLUS -> listOf(a to "Add")
            // Done as a stop: A is Done (its button says so), X Discard
            m.cursor == EditStop.DONE -> listOf(x to "Discard")
            m.cursor == EditStop.DIVIDER -> listOf(DPAD to "Width")
            // the party list has no size (its rows are Party mode's)
            m.cursor == EditStop.PARTY -> listOf(a to "Move", x to "Options")
            else -> listOf(a to "Move", x to "Options", y to "Size")
        }
    }

    /** one hint's glyph width ([DPAD]: Left and Right side by side; [DPAD4]: one four-way cross) */
    private fun glyphW(g: String): Float = if (g == DPAD) 2f * GLYPH + 2f else GLYPH
    /** the hints' width with the first [n] of [pairs] in paint [p] */
    private fun hintsW(pairs: List<Pair<String, String>>, n: Int, p: Paint): Float {
        var w = 0f
        for (i in 0 until n) w += p.measureText(Tr.t(pairs[i].second)) + 6f + glyphW(pairs[i].first) + (if (i > 0) HINT_SEP else 0f)
        return w
    }

    /**
     * the hints right-aligned to [right], never left of [left] (the disc's gap: the review found ES / FR running under it): the text
     * shrinks to [RIBBON_TEXT_MIN] to keep them all ([hintsKept]); only past that do the last pairs (the least needed: Size, then
     * Options; Put back, then Drop) go
     */
    private fun hints(c: Canvas, left: Float, right: Float, cy: Float, pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty() || right <= left) return
        val n = hintsKept(pairs, right - left)
        var size = RIBBON_TEXT
        var p = bodyP(size, Theme.text)
        while (size > RIBBON_TEXT_MIN && hintsW(pairs, n, p) > right - left) { size -= 1f; p = bodyP(size, Theme.text) }
        val sv = c.save()
        c.clipRect(left, 0f, right, cy * 2f)
        var x = right
        for (i in n - 1 downTo 0) {
            val (glyph, word) = pairs[i]
            val t = Tr.t(word)
            x -= p.measureText(t)
            c.drawText(t, x, cy - (p.ascent() + p.descent()) / 2f, p)
            x -= 6f + GLYPH / 2f
            when (glyph) {
                DPAD -> { UiKit.pad(c, "gen_right", x, cy, GLYPH); x -= GLYPH + 2f; UiKit.pad(c, "gen_left", x, cy, GLYPH) }
                DPAD4 -> dpad4(c, x, cy, GLYPH)
                else -> UiKit.pad(c, glyph, x, cy, GLYPH)
            }
            x -= GLYPH / 2f + HINT_SEP
        }
        hintsLeftDrawn = x + HINT_SEP
        hintsKeptDrawn = n
        c.restoreToCount(sv)
    }
    /** the last drawn hints' left edge (the tests' check that they stay clear of the disc) */
    var hintsLeftDrawn = Float.NaN
    /** how many hints the last drawn ribbon kept (the tests' check that every one fits, B's last one too) */
    var hintsKeptDrawn = -1

    /** how many of [pairs] (from the first) fit [room] px at [RIBBON_TEXT] (one at least: the text shrinks for it) */
    fun hintsKept(pairs: List<Pair<String, String>>, room: Float): Int {
        val p = bodyP(RIBBON_TEXT, Theme.text)
        // fix round (the review: B's hint went first) and the readability pass (every hint that fits shows): ALL of a state's hints are
        // kept at a smaller text, down to [RIBBON_TEXT_MIN], before any pair is dropped ([hints] shrinks the text for them)
        if (pairs.size > 1 && hintsW(pairs, pairs.size, p) > room &&
            hintsW(pairs, pairs.size, bodyP(RIBBON_TEXT_MIN, Theme.text)) <= room) return pairs.size
        var n = pairs.size
        while (n > 1 && hintsW(pairs, n, p) > room) n--
        return n
    }

    /** a lifted widget that moves all four ways (the card, the bar, the party list): ONE cross with its four arms lit (each direction's
     *  glyph lights its own arm: each drawn clipped to its arm's quarter, the triangles from the cross's centre) */
    private val armClip = Path()
    private fun dpad4(c: Canvas, cx: Float, cy: Float, size: Float) {
        val h = size / 2f
        for (i in 0 until 4) {
            armClip.reset(); armClip.moveTo(cx, cy)
            when (i) {
                0 -> { armClip.lineTo(cx - h, cy - h); armClip.lineTo(cx - h, cy + h) }      // left
                1 -> { armClip.lineTo(cx - h, cy - h); armClip.lineTo(cx + h, cy - h) }      // up
                2 -> { armClip.lineTo(cx + h, cy - h); armClip.lineTo(cx + h, cy + h) }      // right
                else -> { armClip.lineTo(cx - h, cy + h); armClip.lineTo(cx + h, cy + h) }   // down
            }
            armClip.close()
            val sv = c.save(); c.clipPath(armClip)
            UiKit.pad(c, DPAD4_ARMS[i], cx, cy, size)
            c.restoreToCount(sv)
        }
    }

    /** the finger that went down on Done (its long press runs: [Host.holdDone]) */
    var doneDown = false
        private set
    /** 1518: the finger slid off Done (its hold let go): its UP does nothing */
    private var doneSlid = false

    /** 1518: the finger on Done let go of (edit mode parked by a fight or a room, the chrome torn down): its hold goes, the ribbon's
     *  next tap is a tap (never swallowed by a stale down) */
    fun ribbonLetGo() {
        doneSlid = false
        if (!doneDown) return
        doneDown = false; host.holdDone(false); invalidate()
    }

    /**
     * a finger on the ribbon (Strip px; [action] = MotionEvent's ACTION_DOWN / ACTION_MOVE / ACTION_UP / ACTION_CANCEL): Done - a tap is
     * Done, a long press Discard (the host's hold timer; 1518: a finger slid off Done by more than [TAP_SLOP] lets the hold go and its
     * UP does nothing, iOS's long press) - and "+" the Add tray. True = the ribbon's
     */
    fun ribbonTouch(x: Float, y: Float, action: Int): Boolean {
        if (action == MotionEvent.ACTION_CANCEL) { ribbonLetGo(); return true }
        val m = host.liveModel ?: run { doneDown = false; return false }
        host.poke()
        val up = action == MotionEvent.ACTION_UP
        // 1518: parked meanwhile (a fight): the finger's UP / CANCEL clears its down, so the first tap after the fight is a tap
        if (m.parked) { if (up) ribbonLetGo(); return false }
        val r = ribbon(width.toFloat().takeIf { it > 0f } ?: 1240f)
        if (action == MotionEvent.ACTION_MOVE) {
            // 1518: off Done by more than the tap slop: the hold goes (the ring, "Discard" back to Done); the UP then does nothing
            if (doneDown && !RectF(r.doneHit).apply { inset(-TAP_SLOP, -TAP_SLOP) }.contains(x, y)) { ribbonLetGo(); doneSlid = true }
            return true
        }
        if (up && doneSlid) { doneSlid = false; return true }
        if (!up) doneSlid = false
        if (!up && (y < 0f || y > Strip.TRACKS + Strip.RIBBON_SLOP)) return false
        val onDone = r.doneHit.contains(x, y)
        val onPlus = m.plusShown && r.plusHit.contains(x, y)
        val onFight = r.fightHit.contains(x, y)
        if (!up) {
            if (onDone) { doneDown = true; host.holdDone(true); invalidate(); return true }
            // an open tray closes first (its UP is the ribbon's too); just off the ribbon only "+", Fight and Done are its
            return (m.sheet != null && y >= 0f && y <= Strip.TRACKS) || onPlus || onFight
        }
        if (doneDown) {
            doneDown = false
            val fired = host.holdDone(false)
            if (!fired && onDone) host.run(m.done())
            invalidate()
            return true
        }
        if (m.sheet != null) { m.closeSheet(); invalidate(); return true }
        if (onPlus) { host.run(m.tap(EditStop.PLUS)); invalidate(); return true }
        if (onFight) { host.run(m.toggleFight()); invalidate(); return true }
        return false
    }

    // ---------------------------------------------------------------- phase 4: the room bar (drawn by the Strip, in its px)

    /**
     * THE ROOM BAR'S CHROME (1518 build 2: the rooms are apps - the bar itself wears nothing: no dim, no frame, no gold edge, no tag), in
     * the two layers the Strip calls: 0 (under the lifted room and the disc) nothing; 1 (over them) the golden box on the room under the
     * bar's own cursor - or on its "+" spot - (lifted: round the carried picture, with the kit's gold arrows to the sides it can still go)
     * and a refusal's red pulse (X on Settings). The chrome's fade rides [Host.chromeAlpha]. The badges and the "+" spot are [apps]'.
     */
    fun drawBar(c: Canvas, layer: Int) {
        if (layer == 0) return
        val m = host.model ?: return
        val bar = host.bar() ?: return
        val a = host.chromeAlpha
        if (a <= 0.01f || m.parked) return
        if (m.cursor != EditStop.ROOMS) return
        val save = if (a < 0.99f) c.saveLayerAlpha(0f, 0f, bar.width.toFloat(), bar.height.toFloat(), (255 * a).toInt()) else -1
        val sel = m.menu == null && m.sheet == null
        val name = m.barRoom
        val room = name?.let { n -> Room.values().firstOrNull { it.name == n } }
        val b = if (room != null) bar.roomDrawn(room) else if (m.barOnPlus) bar.plusDrawn() else null
        if (b != null) {
            val box = scratch; box.set(b); box.inset(-10f, -8f)
            if (sel || m.lifted == EditStop.ROOMS) UiKit.focus(c, box)
            if (m.lifted == EditStop.ROOMS && m.drag == null && name != null) {
                val vis = m.barRooms
                val i = vis.indexOf(name)
                UiKit.moveArrows(c, box, left = i > 0, right = i < vis.size - 1, words = false)
            }
            host.flash?.let { (s, at) ->
                val t = SystemClock.uptimeMillis() - at
                if (s == EditStop.ROOMS && t in 0 until HomeEditModel.FLASH_MS) {
                    edgeColour(c, box, Color.rgb(220, 40, 30), (255 * (1f - t.toFloat() / HomeEditModel.FLASH_MS)).toInt())
                    bar.postInvalidateOnAnimation()
                }
            }
        }
        if (save >= 0) c.restoreToCount(save)
    }

    /**
     * THE ROOMS AS APPS (1518 build 2) for the Strip ([Strip.EditApps]): the "+" spot while a room is hidden (edit mode on screen, not
     * parked), each hideable room's "-" badge - the comic-ink look: an ink disc, a bronze ring, a cream bar - and the "+" spot's own
     * picture (a raised disc, a bronze ring, a gold "+", its own wiggle). None on the lifted room (it is picked up), none while parked
     */
    fun apps(): Strip.EditApps = appsImpl
    private val appsImpl = object : Strip.EditApps {
        override val plus: Boolean get() = host.model?.let { m -> m.barPlus && !m.parked && host.chromeAlpha > 0.01f } == true
        override fun badge(c: Canvas, r: Room, cx: Float, cy: Float, icon: Float) {
            val m = host.model ?: return
            val a = host.chromeAlpha
            if (a <= 0.01f || m.parked || m.barLifted == r.name) return
            val bx = cx - icon / 2f + Strip.BADGE_IN; val by = cy - icon / 2f + Strip.BADGE_IN
            val al = (255 * a).toInt().coerceIn(0, 255)
            pFill.style = Paint.Style.FILL; pFill.color = Theme.ink; pFill.alpha = al
            c.drawCircle(bx, by, BADGE_R, pFill)
            pLine.strokeWidth = 2.5f; pLine.color = Theme.bronze; pLine.alpha = al; pLine.pathEffect = null
            c.drawCircle(bx, by, BADGE_R - 1f, pLine)
            pFill.color = Theme.text; pFill.alpha = al
            scratch2.set(bx - BADGE_R * 0.5f, by - 2.2f, bx + BADGE_R * 0.5f, by + 2.2f)
            c.drawRoundRect(scratch2, 2.2f, 2.2f, pFill)
        }
        override fun plus(c: Canvas, cx: Float, cy: Float, icon: Float) {
            val a = host.chromeAlpha
            if (a <= 0.01f) return
            val al = (255 * a).toInt().coerceIn(0, 255)
            val sv = c.save()
            if (host.plusPose(plusBuf)) { c.translate(plusBuf[2], plusBuf[3]); c.rotate(plusBuf[1], cx, cy); c.scale(plusBuf[0], plusBuf[0], cx, cy) }
            val r = icon * 0.46f
            pFill.style = Paint.Style.FILL; pFill.color = Theme.raised; pFill.alpha = al
            c.drawCircle(cx, cy, r, pFill)
            pLine.pathEffect = dashFine; pLine.strokeWidth = 2.5f; pLine.color = Theme.bronze; pLine.alpha = al
            c.drawCircle(cx, cy, r - 1.5f, pLine)
            pLine.pathEffect = null
            pFill.color = Theme.gold; pFill.alpha = al
            val arm = r * 0.5f; val th = 3.2f
            scratch2.set(cx - arm, cy - th, cx + arm, cy + th); c.drawRoundRect(scratch2, th, th, pFill)
            scratch2.set(cx - th, cy - arm, cx + th, cy + arm); c.drawRoundRect(scratch2, th, th, pFill)
            c.restoreToCount(sv)
        }
    }
    private val plusBuf = FloatArray(4)

    /** a finger on the rooms' row while editing (the Strip's px): a tap on a room's "-" badge hides it, a tap on "+" opens the tray of
     *  hidden rooms, a tap on a room moves the cursor there, a drag carries it - the others make way after the hover, the drop settles it
     *  (the model's drag, its x the bar's). An open tray: any tap on the row closes it */
    private var barDownX = 0f
    private var barDownY = 0f
    private var barDownSlot = -1
    private var barDownBadge: Room? = null
    private var barDownPlus = false
    private var barDragging = false
    fun barTouch(e: MotionEvent): Boolean {
        val m = host.liveModel ?: return false
        host.poke()
        val bar = host.bar() ?: return false
        if (m.parked) return false
        val x = e.x; val y = e.y
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                barDownX = x; barDownY = y; barDragging = false
                val open = m.sheet != null
                barDownBadge = if (open) null else bar.badgeAt(x, y)
                barDownPlus = !open && barDownBadge == null && bar.plusBox()?.contains(x, y) == true
                barDownSlot = if (open || barDownPlus) -1 else bar.slotAt(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = barDownSlot
                if (!barDragging && i >= 0 && abs(x - barDownX) > HomeEditModel.DRAG_SLOP) {
                    bar.slotBox(i)?.let { b ->
                        barDragging = true
                        host.run(m.barDragStart(i, barDownX, barDownY, HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom)))
                    }
                }
                if (barDragging) host.run(m.dragMove(x, y, now, host.geo))
            }
            MotionEvent.ACTION_UP -> {
                val tap = abs(x - barDownX) <= TAP_SLOP && abs(y - barDownY) <= TAP_SLOP
                val badge = barDownBadge
                if (barDragging) { host.run(m.dragEnd(x, y, now, host.geo)); barDragging = false }
                else if (m.sheet != null) m.closeSheet()
                else if (tap && badge != null) host.run(m.hideRoom(badge.name))
                else if (tap && barDownPlus) host.run(m.openRoomTray())
                else if (barDownSlot >= 0 && tap) host.run(m.barTap(barDownSlot))
                barDownSlot = -1; barDownBadge = null; barDownPlus = false
            }
            MotionEvent.ACTION_CANCEL -> { if (barDragging) host.run(m.dragCancel()); barDragging = false; barDownSlot = -1; barDownBadge = null; barDownPlus = false }
        }
        invalidate(); bar.invalidate()
        return true
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f
    private var downY = 0f
    private var downStop: EditStop? = null
    private var dragging = false
    /** a gesture that came in already lifted (the entry hold): it drags at once */
    private var carried = false

    /** the entry hold's touch goes on as this view's: the held widget is lifted and follows the finger (one swap gesture) */
    fun carry(s: EditStop, x: Float, y: Float) {
        val m = host.liveModel ?: return
        val b = widgetBoxes()[s] ?: return
        downX = x; downY = y; downStop = s; dragging = true; carried = true
        host.run(m.dragStart(s, x, y, HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom)))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = touch(e)

    fun touch(e: MotionEvent): Boolean {
        val m = host.liveModel ?: return false
        if (m.parked) return false                                          // parked (a fight): Home's own touches (touch-cast) go on
        host.poke()
        val x = e.x; val y = e.y
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; dragging = false; carried = false
                downStop = if (m.menu == null && m.sheet == null) stopAt(x, y) else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> { if (dragging) host.run(m.dragCancel()); dragging = false; downStop = null }
            MotionEvent.ACTION_MOVE -> {
                val s = downStop
                if (!dragging && s != null && (s.movable || s == EditStop.DIVIDER) &&
                    (abs(x - downX) > HomeEditModel.DRAG_SLOP || abs(y - downY) > HomeEditModel.DRAG_SLOP)) {
                    val b = if (s == EditStop.DIVIDER) dividerBox() else widgetBoxes()[s]
                    if (b != null) {
                        dragging = true
                        host.run(m.dragStart(s, downX, downY, HomeLayoutSolver.Box(b.left, b.top, b.right, b.bottom)))
                    }
                }
                if (dragging) host.run(m.dragMove(x, y, now, host.geo))
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) { host.run(m.dragEnd(x, y, now, host.geo)); dragging = false }
                else if (abs(x - downX) <= TAP_SLOP && abs(y - downY) <= TAP_SLOP) tapAt(m, x, y)
                downStop = null
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) host.run(m.dragCancel()); dragging = false; downStop = null }
        }
        invalidate()
        return true
    }

    /** the widget under a finger (the top bar's pieces first; the divider's handle, the card and the bar before the columns; with the
     *  fight preview none of the page's: decision D4) */
    fun stopAt(x: Float, y: Float): EditStop? {
        host.band()?.let { g -> bandAt(g, x, y)?.let { return it.first } }
        if (host.model?.fightPreview == true) return null
        dividerBox()?.let { if (RectF(it).apply { inset(-18f, -10f) }.contains(x, y)) return EditStop.DIVIDER }
        val wb = widgetBoxes()
        for (s in TOUCH_ORDER) {
            if (s == EditStop.CAST) { if (castBoxes().any { it.contains(x, y) }) return s; continue }
            if (wb[s]?.contains(x, y) == true) return s
        }
        return null
    }

    /**
     * the top bar's piece under a finger (P1 - P4) and what it is: (the stop, the extra's key / "badge:key" for its "-" badge / "plus"),
     * null = none. The pieces' touch boxes are [TOUCH_MIN] at least; the D-pad's and the faces' buttons are their pencils' too
     */
    private fun bandAt(g: BandGeo, x: Float, y: Float): Pair<EditStop, String?>? {
        fun hit(r: RectF?, grow: Boolean = true): Boolean {
            if (r == null) return false
            if (!grow) return r.contains(x, y)
            val dx = max(0f, (TOUCH_MIN - r.width()) / 2f); val dy = max(0f, (TOUCH_MIN - r.height()) / 2f)
            return x >= r.left - dx && x <= r.right + dx && y >= r.top - dy && y <= r.bottom + dy
        }
        if (hit(g.padPen)) return EditStop.PAD_PEN to null
        if (hit(g.facePen)) return EditStop.FACE_PEN to null
        if (hit(g.look)) return EditStop.LOOK to null
        if (hit(g.fightPen)) return EditStop.FIGHT_PEN to null
        val m = host.model
        if (m?.fightPreview != true) {
            // fix round 3 (the review: the first extra's badge reach covered the D-pad's Right button's lower part): on one of the D-pad's
            // or the ABXY's buttons ([BandGeo.keys]) an extra takes the finger only on its own cell or its drawn badge
            val onKey = g.keys.any { it.contains(x, y) }
            // an extra's "-" badge first (on its top left), then the extra, then "+"
            for ((k, r) in g.extras) if (extraBadgeHit(k, r, x, y, onKey)) return EditStop.EXTRAS to "badge:$k"
            for ((k, r) in g.extras) if (hit(r, grow = !onKey)) return EditStop.EXTRAS to k
            if (hit(g.plus, grow = !onKey)) return EditStop.EXTRAS to "plus"
        }
        if (hit(g.pad, grow = false)) return EditStop.PAD_PEN to null
        if (hit(g.face, grow = false)) return EditStop.FACE_PEN to null
        return null
    }

    /**
     * extra [k]'s "-" badge under a finger at [x], [y] ([r] its cell). Fix round (the review: the room bar's [Strip.BADGE_HIT] reach round
     * a badge on a 28 px picture covered most of the picture, so a tap meant to select the Hearthstone hid it): off the extra's picture the
     * badge keeps that reach; ON the picture only the drawn badge itself (its disc and [EXTRA_BADGE_SLOP]) is the badge's - the rest of the
     * picture selects the extra. The gold line's picture is its line ([r]). [onKey]: the finger on one of the band's buttons (fix round 3)
     * - there too only the drawn badge is the badge's (the rest is the button's: its pencil's bubble)
     */
    fun extraBadgeHit(k: String, r: RectF, x: Float, y: Float, onKey: Boolean = false): Boolean {
        val bx = if (k == "gold") r.left - EXTRA_BADGE_R - 2f else r.left + BADGE_IN
        val by = if (k == "gold") r.centerY() else r.top + BADGE_IN
        val d = kotlin.math.hypot(x - bx, y - by)
        if (d > Strip.BADGE_HIT / 2f) return false
        return (!onKey && !extraPicture(k, r).contains(x, y)) || d <= EXTRA_BADGE_R + EXTRA_BADGE_SLOP
    }
    /** extra [k]'s picture in its cell [r] (the icon: [ClockBand.ICON] at the cell's scale, centred, at its top; the gold line: the line;
     *  [EXTRA_PLUS]: its "+" spot's square, the blank frame's place) */
    fun extraPicture(k: String, r: RectF): RectF {
        if (k == "gold") return r
        val side = ClockBand.ICON * r.height() / ClockBand.CELL_H
        return RectF(r.centerX() - side / 2f, r.top, r.centerX() + side / 2f, r.top + side)
    }

    private fun tapAt(m: HomeEditModel, x: Float, y: Float) {
        if (m.sheet != null) {
            val i = trayHit.indexOfFirst { it.contains(x, y) }
            if (i < 0) m.closeSheet()
            else if (m.sheet == HomeEditModel.Sheet.ROOMS) host.run(m.unhideRoom(m.hiddenRooms.getOrNull(i)))
            else if (m.sheet == HomeEditModel.Sheet.EXTRAS) host.run(m.unhideExtra(m.extrasOff.getOrNull(i)))
            else host.run(m.addBack(m.removed.getOrNull(i)))
            return
        }
        // THE TOP BAR (P1 - P4): a pencil or its buttons open the bubble, the Look chip flips, a "-" hides, an extra is selected, "+" its tray
        if (m.menu == null || m.menu!!.pencil) host.band()?.let { g -> bandAt(g, x, y) }?.let { (s, what) ->
            when {
                s.pencil -> host.run(m.pencilTap(s))
                s == EditStop.LOOK -> { m.closeMenu(); host.run(m.lookFlip()) }
                what == "plus" -> { m.closeMenu(); host.run(m.openExtraTray()) }
                what != null && what.startsWith("badge:") -> host.run(m.hideExtra(what.removePrefix("badge:")))
                what != null -> m.extrasOn.indexOf(what).takeIf { it >= 0 }?.let { host.run(m.extraTap(it)) }
            }
            return
        }
        if (m.menu != null) {
            if (lastMenuBox.contains(x, y)) {
                for ((i, bs) in menuBoxes.withIndex()) {
                    val k = bs.indexOfFirst { RectF(it).apply { inset(-4f, -6f) }.contains(x, y) }
                    if (k >= 0) { host.run(m.menuTouch(i, k, host.menuRows(m.menu!!))); return }
                }
                menuRowBoxes.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { m.menuRow = it }
                return
            }
            m.closeMenu()
            return
        }
        // the red X chip, then its pill
        val sel = m.cursor
        if (sel.removable) widgetBoxes()[sel]?.let { b ->
            val last = if (sel == EditStop.CAST) castBoxes().lastOrNull() ?: b else b
            if (RectF(removeChip(last)).apply { inset(-10f, -10f) }.contains(x, y) ||
                (m.removeAsk == sel && removePill(last).contains(x, y))) { host.run(m.removeChip(sel)); return }
        }
        val s = stopAt(x, y)
        if (s == null) { m.cancelRemove(); return }
        host.run(m.tap(s))
    }

    companion object {
        /** the stops a finger finds, first first (the riders over the modules; the band's pieces before them all: [bandAt]) */
        private val TOUCH_ORDER = listOf(EditStop.QUEST, EditStop.CAST, EditStop.PARTY, EditStop.CHAT, EditStop.MAP)
        /** P4 (decision D4): the stops the fight preview takes away (the page's widgets, laid under the grown band) */
        private val PREVIEW_GONE = listOf(EditStop.CHAT, EditStop.MAP, EditStop.QUEST, EditStop.CAST, EditStop.PARTY, EditStop.DIVIDER)
        /** a pencil's speech bubble: its width, its least clearance from the screen's edges, its tail's height */
        const val BUBBLE_W = 640f
        const val BUBBLE_EDGE = 16f
        const val BUBBLE_TAIL = 18f
        /** the ribbon's Fight toggle: its least width, its dot's radius */
        const val FIGHT_MIN_W = 110f
        const val FIGHT_DOT = 7f
        /** an extra's "-" badge: its centre in from the cell's top left, its radius */
        const val BADGE_IN = 2f
        const val EXTRA_BADGE_R = 11f
        /** an extra's badge over its picture: the drawn disc and this much round it is the badge's ([extraBadgeHit]) */
        const val EXTRA_BADGE_SLOP = 3f
        /** the extras' "+" spot's key ([BandGeo.plus]: [extraPicture]) */
        const val EXTRA_PLUS = "plus"
        /** the Look chip's diamond pictogram: four buttons round a centre */
        private val DIAMOND = listOf(-1f to 0f, 0f to -1f, 1f to 0f, 0f to 1f)
        /** the dim over a widget that is not selected (SPEC section 4: alpha 70 / 255 black) */
        const val DIM = 70
        const val TAG_SIZE = 24f
        const val TAG_H = 36f
        /** a column narrower than this (a third of the page: ~400 px) puts the chat's / the card's tag on the frame's top edge */
        const val NARROW_TAG_W = 520f
        const val HANDLE_W = 18f
        const val HANDLE_H = 92f
        const val CHIP_R = 26f
        const val MENU_HEAD = 92f
        const val MENU_ROW = 84f
        /** a column menu's least width (narrower columns: it overhangs its own widget towards the middle) */
        const val MENU_MIN_W = 600f
        /** a wrapped row (label above its buttons) is this many rows tall */
        const val WRAP_K = 1.55f
        /** the segmented buttons' text size, and the least a row may shrink it to stay on one line */
        const val SEG_TEXT = 26f
        /** a stepper's sign (the Map's Zoom rows: Settings > Bottom screen's own steppers' 40) */
        const val STEP_TEXT = 40f
        const val SEG_TEXT_MIN = 22f
        /** the most a short column tightens a menu's rows */
        const val MENU_K_MIN = 0.8f
        const val TAP_SLOP = 24f
        const val RIBBON_TEXT = 22f
        /** the least the hints shrink to before any is cut (the readability pass: all of a state's hints shrink first, then the least
         *  needed pairs go) */
        const val RIBBON_TEXT_MIN = 18f
        /** the hints' room past the disc's gap */
        const val HINT_GAP = 12f
        /** between two hints (the readability pass: 18 -> 14, so Spanish and French keep all three: the glyphs part them) */
        const val HINT_SEP = 14f
        /** Done's room round its glyph and word (the readability pass: 40 -> 28 - French "Abandonner" sized it 199 px wide, the hints'
         *  room) */
        const val DONE_PAD = 28f
        /** the ribbon's "+" and Done: the least touch box (px, both ways) */
        const val TOUCH_MIN = 48f
        /** the ribbon's Done button's least width */
        const val DONE_MIN_W = 150f
        const val GLYPH = 28f
        /** the D-pad's hint: Left and Right glyphs side by side */
        const val DPAD = "dpad"
        /** the D-pad's four-way hint (a lifted card, bar or party list's Move): one cross, its four arms lit */
        const val DPAD4 = "dpad4"
        private val DPAD4_ARMS = arrayOf("gen_left", "gen_up", "gen_right", "gen_down")
        /** a menu's pop's starting scale, the Add tray's rise (px) */
        const val MENU_FROM = 0.92f
        /** the text paints kept ([textP]) */
        private const val TEXT_CACHE = 40
        const val SHEET_RISE = 60f
        /** the room bar's "-" badge's radius (an iOS-style badge on the picture's top left), the tray of hidden rooms' sizes */
        const val BADGE_R = 15f
        const val TRAY_W = 380f
        const val TRAY_ROW = 84f
        const val TRAY_HEAD = 76f
        /** rows in one column of the tray (more hidden rooms: a second column) */
        const val TRAY_ROWS = 6
    }
}
