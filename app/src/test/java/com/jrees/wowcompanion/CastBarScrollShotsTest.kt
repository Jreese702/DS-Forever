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
import org.junit.Assert.assertFalse
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
 * GitHub #43 cb5, A SCROLL BAR (the user, 2026-11-13: "I dont want them shown as pages, I want it to be a literal scroll bar that can
 * stop in any position"; the picks: 4 in view, "4 with an arrow on the sides indicating if there are abilities that way"): the real
 * [CastStripView] on its real touch route (the strip follows the finger and stops anywhere, a drag never casts, a fling glides and a
 * finger on it stops it without a cast, a tap casts the slot under the finger at the scroll it is at, Both never scrolls, the scroll
 * kept for the app's run), and with CASTBAR_SHOTS_DIR set the whole Home page (1240 x 1080, the Thor's bottom screen) with the bar
 * Left at its start, stopped between slots, at its end, Right at its start, Both, as scroll_<name>.png and the bar alone at 2x as
 * scroll_<name>_bar.png; the Bind box's Cast bar tab (scroll_bind_tab.png) and the Settings row's picture (scroll_settings_<side>.png).
 * CLOCK_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarScrollShotsTest {
    private val out: File? = System.getenv("CASTBAR_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val L = CastStripModel.Side.LEFT
    private val R = CastStripModel.Side.RIGHT
    private val BOTH = CastStripModel.Side.BOTH

    @After fun down() { CastStripModel.Live.pos = 0f }

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    /** eight spells (cb6; a level-6 Shaman's and a few more), slot 5 waiting for the fight's end, slot 2 cooling */
    private val doc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"q":1},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")
    private val bar get() = CastStripModel.parse(doc)!!

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private class Rig(val v: CastStripView) { val taps = ArrayList<Int>() }

    /** the bar alone at Home's place for [side] on the Thor's 1240-wide bottom screen */
    private fun rig(side: CastStripModel.Side): Rig {
        lateinit var r: Rig
        val v = CastStripView(app) { r.taps.add(it) }
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
    private fun tapAt(v: CastStripView, x: Float, y: Float) {
        t0 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y)
        ShadowSystemClock.advanceBy(Duration.ofMillis(60))
        touch(v, MotionEvent.ACTION_UP, x + 3f, y + 2f)
    }
    private fun tap(v: CastStripView, i: Int) { val r = v.slotRect(i); tapAt(v, r.centerX(), r.centerY()) }
    /** a drag of [dx] in 4 moves 40 ms apart; [hold] = the finger rests 150 ms before it lifts (no fling) */
    private fun drag(v: CastStripView, dx: Float, hold: Boolean) {
        val x0 = v.width / 2f; val y = v.height / 2f; t0 = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x0, y)
        for (s in 1..4) { ShadowSystemClock.advanceBy(Duration.ofMillis(40)); touch(v, MotionEvent.ACTION_MOVE, x0 + dx * s / 4f, y) }
        if (hold) { ShadowSystemClock.advanceBy(Duration.ofMillis(150)); touch(v, MotionEvent.ACTION_MOVE, x0 + dx, y) }
        touch(v, MotionEvent.ACTION_UP, x0 + dx, y)
    }

    @Test fun fourInViewAtBothsSize() {
        val l = rig(L); val b = rig(BOTH)
        assertEquals(b.v.slotRect(0).width(), l.v.slotRect(0).width(), 0.01f)
        assertEquals(CastStripModel.MAX_SIDE, l.v.slotRect(0).width(), 0.01f)
        assertEquals(b.v.height, l.v.height)                                     // no dots band: one height
        val w = l.v.width.toFloat()
        assertTrue(l.v.slotRect(3).right <= w - CastStripModel.PAD)              // slot 4 in view, slot 5 beyond the edge
        assertTrue(l.v.slotRect(4).left >= CastStripModel.window(w).second)
    }

    @Test fun theStripFollowsTheFingerAndStopsAnywhere() {
        val r = rig(L)
        val p = CastStripModel.pitch(L, r.v.width.toFloat())
        drag(r.v, -250f, hold = true)                                          // 4 moves of 62.5: the strip follows from the first (past 24 px)
        assertEquals(187.5f, r.v.scroll, 0.5f)
        assertFalse(r.v.flinging)
        assertTrue("stopped between slots", (r.v.scroll / p) % 1f in 0.05f..0.95f)
        assertTrue(r.taps.isEmpty())                                           // a drag never casts
        // a tap now casts the slot under the finger at this scroll: the leftmost whole one is slot 3 (index 2)
        val i = CastStripModel.slotAt(L, r.v.slotRect(2).centerX(), r.v.height / 2f, r.v.width.toFloat(), r.v.height.toFloat(), r.v.scroll)
        assertEquals(2, i)
        tap(r.v, 2)
        assertEquals(listOf(2), r.taps)
        // to the end, held there (cb6: slots 5-8 in view): slots 8 and 6 cast, the queued slot 5 does not
        drag(r.v, -600f, hold = true)
        assertEquals(CastStripModel.maxScroll(L, r.v.width.toFloat()), r.v.scroll, 0.01f)
        assertEquals(listOf(4, 5, 6, 7), CastStripModel.inView(L, r.v.width.toFloat(), r.v.height.toFloat(), r.v.scroll))
        tap(r.v, 7); tap(r.v, 5); tap(r.v, 4)
        assertEquals(listOf(2, 7, 5), r.taps)
        // back to the start
        drag(r.v, 900f, hold = true)
        assertEquals(0f, r.v.scroll, 0f)
        tap(r.v, 0)
        assertEquals(listOf(2, 7, 5, 0), r.taps)
    }

    @Test fun aFlingGlidesAndAFingerStopsItWithoutACast() {
        val r = rig(R)
        drag(r.v, -120f, hold = false)                                         // a quick flick
        assertTrue(r.v.flinging)
        val at = r.v.scroll
        ShadowSystemClock.advanceBy(Duration.ofMillis(60)); r.v.stepFlingForTest(SystemClock.uptimeMillis())
        assertTrue(r.v.scroll > at)                                            // it glides on
        // a finger on the gliding strip: it stops there, and the tap casts nothing
        tap(r.v, 1)
        assertFalse(r.v.flinging)
        assertTrue(r.taps.isEmpty())
        val held = r.v.scroll
        ShadowSystemClock.advanceBy(Duration.ofMillis(300)); r.v.stepFlingForTest(SystemClock.uptimeMillis())
        assertEquals(held, r.v.scroll, 0f)
        // the next tap casts
        tap(r.v, 3)
        assertEquals(listOf(3), r.taps)
    }

    @Test fun bothNeverScrollsAndTheScrollIsTheAppRuns() {
        HomeSettings.init(app)
        HomeSettings.castBarSide = R
        val both = rig(BOTH)
        drag(both.v, -300f, hold = false)
        assertEquals(0f, both.v.scroll, 0f); assertFalse(both.v.flinging)
        assertEquals(R, HomeSettings.castBarSide)                              // a drag never moves the bar
        val l = rig(L)
        drag(l.v, -100f, hold = true)
        val kept = l.v.scroll
        assertTrue(kept > 0f)
        assertEquals(kept, rig(L).v.scroll, 0.01f)                             // a new bar view (Home laid again) is where it was
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

    private fun save(b: Bitmap, name: String) {
        File(out!!, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the page with the bar scrolled to [pos] slots' shares */
    private fun shot(side: CastStripModel.Side, pos: Float, name: String) {
        val d = out ?: return
        d.mkdirs()
        CastStripModel.Live.pos = pos
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
        save(b, "eight_$name.png")
        val box = CastStripModel.place(side, 1240f, 1080f - Strip.HEIGHT, UiKit.MODULE_GAP)!!
        val crop = Bitmap.createBitmap(b, maxOf(0, box.l.toInt() - 8), maxOf(0, box.t.toInt() - 8),
            minOf(1240, box.r.toInt() + 8) - maxOf(0, box.l.toInt() - 8), minOf(1080 - Strip.HEIGHT, box.b.toInt() + 8) - maxOf(0, box.t.toInt() - 8))
        save(Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true), "eight_${name}_bar.png")
    }

    /** the Bind box's Cast bar tab: the bar's 8 slots (cb6: one row, two groups of 4) with their spells' icons, the cursor on slot 7
     *  with the box's spell */
    private fun bindTab() {
        val d = out ?: return
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))                    // past the box's 1.5 s notes (the clock starts near 0)
        HomeSettings.init(app)
        HomeSettings.castBarSide = L
        CastStripModel.Live.set(bar)
        val o = SpellsOverlay(app, otherReader = { true })
        o.onBook(SpellsModel.parse(JSONObject("""{"type":"spells","v":"s8","s":{"lvl":6,"lines":[{"k":1,"n":"Elemental","i":136048}],
            "items":[{"t":1,"n":"Earth Shock","id":8042,"a":8042,"i":136026,"ln":1},{"t":1,"n":"Rockbiter Weapon","id":8017,"a":8017,"i":136086,"ln":1}]}}"""))!!)
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b56","ctl":{"on":true,"mode":"bind","bind":8017,"bcb":7}}"""))!!)
        lay(o, 1240, 1080 - Strip.HEIGHT)
        val b = Bitmap.createBitmap(1240, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) { val c = Canvas(b); c.drawColor(Theme.ink); o.draw(c); if (pass == 0) settle() }
        save(b, "eight_bind_tab.png")
    }

    private fun settings(side: Int, name: String) {
        val d = out ?: return
        val b = Bitmap.createBitmap(620, 700, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink)
        SettingsPreview.draw(c, RectF(10f, 10f, 610f, 690f), "Tap-to-cast bar", SettingsPreview.State(castBar = side), 0L,
            SettingsPreview.Glyph { _, _, _, _, _, _ -> })
        save(b, "eight_settings_$name.png")
    }

    @Test fun shots() {
        assumeTrue(out != null)
        artRoot()
        shot(L, 0f, "left_start")
        shot(L, 1.45f, "left_mid")                                             // stopped between slots
        shot(L, 4f, "left_end")
        shot(R, 0f, "right_start")
        shot(BOTH, 0f, "both")
        bindTab()
        settings(1, "left"); settings(2, "right"); settings(3, "both")
    }
}
