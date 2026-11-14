package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * fix/castbar-slides (the user on 1.0.31: "When holding LB it slides right but it should only slide the direction if its on that half. If
 * its set to both it can stay in place. The rb plus l2 class pet attack bar is also behaving weird now with the right side menu stuff
 * sliding the both bar to the right and the circle going the full screen size"): the real [HomeView] with the tap-to-cast bar Left /
 * Right / Both under every side slide - the LB picture (pauseSlide with LbPartyView.SIDE_PX), RB + RT's and LB + LT's class wheels
 * (classLayerShow), the middle item / spell wheel (wheelSlot) and the pause wheel's full slide - pinning the bar's offset per side and the
 * class wheel's size. With SLIDES_SHOTS_DIR set (and CLOCK_ART_ROOT for the art), the pictures of each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarSlidesShotsTest {
    private val out: File? = System.getenv("SLIDES_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val L = CastStripModel.Side.LEFT
    private val R = CastStripModel.Side.RIGHT
    private val B = CastStripModel.Side.BOTH
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    @After fun down() { CastStripModel.Live.pos = 0f; CastStripModel.Live.side = null; HomeSettings.init(app); HomeSettings.castBarSide = CastStripModel.Side.OFF }

    private val doc = JSONObject("""{"type":"castbar","v":"cb6","n":8,"pick":8,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5},
        {"s":7,"id":8018,"i":136015,"c":6},{"s":8,"id":5730,"i":135824,"c":7}]}""")
    private val bar get() = CastStripModel.parse(doc)!!

    /** a class radial each side (a pet bar: Attack, Follow, Stay, Passive, Defensive, Aggressive) */
    private fun radial(): JSONObject {
        val e = JSONArray()
        val icons = listOf(132152, 132328, 136106, 132311, 132110, 132270)
        for ((j, ic) in icons.withIndex()) e.put(JSONObject().put("p", j + 1).put("n", "Pet ${j + 1}").put("i", ic).put("k", "pet").put("c", 30 + j))
        return JSONObject().put("e", e)
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private lateinit var cast: CastView

    private fun page(side: CastStripModel.Side): HomeView {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        HomeSettings.init(app)
        HomeSettings.castBarSide = side
        val holder = FrameLayout(app)
        val map = MapView(app); cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))
            .put("ca", JSONObject().put("l", radial()).put("r", radial()))))
        CastStripModel.Live.set(bar)
        home.onCastBar(bar)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, W, H)
        return home
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private fun castStrip(home: HomeView) = field(home, "castStrip") as CastStripView
    private fun chat(home: HomeView) = field(home, "chatPane") as View
    private fun map(home: HomeView) = field(home, "mapSlot") as View
    @Suppress("UNCHECKED_CAST")
    private fun boxes(home: HomeView): Map<Any, RectF> = (field(home, "now") as Map<Any, RectF>).mapValues { RectF(it.value) }

    /** the class wheel opens on [left]'s side as the chord would (CastView's latched side, then Home's layer) */
    private fun classOpen(home: HomeView, left: Boolean) {
        val flow = field(cast, "classFlow")!!
        flow.javaClass.getDeclaredField("side").apply { isAccessible = true }.setInt(flow, if (left) 1 else 2)
        HomeView::class.java.getDeclaredMethod("classLayerShow", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(home, true)
        settle()
    }
    private fun classRect(home: HomeView) = Rect(field(home, "classRect") as Rect)

    private fun settle() { for (i in 0 until 40) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper() } }

    // ---------------------------------------------------------------- the offsets

    /** the LB picture: Left goes with the chat (left), Right with the minimap (right), Both stays */
    @Test fun lbPictureMovesTheBarWithItsHalfOnly() {
        for ((side, sign) in listOf(L to -1f, R to 1f, B to 0f)) {
            val home = page(side)
            home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            val s = castStrip(home)
            assertEquals("$side bar", sign * LbPartyView.SIDE_PX, s.translationX, 0.5f)
            assertEquals(-LbPartyView.SIDE_PX, chat(home).translationX, 0.5f)
            assertEquals(LbPartyView.SIDE_PX, map(home).translationX, 0.5f)
            if (side == L) assertEquals(chat(home).translationX, s.translationX, 0.5f)
            if (side == R) assertEquals(map(home).translationX, s.translationX, 0.5f)
            home.pauseSlide(0f, LbPartyView.SIDE_PX)
            assertEquals(0f, s.translationX, 0.5f)
        }
    }

    /** the pause wheel's full slide: Left / Both go with the chat (cb3's choice), Right with the minimap */
    @Test fun pauseSlideCarriesTheBarWithItsColumn() {
        for (side in listOf(L, R, B)) {
            val home = page(side)
            home.pauseSlide(UiKit.PAUSE_OPEN_MS)
            val s = castStrip(home)
            if (side == R) assertTrue("$side", s.translationX > 0f) else assertTrue("$side", s.translationX < 0f)
            assertTrue("off the screen", s.left + s.translationX >= W - 1f || s.right + s.translationX <= 1f)
        }
    }

    /** RB + RT's class wheel: the right column only; Both's bar stays and the wheel is the right column's square, not the page's */
    @Test fun rightClassWheelLeavesBothsBarAndKeepsItsSize() {
        for (side in listOf(B, R, L)) {
            val home = page(side)
            val strip = boxes(home).entries.firstOrNull { it.key.toString() == "STRIP" }?.value
            val mapBox = boxes(home).entries.firstOrNull { it.key.toString() == "MAP" }?.value ?: error("$side: ${boxes(home).keys}")
            classOpen(home, left = false)
            val r = classRect(home)
            val s = castStrip(home)
            assertTrue("$side: the wheel is in the right half ($r)", r.left >= W / 2 - 1)
            assertTrue("$side: no bigger than the right column ($r)", r.width() <= mapBox.width() + 1)
            assertTrue("$side: the map slides off", map(home).translationX >= W - mapBox.left - 1f)
            when (side) {
                B -> { assertEquals(0f, s.translationX, 0.5f); assertTrue("above Both's bar", r.bottom <= strip!!.top + 1f) }
                R -> assertEquals(map(home).translationX, s.translationX, 1f)
                L -> assertEquals(0f, s.translationX, 0.5f)
                else -> {}
            }
        }
    }

    /** LB + LT's class wheel: the left column only; Both's bar stays */
    @Test fun leftClassWheelLeavesBothsBar() {
        for (side in listOf(B, L, R)) {
            val home = page(side)
            classOpen(home, left = true)
            val r = classRect(home)
            val s = castStrip(home)
            assertTrue("$side: the wheel is in the left half ($r)", r.right <= W / 2 + 1)
            assertTrue("$side: no bigger than the left half $r", r.width() <= W / 2)
            when (side) {
                L -> assertEquals(chat(home).translationX, s.translationX, 1f)
                else -> assertEquals("$side", 0f, s.translationX, 0.5f)
            }
        }
    }

    /** the middle wheel parts the columns: Left / Right bars with theirs, Both stays and the wheel sits above it */
    @Test fun middleWheelPartsTheBarWithItsHalf() {
        for (side in listOf(L, R, B)) {
            val home = page(side)
            val stripTop = boxes(home).entries.first { it.key.toString() == "STRIP" }.value.top
            val slot = home.wheelSlot(true)!!
            settle()
            val s = castStrip(home)
            val c = chat(home).translationX; val m = map(home).translationX
            assertTrue(c < 0f && m > 0f)
            when (side) {
                L -> assertEquals(c, s.translationX, 0.5f)
                R -> assertEquals(m, s.translationX, 0.5f)
                else -> { assertEquals(0f, s.translationX, 0.5f); assertTrue("above Both's bar", slot.bottom <= stripTop + 0.5f) }
            }
            home.wheelSlot(false); settle()
            assertEquals(0f, s.translationX, 0.5f)
        }
    }

    // ---------------------------------------------------------------- the pictures

    private val pRect = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.MAGENTA }

    private fun shot(home: HomeView, name: String, lb: LbPartyView? = null, rect: RectF? = null) {
        val d = out ?: return
        d.mkdirs()
        val bm = Bitmap.createBitmap(W, 1080, Bitmap.Config.ARGB_8888)
        val strip = Strip(app) { }
        for (pass in 0..1) {
            val c = Canvas(bm); c.drawColor(Theme.ink)
            home.draw(c)
            lb?.draw(c)
            rect?.let { c.drawRect(it, pRect) }
            lay(strip, W, Strip.HEIGHT)
            c.save(); c.translate(0f, H.toFloat()); strip.draw(c); c.restore()
            if (pass == 0) { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }
        }
        File(d, "$name.png").outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lbView(): LbPartyView {
        val doc = JSONObject("""{"p":{"box":1,"fight":false,"grp":true,"m":[
            {"c":"SHAMAN","col":"ff0070dd","dead":false,"lv":20,"i":0,"n":"Korvash","off":false,"pt":"MANA","role":"NONE","u":"player"},
            {"c":"WARRIOR","col":"ffc69b6d","dead":false,"lv":21,"i":1,"n":"Brannoc","off":false,"pt":"RAGE","role":"TANK","u":"party1"},
            {"c":"MAGE","col":"ff3fc7eb","dead":false,"lv":20,"i":2,"n":"Iselle","off":false,"pt":"MANA","role":"DAMAGER","u":"party2"}],
            "on":false,"sel":"bound"},"rs":2,"type":"party","v":"m104"}""")
        PartyModel.live.onParty(PartyModel.parseParty(doc)!!, true, System.currentTimeMillis())
        val v = LbPartyView(app)
        lay(v, W, H)
        v.lb(true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        return v
    }

    @Test fun slideShots() {
        if (out == null) return
        for ((side, tag) in listOf(L to "left", R to "right", B to "both")) {
            val home = page(side)
            home.pauseSlide(UiKit.PAUSE_OPEN_MS, LbPartyView.SIDE_PX)
            shot(home, "lb_hold_$tag", lb = lbView())
        }
        for ((side, tag) in listOf(B to "both", R to "right")) {
            val home = page(side)
            classOpen(home, left = false)
            shot(home, "class_rb_l2_$tag", rect = RectF(classRect(home)))
        }
        run {
            val home = page(B)
            classOpen(home, left = true)
            shot(home, "class_lb_l2_both", rect = RectF(classRect(home)))
        }
        run {
            val home = page(B)
            home.pauseSlide(UiKit.PAUSE_OPEN_MS)
            shot(home, "pause_both")
            val mid = page(B)
            mid.pauseSlide(UiKit.PAUSE_OPEN_MS / 3f)
            shot(mid, "pause_both_mid")
        }
        run {
            val home = page(B)
            val r = home.wheelSlot(true)!!
            settle()
            shot(home, "middle_wheel_both", rect = r)
        }
    }
}
