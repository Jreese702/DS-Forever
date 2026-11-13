package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
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
import java.time.Duration

/**
 * THE HOLD FILL on the real touch route (PartyModuleView.onTouchEvent): a held member plate fills over exactly LONG_MS and the long
 * press (the target radial) fires as it completes; an early release drains it with no long press; a tap still taps (also on a dead
 * member: Smart Res); the pet's hold (no radial) never fills. With PARTY_SHOTS_DIR set it also saves the 0 / 50 / 100 % frames
 * (partyfill_0.png, partyfill_50.png, partyfill_100.png) as PartyShotsTest draws the module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PartyHoldFillViewTest {
    private val out: File? = System.getenv("PARTY_SHOTS_DIR")?.let { File(it) }
    private val e = 1e-3f

    private fun member(i: Int, n: String, c: String, role: String, dead: Boolean = false) =
        JSONObject().put("u", if (i == 0) "player" else "party$i").put("i", i).put("n", n).put("c", c).put("role", role)
            .put("pt", "MANA").put("lv", 20 + i).put("dead", dead).put("off", false).put("rng", JSONObject.NULL)

    private class Rig(val v: PartyModuleView, val m: PartyLayout.Module) {
        val taps = ArrayList<Int>()
        val longs = ArrayList<Int>()
    }

    private fun rig(pet: Boolean = false, box: Int = 2): Rig {
        val st = PartyModel.State()
        val arr = JSONArray()
        arr.put(member(0, "Korvash Emberhoof", "SHAMAN", "HEALER"))
        if (pet) arr.put(JSONObject().put("u", "pet").put("i", 5).put("n", "Grimfang").put("role", "NONE").put("pt", "FOCUS")
            .put("lv", 20).put("dead", false).put("off", false))
        arr.put(member(1, "Brannoc Ironhelm", "WARRIOR", "TANK"))
        arr.put(member(2, "Tamsin Quickfoot", "ROGUE", "DAMAGER", dead = true))
        val doc = JSONObject().put("type", "party").put("v", "m77").put("rs", 1)
            .put("p", JSONObject().put("on", true).put("box", box).put("fight", false).put("grp", true).put("sel", "bound").put("m", arr))
        st.onParty(PartyModel.parseParty(doc)!!, true, 0L)
        val v = PartyModuleView(RuntimeEnvironment.getApplication(), st)
        v.mode = PartyLayout.Mode.REST
        val top = PartyLayout.defaultTop(PartyLayout.Mode.REST)
        val m = PartyLayout.module(PartyLayout.Mode.REST, st.party!!.n, pet = st.party!!.pet, top = top)
        val w = (615f - PartyLayout.EDGE).toInt(); val h = (m.well.b - top).toInt()
        // attached to a window as on Home (an unattached view's postDelayed - the long press - never runs)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val host = android.widget.FrameLayout(act)
        host.addView(v, android.widget.FrameLayout.LayoutParams(w, h))
        act.setContentView(host)
        ShadowLooper.idleMainLooper()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val r = Rig(v, v.module())
        v.onCellTap = { r.taps.add(it) }
        v.onCellLong = { r.longs.add(it) }
        return r
    }

    private fun now() = android.os.SystemClock.uptimeMillis()
    private fun touch(v: View, action: Int, x: Float, y: Float) { v.onTouchEvent(MotionEvent.obtain(now(), now(), action, x, y, 0)) }
    private fun hold(ms: Long) = ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    /** drawn entry [k]'s centre */
    private fun at(r: Rig, k: Int) = r.m.cells[k].cx to r.m.cells[k].cy

    @Test fun fillTracksHoldTimeAndOpensAtTheThreshold() {
        val r = rig()
        val (x, y) = at(r, 1)
        touch(r.v, MotionEvent.ACTION_DOWN, x, y)
        assertEquals(0f, r.v.fillAt(), e)
        hold(PartyModuleView.LONG_MS / 2)
        assertEquals(0.5f, r.v.fillAt(), e)
        assertTrue(r.longs.isEmpty())
        hold(PartyModuleView.LONG_MS / 2 - 1)
        assertTrue(r.v.fillAt() < 1f); assertTrue(r.longs.isEmpty())   // not open one ms before full
        hold(1)
        assertEquals(1f, r.v.fillAt(), e)
        assertEquals(listOf(1), r.longs)                               // open exactly as it completes
        touch(r.v, MotionEvent.ACTION_UP, x, y)
        assertTrue(r.taps.isEmpty())                                   // a hold is never also a tap
        hold(PartyLayout.FILL_DRAIN_MS)
        assertEquals(0f, r.v.fillAt(), e)
    }

    @Test fun earlyReleaseCancels() {
        val r = rig()
        val (x, y) = at(r, 0)
        touch(r.v, MotionEvent.ACTION_DOWN, x, y)
        hold(300)
        val f = r.v.fillAt()
        assertEquals(300f / PartyModuleView.LONG_MS, f, e)
        touch(r.v, MotionEvent.ACTION_UP, x, y)
        assertEquals(f, r.v.fillAt(), e)                               // drains from where it stood
        hold(PartyLayout.FILL_DRAIN_MS / 2)
        assertEquals(f / 2f, r.v.fillAt(), e)
        hold(PartyModuleView.LONG_MS * 2)
        assertEquals(0f, r.v.fillAt(), e)
        assertTrue(r.longs.isEmpty())                                  // no radial
        assertEquals(listOf(0), r.taps)                                // the tap behaviour unchanged: a release under 450 ms taps
    }

    @Test fun slideAwayCancelsWithNoTapNoRadial() {
        val r = rig()
        val (x, y) = at(r, 1)
        touch(r.v, MotionEvent.ACTION_DOWN, x, y)
        hold(200)
        touch(r.v, MotionEvent.ACTION_MOVE, x + PartyModuleView.SLOP * 3, y)
        hold(PartyModuleView.LONG_MS)
        touch(r.v, MotionEvent.ACTION_UP, x + PartyModuleView.SLOP * 3, y)
        assertEquals(0f, r.v.fillAt(), e)
        assertTrue(r.longs.isEmpty()); assertTrue(r.taps.isEmpty())
    }

    @Test fun tapUnaffectedIncludingDeadMember() {
        val r = rig()
        for (k in 0..2) {
            val (x, y) = at(r, k)
            touch(r.v, MotionEvent.ACTION_DOWN, x, y); hold(40); touch(r.v, MotionEvent.ACTION_UP, x, y)
            hold(PartyModuleView.LONG_MS)
        }
        assertEquals(listOf(0, 1, 2), r.taps)                          // unit 2 is dead: its tap (Smart Res) still goes through
        assertTrue(r.longs.isEmpty())
        assertEquals(0f, r.v.fillAt(), e)
    }

    @Test fun petHoldNeverFills() {
        val r = rig(pet = true)
        val (x, y) = at(r, 1)                                          // drawn entry 1 = the pet, beside you
        touch(r.v, MotionEvent.ACTION_DOWN, x, y)
        hold(PartyModuleView.LONG_MS / 2)
        assertEquals(0f, r.v.fillAt(), e)
        hold(PartyModuleView.LONG_MS)
        assertEquals(0f, r.v.fillAt(), e)
        touch(r.v, MotionEvent.ACTION_UP, x, y)
    }

    /** the frames: a party of 3, Party mode on, you picked, Brannoc held 0 / 225 / 450 ms (the real touch route and the real draw) */
    @Test fun frames() {
        val d = out ?: return
        System.getenv("PARTY_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        for ((name, ms) in listOf("partyfill_0.png" to 0L, "partyfill_50.png" to PartyModuleView.LONG_MS / 2, "partyfill_100.png" to PartyModuleView.LONG_MS)) {
            val r = rig(box = 1)
            val (x, y) = at(r, 1)
            touch(r.v, MotionEvent.ACTION_DOWN, x, y)
            if (ms > 0) hold(ms)
            val bmp = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Theme.ink)
            val top = PartyLayout.defaultTop(PartyLayout.Mode.REST)
            UiKit.module(c, android.graphics.RectF(8f, r.m.chatTop, 616f, 927f))
            c.save(); c.translate(PartyLayout.EDGE, top - PartyModuleView.RISE); r.v.draw(c); c.restore()
            d.mkdirs()
            File(d, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            touch(r.v, MotionEvent.ACTION_UP, x, y)
        }
    }
}
