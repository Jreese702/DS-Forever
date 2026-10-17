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
        val doc = strip(f)
        doc.optJSONObject("actionbar")?.optJSONObject("hudS")?.let { hs ->
            hs.put("c", fight)
            // a fight's swing line needs a swing: one in progress when the copy has none (the game's own clock, hudS.g)
            if (fight && !hs.has("sw")) hs.optDouble("g", Double.NaN).takeIf { !it.isNaN() }?.let { g ->
                hs.put("sw", JSONObject().put("k", 0).put("s", g - 0.8).put("d", 2.6))
            }
        }
        val bmp = try {
            HomeView.renderLook = look
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
