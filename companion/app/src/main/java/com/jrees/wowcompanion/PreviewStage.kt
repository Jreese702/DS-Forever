package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * THE SETTINGS PANEL'S LIVE MINIATURES (f-settings-previews; the user: "What ever happened to making our visual aids in the settings less
 * shitty?", then "Yes, this direction": real miniatures of our own bottom-screen views, drawn by the real view code). An OFFSCREEN Home of
 * its own - a HomeView with its own CastView, MapView and ChatView, exactly as CompanionHost builds the live one - fed COPIES of the latest
 * game documents, and drawn at a [HomeView.Companion.Look] into a cached half-size bitmap.
 *
 * NEVER THE GAME'S: the views are never attached to a window (so no controller reader ever starts: CastView starts its reader only while
 * attached and shown), they get no touch, their callbacks are no-ops, and every "pad" (the add-on's tap cells) is stripped from the copies,
 * so no tap can be addressed. The full document is fed as current (Home takes a fight only from a current one); the quests and chat as
 * replays. The look is applied only for the length of one synchronous
 * render (HomeView.renderLook), so the player's settings are never written and the live Home never sees it. Main thread only.
 */
object PreviewStage {
    private var full: JSONObject? = null
    private var quests: JSONObject? = null
    private var chat: JSONObject? = null
    /** documents seen since the last render (a render reads them all again) */
    private var gen = 0
    private var builtGen = -1

    /** the host's feed: the latest documents, as they come (kept as they are: copied and stripped of every tap pad only when a
     *  picture is drawn, so the game's stream costs nothing while Settings is closed) */
    fun feedFull(msg: JSONObject) { full = msg; gen++ }
    fun feedQuests(q: JSONObject) { quests = q; gen++ }
    fun feedChat(c: JSONObject) { chat = c; gen++ }
    /** something to show (the panel draws its own diagram until the game has sent a full document) */
    val ready get() = full != null

    /** the map module's box in the last picture, as fractions of it (the minimap rows crop it), null = none yet */
    var mapBox: android.graphics.RectF? = null
        private set

    /** the companion's own keyboard, drawn at [w] x [h] (the Message input row): a board of its own, never wired to anything */
    private var kb: ChatKeyboard? = null
    fun keyboard(ctx: Context, c: Canvas, w: Float, h: Float) {
        val k = kb ?: ChatKeyboard(ctx).also { it.open(); kb = it }
        val fh = ChatKeyboard.fullHeight()
        k.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY))
        k.layout(0, 0, 1240, fh)
        c.save(); c.scale(w / 1240f, h / fh); k.draw(c); c.restore()
    }

    /** the player's own item / spell wheel lists (the host's HomeRadialView) */
    var wheelItems: (spell: Boolean) -> List<UiKit.RadialItem?> = { emptyList() }

    /** the class radial on that side, from the stage's own CastView (the bars' latest document) */
    fun classWheel(ctx: Context, c: Canvas, w: Float, h: Float, left: Boolean, pointed: Int): Boolean {
        val f = full ?: return false
        val t = tree ?: build(ctx).also { tree = it }
        strip(f).optJSONObject("actionbar")?.let { t.cast.update(it) }
        return t.cast.drawClassPreview(c, w, h, left, pointed)
    }

    /** the live bar's DS toolbar (the host's CastView): its pictures and whether they show */
    var roomBar: () -> Map<Int, String>? = { null }
    var roomBarOn: () -> Boolean = { false }

    private class Tree(val home: HomeView, val cast: CastView, val map: MapView)
    private var tree: Tree? = null
    private val cache = HashMap<String, Bitmap>()
    private var cachedAt = 0L

    /**
     * THE MINIATURES GO WHEN SETTINGS IS NOT ON SHOW (issue triage 2026-11-14, the 1.0.29 - 1.0.31 low-memory cluster; the user,
     * 2026-11-13: "yes on the triage things"): each look's half-size Home is 1.2 MB and the cache was kept after the panel closed, for
     * the rest of the run. At Android's running-low trims and at MemWatch's relief (the game starting, the world loading) they go
     * unless Settings is the room on show; the panel draws them again when it opens. Main thread (MemWatch runs trims there).
     */
    fun cacheLetsGo(level: Int, room: String): Boolean =
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && room != Room.SETTINGS.name.lowercase()
    init {
        MemWatch.onTrim("preview") { level ->
            if (!cacheLetsGo(level, MemWatch.room) || cache.isEmpty()) 0L
            else cache.values.sumOf { it.byteCount.toLong() }.also { cache.clear(); cachedAt = 0L }
        }
    }

    /** the width of a miniature; Home's own frame is 1240 x (1080 - the bar) */
    private const val W = 1240
    private val H get() = 1080 - Strip.HEIGHT
    private const val SCALE = 0.5f
    /** a cached picture is drawn again after this long (the game's documents keep coming; the panel need not follow every one) */
    private const val STALE_MS = 3_000L

    /**
     * Home at [look] (in a fight: [fight]), the half-size picture; null until the game has sent a full document. Cached per look and
     * fight for [STALE_MS].
     */
    fun home(ctx: Context, look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean? = null): Bitmap? {
        val f = full ?: return null
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt > STALE_MS && gen != builtGen) { cache.clear(); cachedAt = now }
        val key = "$look/$fight/$toolbar"
        cache[key]?.let { return it }
        val t = tree ?: build(ctx).also { tree = it }
        builtGen = gen
        askDirty = true
        val bmp = try {
            HomeView.renderLook = look
            feed(t, f, fight, toolbar)
            t.home.previewMapRect()?.let { m -> mapBox = android.graphics.RectF(m.left / W, m.top / H, m.right / W, m.bottom / H) }
            val b = Bitmap.createBitmap((W * SCALE).toInt(), (H * SCALE).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(SCALE, SCALE)
            t.home.draw(c)
            b
        } catch (e: Throwable) {
            android.util.Log.w("WCHOME", "preview stage: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            HomeView.renderLook = null
        }
        if (bmp != null) cache[key] = bmp
        return bmp
    }

    /** the stage's Home fed copies of the latest documents (no tap pad), [fight] or not, settled at [HomeView.renderLook] */
    private fun feed(t: Tree, f: JSONObject, fight: Boolean, toolbar: Boolean?) {
        val look = HomeView.renderLook ?: return
        val doc = strip(f)
        doc.optJSONObject("actionbar")?.optJSONObject("hudS")?.let { hs ->
            hs.put("c", fight)
            // a fight's swing line needs a swing: one in progress when the copy has none (the game's own clock, hudS.g)
            if (fight && !hs.has("sw")) hs.optDouble("g", Double.NaN).takeIf { !it.isNaN() }?.let { g ->
                hs.put("sw", JSONObject().put("k", 0).put("s", g - 0.8).put("d", 2.6))
            }
        }
        run {
            // the DS toolbar's pictures on the plain D-pad: as the live bar has them (on), or the player's own slots (off)
            t.cast.roomBar = roomBar()
            t.cast.roomBarOn = toolbar ?: roomBarOn()
            doc.optJSONObject("map")?.let { t.map.update(it, 0) }
            doc.optJSONObject("actionbar")?.let { t.cast.update(it) }
            // as CURRENT: Home takes the fight's word only from a current document (HomeCombat.note). The copy has no tap pad: nothing to tap
            t.home.onFull(doc, true)
            quests?.let { strip(it) }?.let { q -> t.home.onQuests(q, false); t.map.onQuests(q) }
            chat?.let { t.home.onChat(strip(it)) }
            t.home.tick()
            t.home.previewSettle(look)
            lay(t)
            t.home.previewSettle(look)
            lay(t)
        }
    }

    // ---------------------------------------------------------------- the one-time cast bar question's picture
    /** set by every other use of the stage: a cast bar picture being drawn starts again (the stage's Home is shared) */
    private var askDirty = false
    private var stripView: Strip? = null

    /**
     * THE ONE-TIME "TRY THE NEW TAP-TO-CAST BAR?" PICTURE, REAL (the user, 2026-11-13: "show a little picture with the pop up showing how
     * it looks with it animating away in combat", then "make sure that this pop up uses realistic visuals instead of the renders"): the
     * stage's real Home - the player's own chat, minimap, controller band - and the real Home bar ([Strip]) under it, the tap-to-cast bar
     * on Both ([HomeView.Companion.Look.castSide]) showing [bar], at rest and then in a fight sliding away on the page's one motion.
     * Drawn ONE frame per [advance] (spread over the prompt's first frames, never one long stall): [frames] 0 = at rest, 1 + i = the
     * fight's motion at i / [STEPS] (0 = the fight shown with the bar still laid, [STEPS] = slid away). Each [scale] of 1240 x 1080.
     */
    class AskFrames(internal val ctx: Context, internal val bar: CastStripModel.Bar, val scale: Float) {
        val frames = arrayOfNulls<Bitmap>(STEPS + 2)
        internal var step = 0
        internal var rest: Map<String, android.graphics.RectF>? = null
        internal var hid: Map<String, android.graphics.RectF>? = null
        val done: Boolean get() = step >= frames.size
        /** the next frame; false = no game documents yet (nothing real to draw) */
        fun advance(): Boolean = askStep(this)
        internal fun next(b: Bitmap?) { frames[step] = b; step++ }
        internal fun restart() { step = 0; rest = null; hid = null }
        /** the frames go (the prompt closed) */
        fun drop() { for (i in frames.indices) frames[i] = null; if (step > 0 || pass > 0) askRestore() }
        /** a second pass a second in (the minimap's tiles decode on their own thread: the first frames may lack them) */
        internal var pass = 0
        internal var startedAt = 0L
        /** both passes drawn */
        val finished: Boolean get() = done && pass > 0

        private val pBmp = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val dst = android.graphics.RectF()
        private val pEdge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f; color = Theme.bronzeDim }

        /**
         * the picture in [r] at [tMs] since the prompt opened ([SettingsPreview.askPhase]'s loop): at rest, the fight's real red edge
         * and the game's "In combat" tag (its crossed swords) coming, the bar sliding down behind the Home bar while the columns grow,
         * then back. One frame is drawn per call until all are there; a frame not drawn yet shows the nearest one before it.
         */
        fun draw(c: Canvas, r: android.graphics.RectF, tMs: Long) {
            if (!done) advance()
            val p = SettingsPreview.askPhase(tMs)
            val k = Math.round(p.slide * STEPS)
            fun pick(i: Int): Bitmap? { for (j in i downTo 0) frames[j]?.let { return it }; return null }
            val base = if (p.slide <= 0f && p.fight <= 0f) pick(0) else if (p.slide <= 0f) pick(0) else pick(1 + k)
            val bw = base?.width ?: return
            val bh = base.height
            val sc = minOf(r.width() / bw, r.height() / bh)
            dst.set(r.centerX() - bw * sc / 2f, r.top, r.centerX() + bw * sc / 2f, r.top + bh * sc)
            pBmp.alpha = 255; c.drawBitmap(base, null, dst, pBmp)
            // the fight's first moment: the fight's frame (its red edge) fades in over the rest frame while the bar is still laid
            if (p.slide <= 0f && p.fight > 0f) pick(1)?.let { pBmp.alpha = (255 * p.fight).toInt(); c.drawBitmap(it, null, dst, pBmp); pBmp.alpha = 255 }
            if (p.fight > 0f) {
                // Home's own fight edge (its red painter's glow) and the game's "In combat" tag with its crossed swords, at the picture's size
                c.save(); c.clipRect(dst); UiKit.combatGlow(c, dst, 0.7f * p.fight, 40f * sc); c.restore()
                val k = 0.6f
                c.save(); c.translate(dst.centerX(), dst.top); c.scale(k, k)
                UiKit.combatTag(c, android.graphics.RectF(-dst.width() / 2f / k, 0f, dst.width() / 2f / k, dst.height() / k), (255 * p.fight).toInt())
                c.restore()
            }
            c.drawRect(dst, pEdge)                                                // the screen's edge
        }
        companion object { const val STEPS = 10 }
    }

    private fun askStep(a: AskFrames): Boolean {
        val f = full ?: return false
        val now = SystemClock.uptimeMillis()
        if (a.startedAt == 0L) a.startedAt = now
        // once more after a second, with the minimap tiles that decoded meanwhile (their own thread)
        if (a.done && a.pass == 0 && now - a.startedAt >= 1_000L) { a.pass = 1; a.restart() }
        if (a.done) return true
        val t = tree ?: build(a.ctx).also { tree = it }
        if (askDirty && a.step > 0) a.restart()
        askDirty = false
        val look = HomeView.Companion.Look(HomeSettings.rest, HomeSettings.combatGrow, HomeSettings.questOverMap, HomeSettings.swingTimer,
            castSide = CastStripModel.Side.BOTH)
        try {
            HomeView.renderLook = look
            when (val s = a.step) {
                0 -> {
                    feed(t, f, false, null)
                    t.home.onCastBar(a.bar)
                    t.home.previewSettle(look); lay(t)
                    a.rest = t.home.previewBoxes()
                    a.next(askDraw(a, t))
                }
                1 -> {
                    feed(t, f, true, null)
                    t.home.onCastBar(a.bar)
                    t.home.previewSettle(look); lay(t)
                    val h = t.home.previewBoxes()
                    a.hid = h
                    t.home.previewBetween(a.rest ?: h, h, 0f)
                    a.next(askDraw(a, t))
                }
                else -> {
                    val r = a.rest; val h = a.hid
                    if (r == null || h == null) { a.restart(); return true }
                    t.home.previewBetween(r, h, (s - 1).toFloat() / AskFrames.STEPS)
                    a.next(askDraw(a, t))
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("WCHOME", "cast bar picture: ${e.javaClass.simpleName}: ${e.message}")
            a.next(null)
        } finally {
            HomeView.renderLook = null
        }
        if (a.done) askRestore()
        return true
    }

    /** the stage's Home back to the player's own bar (the Settings pictures read it again), nothing cached from the picture's run */
    private fun askRestore() {
        val t = tree ?: return
        t.home.onCastBar(CastStripModel.Live.bar ?: CastStripModel.Bar(
            (0 until CastStripModel.SLOTS).map { i -> CastStripModel.Slot(i, null, null, null, false) }, null, false, "", null))
        cache.clear(); askDirty = true
    }

    /** Home and the Home bar under it, at the picture's scale */
    private fun askDraw(a: AskFrames, t: Tree): Bitmap {
        val b = Bitmap.createBitmap((W * a.scale).toInt(), (1080 * a.scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(a.scale, a.scale)
        c.drawColor(Theme.ink)
        t.home.draw(c)
        val st = stripView ?: Strip(a.ctx) { }.also { stripView = it }
        st.active = Room.HOME
        st.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        st.layout(0, 0, W, Strip.HEIGHT)
        c.translate(0f, H.toFloat()); st.draw(c)
        return b
    }

    private fun build(ctx: Context): Tree {
        val holder = FrameLayout(ctx)
        val map = MapView(ctx)
        val cast = CastView(ctx) { }
        val chatView = ChatView(ctx) { }
        holder.addView(map); holder.addView(cast); holder.addView(chatView)
        val home = HomeView(ctx, map, cast, chatView) { }
        val t = Tree(home, cast, map)
        lay(t)
        home.borrow()
        return t
    }

    private fun lay(t: Tree) {
        t.home.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        t.home.layout(0, 0, W, H)
    }

    /** a deep copy with every "pad" key gone, at any depth (the add-on's tap cells: the stage must never be able to tap) */
    private fun strip(o: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in o.keys()) {
            if (k == "pad") continue
            out.put(k, when (val v = o.opt(k)) { is JSONObject -> strip(v); is JSONArray -> strip(v); else -> v })
        }
        return out
    }
    private fun strip(a: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until a.length()) out.put(when (val v = a.opt(i)) { is JSONObject -> strip(v); is JSONArray -> strip(v); else -> v })
        return out
    }
}
