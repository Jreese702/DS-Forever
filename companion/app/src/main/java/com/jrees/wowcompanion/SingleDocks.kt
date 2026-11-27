package com.jrees.wowcompanion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.jrees.wowcompanion.SinglePlayLayout.Box
import com.jrees.wowcompanion.SinglePlayLayout.Piece
import com.jrees.wowcompanion.SinglePlayLayout.Plan
import com.jrees.wowcompanion.SinglePlayLayout.Step

/**
 * SINGLE SCREEN MODE'S PLAY DOCKS (SingleScreen; the user's pick for "While playing", 2026-11-25: "1, 2, and also the chat on the left thr
 * ds toolbar should have the same functionality in single screen mode. We can shift those panels ine and out of combat to all fit
 * better"): while the game is the screen (SingleLook's PLAY state) Home's OWN views stand over it, each in a small never-focused window
 * of the top screen (SingleShell.sub, fork/SubWindow): the live map (its trail, the Polestar arrows) top right ([map]), Party mode's list
 * ([party]) and the chat ([chat]) on the left, hold to talk's pop-up over the party list while D-pad Left is held ([talk]). No second map,
 * no second chat: HomeView lends its views here (HomeView.lendToDocks) and takes them back before its page shows.
 *
 * WHERE: SinglePlayLayout (out of combat / in combat, every piece clear of the add-ons' HUD, the Wings, the compass). THE SHIFT: each
 * moving piece's window grows to the union of both places (ONE window update), the piece glides inside it on Motion's curve (its box laid
 * directly, no layout pass of the window), the window shrinks to the new place (ONE update) - none per frame. A window per piece, so a
 * touch between the party list and the chat is the game's. GPU: three small windows (within 0.35 MP), the fourth only while the talk
 * pop-up shows; a hidden piece's window is GONE (no surface).
 *
 * Touch on a piece = Home's own handlers (the chat -> the Chat room; the map -> Home's map module; a party plate -> its target / Smart Res
 * / its menu): those open a panel, and the docks give the views back.
 *
 * THE 1535 POLISH: two more pieces - Home's focused-quest card ([quest], lent as the map is) under the map, and the room row ([row]: the
 * host's own Strip, laid at its rooms-column size and drawn at SinglePlayLayout.ROW_K) out of fights only, SLIDING up from under the
 * screen's bottom edge and back down (SinglePlayLayout.SLIDES: no fade, the row whole all the way).
 */
class SingleDocks(context: Context, private val shell: SingleShell, private val cast: CastView) {
    val chat = Slot(context, Piece.CHAT, edge = false)
    val party = Slot(context, Piece.PARTY, edge = false)
    val map = Slot(context, Piece.MAP, edge = true)
    val quest = Slot(context, Piece.QUEST, edge = false)
    val row = Slot(context, Piece.ROW, edge = false).also { it.scale = SinglePlayLayout.ROW_K }
    /** the 1535 polish's corner buttons (the user: "touch buttons in the bottome left and right"): the tap-to-cast bar's (Home's own
     *  CastStripView, lent into [castBar] while it is open) and the swirl's (the room row on demand) */
    val castBar = Slot(context, Piece.CAST, edge = false)
    val btnL = CornerButton(context, swirl = false)
    val btnR = CornerButton(context, swirl = true)
    private val btnLSlot = Slot(context, Piece.BTN_L, edge = false).also { it.addView(btnL) }
    private val btnRSlot = Slot(context, Piece.BTN_R, edge = false).also { it.addView(btnR) }
    val talk = TalkDock(context, cast)

    /** each piece's window bounds now (screen px), its box now (where the piece is drawn), its alpha (a fade in / out) */
    private val win = HashMap<Piece, Box?>()
    private val now = HashMap<Piece, Box?>()
    private var anim: ValueAnimator? = null
    /** the arrangement laid (or being glided to) */
    var plan: Plan? = null; private set
    /** pieces kept off whatever the plan says (the LB picture over the party list, the fight's rolls at the map's place) */
    private var off: Set<Piece> = emptySet()
    /** the docks are up (PLAY) */
    var up = false; private set
    /** the windows' updates so far (the tests: two a piece a fight edge) */
    var places = 0; private set

    private fun slot(p: Piece): View = when (p) {
        Piece.MAP -> map; Piece.PARTY -> party; Piece.CHAT -> chat; Piece.TALK -> talk; Piece.QUEST -> quest; Piece.ROW -> row
        Piece.CAST -> castBar; Piece.BTN_L -> btnLSlot; Piece.BTN_R -> btnRSlot
    }

    private fun sub(p: Piece): SingleSub = shell.sub(p.sub).also { it.put(slot(p)) }

    /** the docks up (PLAY) or gone (a panel, a wheel, the game's end): gone = every window GONE (no surface, no touch) */
    fun show(on: Boolean) {
        if (up == on) return
        up = on
        if (!on) {
            anim?.cancel(); anim = null
            for (p in Piece.values()) if (win[p] != null || (p == Piece.TALK && talkUp)) shell.sub(p.sub).shown(false)
            win.clear(); now.clear(); plan = null
            talkUp = false
        }
    }

    /**
     * lay [p] (at once, or with the shift's glide: [animate]); [hide] = pieces kept off for now. A plan equal to the one laid: nothing.
     */
    fun arrange(p: Plan, animate: Boolean, hide: Set<Piece> = emptySet()) {
        if (!up) return
        if (p == plan && hide == off) return                              // (the tick asks every 100 ms: a glide under way goes on)
        val to = p.boxes().mapValues { (k, b) -> if (k in hide) null else b }
        val from = HashMap(now)
        plan = p; off = hide
        anim?.cancel(); anim = null
        val steps = SinglePlayLayout.steps(from, to, p.arr)
        if (!animate || steps.isEmpty()) {
            for (k in LAID) land(k, to[k])
            return
        }
        val glides = steps.filterIsInstance<Step.Glide>()
        for (g in glides) if (g.from == null) slot(g.piece).alpha = 0f                                  // (one coming fades in)
        for (st in steps.filterIsInstance<Step.Place>().distinctBy { it.piece }) place(st.piece, st.box)   // the grow: the union
        for (k in LAID) if (glides.none { it.piece == k }) land(k, to[k])
        val curve = Motion.curve()
        // a sliding piece starts where its glide does (under the screen's edge), drawn whole from the first frame
        for (g in glides) if (g.piece in SinglePlayLayout.SLIDES) frame(g.piece, g.from, g.to, 0f)
        val ms = glides.maxOfOrNull { it.ms } ?: SinglePlayLayout.SHRINK_MS
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            addUpdateListener { a ->
                val f = curve.getInterpolation(a.animatedFraction)
                for (g in glides) frame(g.piece, g.from, g.to, f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cut = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cut = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cut) return
                    anim = null
                    for (g in glides) land(g.piece, to[g.piece])         // the shrink (a sliding piece's end under the edge: gone)
                }
            })
            start()
        }
    }

    /** one frame of [k]'s glide at [f] (the curve applied): its box inside its (grown) window, a fade for one coming / going (a sliding
     *  piece's ends are never null: it moves whole) */
    private fun frame(k: Piece, a: Box?, b: Box?, f: Float) {
        val box = SinglePlayLayout.at(a, b, f) ?: return
        val v = slot(k)
        v.alpha = when { a == null -> f; b == null -> 1f - f; else -> 1f }
        now[k] = box
        win[k]?.let { w -> (v as? Slot)?.box(box.shift(-w.l, -w.t)) }
    }

    /** [k] laid at [b] (null = gone): its window to [b] (one update when it changed), the piece filling it */
    private fun land(k: Piece, b: Box?) {
        now[k] = b
        val v = slot(k)
        v.alpha = 1f
        place(k, b)
        if (b != null) (v as? Slot)?.box(Box(0, 0, b.w, b.h))
    }

    private fun place(k: Piece, b: Box?) {
        val s = sub(k)
        if (b == null) { if (win[k] != null) s.shown(false); win[k] = null; return }
        if (win[k] != b) { s.place(b.l, b.t, b.r, b.b); places++ }
        val was = win[k]
        win[k] = b
        s.touchable(true)
        if (was == null) s.shown(true)
        // a box inside the window relative to the window's origin (the glide lays it every frame)
        now[k]?.let { n -> (slot(k) as? Slot)?.box(n.shift(-b.l, -b.t)) }
    }

    // ---------------------------------------------------------------- hold to talk's pop-up

    private var talkUp = false

    /** hold to talk's pop-up / its line: up while the hold lasts and its words wait (CastView.talkPanel / talkLine), over the party list
     *  (or the chat's top); true = it shows */
    fun talkSync(): Boolean {
        val want = up && (cast.talkPanel != null || cast.talkLine != null)
        val p = plan
        if (want && p != null) {
            val s = shell.sub(Piece.TALK.sub)
            s.put(talk)
            s.place(p.talk.l, p.talk.t, p.talk.r, p.talk.b)
            s.touchable(cast.talkPanel != null)
            if (!talkUp) s.shown(true)
            talk.invalidate()
        } else if (talkUp) shell.sub(Piece.TALK.sub).shown(false)
        talkUp = want && p != null
        return talkUp
    }

    /** the host went: nothing left moving */
    fun close() { anim?.cancel(); anim = null; show(false) }

    /** where [k] stands now (tests, the shots), null = not shown */
    fun boxNow(k: Piece): Box? = now[k]
    fun windowNow(k: Piece): Box? = win[k]

    // ---------------------------------------------------------------- the views

    /**
     * A CORNER BUTTON (the 1535 polish): the bottom screen's own round button - the game's circle slot ring (the band's frame_circle art,
     * its pressed / selected states) round a dark well and its picture: the tap-to-cast bar's (the Spellbook room's icon) or, [swirl],
     * Home's own stone disc ([art]: the Strip's drawing). A tap = [onTap]; [lit] = what it opens is open (the ring's selected gold);
     * the press sinks it (0.92), as the band's buttons.
     */
    class CornerButton(ctx: Context, val swirl: Boolean) : View(ctx) {
        var onTap: (() -> Unit)? = null
        var lit = false
            set(v) { if (field != v) { field = v; invalidate() } }
        /** the swirl's own drawing (CompanionHost: the Strip's disc at this size): false = the drawn stand-in */
        var art: ((Canvas, Float, Float, Float) -> Boolean)? = null
        private var down = false
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val d = minOf(w, h)
            val cx = w / 2f; val cy = h / 2f
            val save = c.save()
            if (down) c.scale(0.92f, 0.92f, cx, cy + 2f)
            if (swirl && art?.invoke(c, cx, cy, d) == true) {
                if (lit) { p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Theme.gold; c.drawCircle(cx, cy, d / 2f - 2f, p) }
                c.restoreToCount(save); return
            }
            val r = d / 2f
            p.style = android.graphics.Paint.Style.FILL; p.color = android.graphics.Color.argb(230, 10, 8, 6)
            c.drawCircle(cx, cy, r * 0.84f, p)
            val pic = if (swirl) null else UiKit.tex("ricon_spellbook")
            if (pic != null) { box.set(cx - r * 0.56f, cy - r * 0.56f, cx + r * 0.56f, cy + r * 0.56f); c.drawBitmap(pic, null, box, p) }
            else if (swirl) {
                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Theme.gold
                c.drawArc(RectF(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f), 0f, 300f, false, p)
            }
            val ring = Icons.ui(if (lit) "cast/frame_circle_selected" else if (down) "cast/frame_circle_pressed" else "cast/frame_circle_normal")
                ?: Icons.ui("cast/frame_circle_normal")
            if (ring != null) { box.set(0f, 0f, w, h); c.drawBitmap(ring, null, box, p) }
            else {
                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 4f; p.color = if (lit) Theme.gold else Theme.bronze
                c.drawCircle(cx, cy, r - 3f, p)
            }
            c.restoreToCount(save)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
                MotionEvent.ACTION_UP -> { val was = down; down = false; invalidate(); if (was && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onTap?.invoke() }
                MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
            }
            return true
        }
    }

    /**
     * ONE PIECE'S WINDOW CONTENT: it lays its one child (Home's lent view) at [box] in the window's own px, measured only when its size
     * changed - the glide's frames lay it here directly (no layout pass through the window). The map's: clipped round and the module's
     * edge drawn over it (Home's well language: UiKit.module).
     */
    class Slot(ctx: Context, val piece: Piece, private val edge: Boolean) : FrameLayout(ctx) {
        private var at: Box? = null
        private val clip = Path()
        private val r = RectF()
        /** the 1535 polish: the child laid at its box / [scale] and drawn at [scale] (the room row: the Strip at its own size, smaller) */
        var scale = 1f

        init { clipChildren = true; setWillNotDraw(!edge) }

        private fun cw(b: Box) = maxOf(1, Math.round(b.w / scale))
        private fun ch(b: Box) = maxOf(1, Math.round(b.h / scale))
        private fun lay(c: View, b: Box) {
            c.layout(b.l, b.t, b.l + cw(b), b.t + ch(b))
            if (scale != 1f) { c.pivotX = 0f; c.pivotY = 0f; c.scaleX = scale; c.scaleY = scale }
        }

        fun box(b: Box) {
            if (at == b) return
            at = b
            val c = getChildAt(0)
            if (c == null || width == 0) { requestLayout(); invalidate(); return }
            if (c.width != cw(b) || c.height != ch(b)) c.measure(MeasureSpec.makeMeasureSpec(cw(b), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch(b), MeasureSpec.EXACTLY))
            lay(c, b)
            invalidate()
        }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
            val b = at ?: Box(0, 0, MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
            for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw(b), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch(b), MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val a = at ?: Box(0, 0, r - l, b - t)
            for (i in 0 until childCount) lay(getChildAt(i), a)
        }

        override fun dispatchDraw(c: Canvas) {
            val a = at
            if (!edge || a == null) { super.dispatchDraw(c); return }
            r.set(a.l.toFloat(), a.t.toFloat(), a.r.toFloat(), a.b.toFloat())
            clip.reset(); clip.addRoundRect(r, UiKit.MODULE_R, UiKit.MODULE_R, Path.Direction.CW)
            c.save(); c.clipPath(clip); super.dispatchDraw(c); c.restore()
            UiKit.module(c, r, edgeOnly = true)
        }
    }

    /**
     * HOLD TO TALK'S POP-UP OVER THE GAME (CastView.drawTalkPanel / drawTalkLine, the band's own painter, as Home draws it under the band):
     * recorded once at the page's origin, then drawn with its box at this view's top-left; a finger on its tabs picks that chat
     * (CastView.talkPanelTouch, in the page's px). The panel's own listening halo asks this view for its next frames.
     */
    class TalkDock(ctx: Context, private val cast: CastView) : View(ctx) {
        private val probe = Picture()
        private var ox = 0f
        private var oy = 0f

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            if (w <= 0f) return
            val panel = cast.talkPanel != null
            val pc = probe.beginRecording(width + 64, 4096)
            if (panel) cast.drawTalkPanel(pc, 0f, 0f, w + 28f, this) else cast.drawTalkLine(pc, 0f, 0f, w + 24f)
            probe.endRecording()
            val box = if (panel) cast.talkPanelBox else cast.talkLineBox
            if (box.isEmpty) return
            ox = box.left; oy = box.top
            c.save(); c.translate(-ox, -oy); c.drawPicture(probe); c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) cast.talkPanelTouch(e.x + ox, e.y + oy)
            return true
        }
    }

    companion object {
        /** the pieces laid by the arrangement (the talk pop-up has its own [talkSync]) */
        private val LAID = listOf(Piece.MAP, Piece.PARTY, Piece.CHAT, Piece.QUEST, Piece.ROW, Piece.CAST, Piece.BTN_L, Piece.BTN_R)

        /**
         * Home never laid yet: a fresh host in single screen mode (the switch, every launcher resume) starts on the game with the rooms
         * column GONE, so no layout pass ever reaches [home] and its places - what the docks show (HomeView.dockLaid) - are never worked
         * out until Home's page shows once. [column] (the rooms column holding Home) is measured and laid ONCE at the panel's ([l], [t],
         * [r], [b]) out of the window's own passes (it stays GONE); Home works its places out at its first size (its onSizeChanged).
         * True = laid now; a Home laid already is left alone
         */
        fun layHomeOnce(home: View, column: View, l: Int, t: Int, r: Int, b: Int): Boolean {
            if (home.width > 0 && home.height > 0) return false
            if (r <= l || b <= t) return false
            column.measure(View.MeasureSpec.makeMeasureSpec(r - l, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(b - t, View.MeasureSpec.EXACTLY))
            column.layout(l, t, r, b)
            return home.width > 0 && home.height > 0
        }
    }
}
