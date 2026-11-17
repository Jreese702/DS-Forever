package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * sw-dual (the user, 2026-11-16: "Dual weilding needs split swing timers for each weapon"): Home's swing line drawn by the real
 * [HomeView] + [CastView] from made-up `hudS` documents - one swing (today's line), a dual-wield fight (hudS.sw the main hand, hudS.so
 * the off hand: the thin dimmer bar right under it), the "Same size" fight (the strip under the buttons) and a cast over a dual-wield
 * fight. PNGs only when SWING_SHOTS_DIR is set; SWING_ART_ROOT = an art root laid out like the app's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SwingDualShotsTest {
    private val out: File? = System.getenv("SWING_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT
    private val G = 5000.0

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun page(grow: Boolean): Pair<HomeView, CastView> {
        HomeSettings.init(app)
        HomeSettings.combatGrow = grow
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        return home to cast
    }

    private fun sw(k: Int, ago: Double, d: Double) = JSONObject().put("k", k).put("s", G - ago).put("d", d)

    private fun shot(name: String, grow: Boolean, mh: JSONObject?, oh: JSONObject?, cs: JSONObject? = null) {
        val d = out ?: return
        d.mkdirs()
        val (home, cast) = page(grow)
        val st = JSONObject().put("g", G).put("c", true).put("aa", true)
        if (mh != null) st.put("sw", mh)
        if (oh != null) st.put("so", oh)
        if (cs != null) st.put("cs", cs)
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", st)
        home.onFull(JSONObject().put("type", "full").put("actionbar", ab), true)
        cast.update(ab)
        home.tick(); ShadowLooper.idleMainLooper()
        lay(home, W, H)
        val bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            home.draw(c)
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // the band's top, enlarged: the two bars and the buttons under them
        val top = Bitmap.createBitmap(bm, 0, 0, W, 160)
        File(d, "${name}_top.png").outputStream().use { Bitmap.createScaledBitmap(top, W, 320, true).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Home's own band (the CastView as Home laid it for the fight), drawn alone */
    private fun band(grow: Boolean, mh: JSONObject?, oh: JSONObject?, cs: JSONObject? = null): Pair<Bitmap, CastView> {
        val (home, cast) = page(grow)
        val st = JSONObject().put("g", G).put("c", true).put("aa", true)
        if (mh != null) st.put("sw", mh)
        if (oh != null) st.put("so", oh)
        if (cs != null) st.put("cs", cs)
        val ab = JSONObject().put("hud", JSONObject().put("ok", true)).put("hudS", st)
        home.onFull(JSONObject().put("type", "full").put("actionbar", ab), true)
        cast.update(ab)
        home.tick(); ShadowLooper.idleMainLooper()
        lay(home, W, H)
        val bm = Bitmap.createBitmap(cast.width, cast.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Theme.ink)
        cast.draw(c)
        return bm to cast
    }

    private fun rowsDiffer(a: Bitmap, b: Bitmap, y0: Int, y1: Int, x0: Int, x1: Int): Int {
        var n = 0
        for (y in maxOf(0, y0) until minOf(a.height, y1)) for (x in maxOf(0, x0) until minOf(a.width, x1)) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    /** the off hand's line is drawn inside the bar's own box, and nothing outside that box moves (the band's layout is unchanged) */
    @Test fun twoLinesInTheBarsOwnBox() {
        for (grow in listOf(true, false)) {
            val (one, cv) = band(grow, sw(0, 1.6, 2.6), null)
            val (two, _) = band(grow, sw(0, 1.6, 2.6), sw(1, 0.5, 1.8))
            assertEquals(one.width, two.width); assertEquals(one.height, two.height)
            // CastBarPlace.bar (internal to the app's module): the top edge in a Grow fight, the strip under the buttons otherwise
            val k = cv.width / 1240f; val ch = cv.height.toFloat()
            val g = if (grow) floatArrayOf(150f * k, 2f * k, cv.width - 150f * k, 30f * k) else floatArrayOf(0f, ch - 16f * k, cv.width.toFloat(), ch - 2f * k)
            val inBox = rowsDiffer(one, two, g[1].toInt(), g[3].toInt() + 1, g[0].toInt(), g[2].toInt())
            assertTrue("grow=$grow: the off hand's line is drawn in the bar's box ($inBox px differ)", inBox > 200)
            // the spark stands 3.5 bar units past the bar: the rest of the band, a margin past that, is pixel for pixel the same
            val m = 14
            val above = rowsDiffer(one, two, 0, g[1].toInt() - m, 0, one.width)
            val below = rowsDiffer(one, two, g[3].toInt() + m, one.height, 0, one.width)
            assertEquals("grow=$grow: nothing else on the band moves (above)", 0, above)
            assertEquals("grow=$grow: nothing else on the band moves (below)", 0, below)
            // the off hand gone (hudS without so): one line again, exactly today's
            val (back, _) = band(grow, sw(0, 1.6, 2.6), null)
            assertEquals("grow=$grow: one hand = today's line", 0, rowsDiffer(one, back, 0, one.height, 0, one.width))
        }
        // a cast over a dual-wield fight takes the whole box, as before (the same picture as the cast over one line)
        val cs = JSONObject().put("k", "c").put("s", G - 0.5).put("e", G + 1.5).put("nm", "Sinister Strike")
        val (c1, _) = band(true, sw(0, 1.6, 2.6), null, cs)
        val (c2, _) = band(true, sw(0, 1.6, 2.6), sw(1, 0.5, 1.8), cs)
        assertEquals("a cast hides both lines as it hid the one", 0, rowsDiffer(c1, c2, 0, c1.height, 0, c1.width))
    }

    @Test fun shots() {
        if (out == null) return
        System.getenv("SWING_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        Tr.init(app)
        shot("fight_one", true, sw(0, 1.2, 2.6), null)
        shot("fight_dual", true, sw(0, 1.6, 2.6), sw(1, 0.5, 1.8))
        shot("same_size_dual", false, sw(0, 1.6, 2.6), sw(1, 0.5, 1.8))
        shot("fight_dual_cast", true, sw(0, 1.6, 2.6), sw(1, 0.5, 1.8),
            JSONObject().put("k", "c").put("s", G - 0.5).put("e", G + 1.5).put("nm", "Sinister Strike"))
    }
}
