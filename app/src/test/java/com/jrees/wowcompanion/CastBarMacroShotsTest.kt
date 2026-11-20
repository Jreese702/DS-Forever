package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 * cb13 A PLAYER'S MACRO ON THE TAP-TO-CAST BAR (GitHub #553, a player: "I cannot assign macros to the bottom screen cast bar"; ActionBar
 * cb13, Book b69): the real [CastStripView] takes a tap on a macro slot (its cell) and none on a gone macro's; the Macros room's Bind
 * box ([SpellsOverlay], hosted: `via` macros, `bitem` mac) shows its Cast bar tab only while the add-on takes macros (`mac` = 1); a bag
 * item's box never. With CASTBAR_MACRO_SHOTS_DIR set: Home (1240 x 1080, the Thor's bottom screen) with the bar on Both holding two
 * macros and a gone one (macro_home_both.png, the bar alone at 2x macro_home_both_bar.png) and the Bind box's Cast bar tab for a macro
 * (macro_bind_tab.png). CLOCK_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarMacroShotsTest {
    private val out: File? = System.getenv("CASTBAR_MACRO_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val BOTH = CastStripModel.Side.BOTH

    @After fun down() { CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null; HomeSettings.init(app); HomeSettings.castBarSide = CastStripModel.Side.OFF }

    private fun artRoot() {
        val root = System.getenv("CLOCK_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    /** a level-6 Shaman's bar: spells, the player's macros "Bravo" (slot 3) and "Heal" (slot 4, a #showtooltip picture), a gone one (6) */
    private val doc = JSONObject("""{"type":"castbar","v":"cb13","n":8,"any":1,"mac":1,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},
        {"s":3,"k":"macro","n":"Bravo","i":134400,"c":2},{"s":4,"k":"macro","n":"Heal","i":136052,"c":3},
        {"s":5,"id":331,"i":136052,"c":4},{"s":6,"k":"macro","n":"Old","miss":1,"q":1},
        {"s":7,"k":"item","id":6948,"n":"Hearthstone","i":134414,"ct":1,"c":6}]}""")
    private val bar get() = CastStripModel.parse(doc)!!

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(200); ShadowLooper.idleMainLooper() }

    @Test fun aMacroSlotTakesItsTapAGoneOneNone() {
        val b = bar
        assertEquals(CastStripModel.ROW to 2, CastStripModel.tapCell(b, 2))
        assertEquals(CastStripModel.ROW to 3, CastStripModel.tapCell(b, 3))
        assertNull("a gone macro: no tap", CastStripModel.tapCell(b, 5))
        val taps = ArrayList<Int>()
        HomeSettings.init(app); HomeSettings.castBarSide = BOTH
        val v = CastStripView(app) { taps.add(it) }
        v.side = BOTH
        v.update(b)
        val pl = CastStripModel.place(BOTH, 1240f, 1080f - Strip.HEIGHT, UiKit.MODULE_GAP)!!
        lay(v, pl.w.toInt(), pl.h.toInt())
        for (i in listOf(2, 5)) {
            val r = v.slotRect(i)
            val t = android.os.SystemClock.uptimeMillis()
            v.onTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0))
            v.onTouchEvent(android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0))
        }
        assertEquals("the macro on slot 3 casts; the gone one on slot 6 does not", listOf(2), taps)
    }

    private fun three(o: SpellsOverlay): Boolean =
        SpellsOverlay::class.java.getDeclaredMethod("three").apply { isAccessible = true }.invoke(o) as Boolean

    private fun box(item: String): SpellsOverlay {
        val o = SpellsOverlay(app, otherReader = { true })
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b69","ctl":{"on":true,"via":"macros","mode":"bind","bind":2,
            "bcb":3,"bitem":$item}}"""))!!)
        return o
    }
    private val macItem = """{"id":2,"n":"Bravo","i":134400,"mac":true}"""

    @Test fun theMacrosBoxShowsTheCastBarTabOnlyWhileTheAddOnTakesMacros() {
        HomeSettings.init(app); HomeSettings.castBarSide = BOTH
        CastStripModel.Live.set(bar)
        assertTrue("cb13: a macro's box has the Cast bar tab", three(box(macItem)))
        assertTrue("b70 (D8): a bag item's box has Tap to cast too", three(box("""{"id":6948,"n":"Hearthstone","i":134414}""")))
        CastStripModel.Live.set(CastStripModel.parse(JSONObject("""{"type":"castbar","v":"cb12","n":8,"any":1,"pp":[39,19],"e":[]}"""))!!)
        assertFalse("a cb12 add-on: no Cast bar tab for a macro", three(box(macItem)))
        HomeSettings.castBarSide = CastStripModel.Side.OFF
        CastStripModel.Live.set(bar)
        assertTrue("b70 (D2): the bar setting Off: the tab stays (dimmed, Turn on)", three(box(macItem)))
    }

    private fun page(): HomeView {
        HomeSettings.init(app)
        HomeSettings.castBarSide = BOTH
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
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        val settleLook = HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }
        settleLook?.invoke(home, look)
        CastStripModel.Live.set(bar)
        home.onCastBar(bar)
        settleLook?.invoke(home, look)
        lay(home, 1240, h)
        return home
    }

    private fun save(b: Bitmap, name: String) { File(out!!, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    @Test fun shots() {
        val d = out ?: return
        artRoot()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        CastStripModel.Live.side = null; CastStripModel.Live.pos = 0f
        val home = page()
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
        save(b, "macro_home_both.png")
        val pl = CastStripModel.place(BOTH, 1240f, 1080f - Strip.HEIGHT, UiKit.MODULE_GAP)!!
        val l = maxOf(0, pl.l.toInt() - 8); val t = maxOf(0, pl.t.toInt() - 8)
        val crop = Bitmap.createBitmap(b, l, t, minOf(1240, pl.r.toInt() + 8) - l, minOf(1080 - Strip.HEIGHT, pl.b.toInt() + 8) - t)
        save(Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true), "macro_home_both_bar.png")
        // the Macros room's Bind box (hosted), its Cast bar tab with the cursor on slot 3 (where Bravo already sits)
        val o = box(macItem)
        lay(o, 1240, 1080 - Strip.HEIGHT)
        val bb = Bitmap.createBitmap(1240, 1080 - Strip.HEIGHT, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) { val c = Canvas(bb); c.drawColor(Theme.ink); o.draw(c); if (pass == 0) settle() }
        save(bb, "macro_bind_tab.png")
        assertTrue(File(d, "macro_bind_tab.png").length() > 0)
    }
}
