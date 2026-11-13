package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * GitHub #43 cb4, THREE AT A TIME (the user, 2026-11-13: "make them only show 3 abilities at a time for left or right, and swipe to the
 * other 3. they should be the same size as the both option"): the real [CastStripView] on its real touch route (a tap on page 2 casts
 * slot 4, a swipe either way flips the page and never moves the bar), and with CASTBAR_SHOTS_DIR set the whole Home page at rest
 * (1240 x 1080, the Thor's bottom screen) with the bar Left page 1, Left page 2, Right page 1, Both, as pages_<name>.png and the bar
 * alone at 2x as pages_<name>_bar.png, plus the Settings row's picture (pages_settings_<side>.png). CLOCK_ART_ROOT = an art root laid
 * out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarPagesShotsTest {
    private val out: File? = System.getenv("CASTBAR_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val L = CastStripModel.Side.LEFT
    private val R = CastStripModel.Side.RIGHT
    private val BOTH = CastStripModel.Side.BOTH

    @After fun down() { CastStripModel.Live.page = 0 }

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    /** six spells (a level-6 Shaman's and a few more), slot 5 waiting for the fight's end, slot 2 cooling */
    private val doc = JSONObject("""{"type":"castbar","v":"cb3","n":6,"pick":6,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"q":1},{"s":6,"id":2484,"i":136102,"c":5}]}""")
    private val bar get() = CastStripModel.parse(doc)!!

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private class Rig(val v: CastStripView) { val taps = ArrayList<Int>(); val flips = ArrayList<Int>() }

    /** the bar alone at Home's place for [side] on the Thor's 1240-wide bottom screen */
    private fun rig(side: CastStripModel.Side): Rig {
        lateinit var r: Rig
        val v = CastStripView(app, { r.taps.add(it) }) { r.flips.add(it) }
        r = Rig(v)
        v.side = side
        v.update(bar)
        val b = CastStripModel.place(side, 1240f, 1080f - Strip.HEIGHT, UiKit.MODULE_GAP)!!
        lay(v, b.w.toInt(), b.h.toInt())
        return r
    }

    private var t0 = 0L
    private fun touch(v: View, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), action, x, y, 0)
        v.onTouchEvent(e); e.recycle()
    }
    private fun tap(v: CastStripView, k: Int) {
        val r = v.slotRect(k); t0 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        touch(v, MotionEvent.ACTION_UP, r.centerX() + 3f, r.centerY() + 2f)
    }
    private fun swipe(v: CastStripView, dx: Float) {
        val r = v.slotRect(1); t0 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY())
        for (s in 1..4) { ShadowSystemClock.advanceBy(Duration.ofMillis(40)); touch(v, MotionEvent.ACTION_MOVE, r.centerX() + dx * s / 4f, r.centerY()) }
        touch(v, MotionEvent.ACTION_UP, r.centerX() + dx, r.centerY())
    }

    @Test fun threeAtATimeAtBothsSize() {
        val l = rig(L); val b = rig(BOTH)
        assertEquals(b.v.slotRect(0).width(), l.v.slotRect(0).width(), 0.01f)
        assertEquals(CastStripModel.MAX_SIDE, l.v.slotRect(0).width(), 0.01f)
        assertEquals(b.v.height + CastStripModel.DOTS_H.toInt(), l.v.height)        // Left / Right: the dots' band under the slots
    }

    @Test fun aTapOnPageTwoCastsSlotFour() {
        val r = rig(L)
        tap(r.v, 0)
        assertEquals(listOf(0), r.taps)                                      // page 1: slot 1
        swipe(r.v, -200f)
        assertEquals(1, r.v.page); assertEquals(listOf(1), r.flips)
        assertEquals(listOf(0), r.taps)                                      // the swipe cast nothing
        tap(r.v, 0); tap(r.v, 2)
        assertEquals(listOf(0, 3, 5), r.taps)                                // page 2: slots 4 and 6
        tap(r.v, 1)
        assertEquals(listOf(0, 3, 5), r.taps)                                // slot 5 waits for the fight's end: no tap
    }

    @Test fun aSwipeEitherWayFlipsAndNeverMovesTheBar() {
        HomeSettings.init(app)
        HomeSettings.castBarSide = R
        val r = rig(R)
        swipe(r.v, 200f)                                                     // toward the page's middle: cb3 moved it Left; now it flips
        assertEquals(1, r.v.page)
        swipe(r.v, 200f)                                                     // the same way again: back to 1-3
        assertEquals(0, r.v.page)
        swipe(r.v, -200f)
        assertEquals(1, r.v.page)
        assertEquals(listOf(1, 0, 1), r.flips)
        assertEquals(R, HomeSettings.castBarSide); assertEquals(R, r.v.side)
        swipe(r.v, 12f)                                                      // a wobble: no flip
        assertEquals(1, r.v.page)
        val both = rig(BOTH)
        swipe(both.v, -200f)
        assertTrue(both.flips.isEmpty())                                     // Both: one page, nothing flips
        // the page is the app run's: a new bar view (Home laid again) shows the same page
        assertEquals(1, rig(L).v.page)
    }

    // ---------------------------------------------------------------- the renders

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(200); ShadowLooper.idleMainLooper() }

    private fun settleLook(home: HomeView) {
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
    }

    private fun page(side: CastStripModel.Side): HomeView {
        HomeSettings.init(app)
        HomeSettings.castBarSide = side
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        val h = 1080 - Strip.HEIGHT
        lay(home, 1240, h)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
        home.tick()
        settleLook(home)
        CastStripModel.Live.set(bar)
        home.onCastBar(bar)
        home.onCastCd(CastStripModel.parseCd(JSONObject("""{"type":"castcd","v":"cb3","k":{"2":{"d":8,"l":5}}}"""))!!)
        settleLook(home)
        lay(home, 1240, h)
        return home
    }

    private fun shot(side: CastStripModel.Side, pg: Int, name: String) {
        val d = out ?: return
        d.mkdirs()
        CastStripModel.Live.page = pg
        val home = page(side)
        val strip = Strip(app) { }
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            lay(home, 1240, 1080 - Strip.HEIGHT)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            home.draw(c)
            lay(strip, 1240, Strip.HEIGHT)
            c.save(); c.translate(0f, (1080 - Strip.HEIGHT).toFloat()); strip.draw(c); c.restore()
            if (pass == 0) settle()
        }
        File(d, "pages_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val box = CastStripModel.place(side, 1240f, 1080f - Strip.HEIGHT, UiKit.MODULE_GAP)!!
        val crop = Bitmap.createBitmap(b, maxOf(0, box.l.toInt() - 8), maxOf(0, box.t.toInt() - 8),
            minOf(1240, box.r.toInt() + 8) - maxOf(0, box.l.toInt() - 8), minOf(1080 - Strip.HEIGHT, box.b.toInt() + 8) - maxOf(0, box.t.toInt() - 8))
        Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true).let { s ->
            File(d, "pages_${name}_bar.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** the slide frozen part-way (the finger dragged a third of the bar left): the page and the next three beside it */
    private fun midSlide(name: String) {
        val d = out ?: return
        CastStripModel.Live.page = 0
        val r = rig(L)
        r.v.freezeOffsetForTest(-r.v.width / 3f)
        val b = Bitmap.createBitmap(r.v.width, r.v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); r.v.draw(c)
        Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, true).let { s ->
            File(d, "pages_$name.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun settings(side: Int, name: String) {
        val d = out ?: return
        val b = Bitmap.createBitmap(620, 700, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink)
        SettingsPreview.draw(c, RectF(10f, 10f, 610f, 690f), "Tap-to-cast bar", SettingsPreview.State(castBar = side), 0L,
            SettingsPreview.Glyph { _, _, _, _, _, _ -> })
        File(d, "pages_settings_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        assumeTrue(out != null)
        artRoot()
        shot(L, 0, "left_p1")
        shot(L, 1, "left_p2")
        shot(R, 0, "right_p1")
        shot(BOTH, 0, "both")
        midSlide("left_slide")
        settings(1, "left"); settings(2, "right"); settings(3, "both")
    }
}
