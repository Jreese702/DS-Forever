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
 * THE TAP-TO-CAST BAR IN A FIGHT (the user, 2026-11-13: "there should be a settings toggle to make them Hide/stay in combat"; Hide the
 * default): the real [CastStripView] on its touch route (hidden = no touch taken, no cast; Stay = casts in a fight), the real
 * [HomeView] fed a fight (`full` hudS c) and its end (the bar fades out and back; Stay keeps it laid and seen in the takeover), and
 * with CASTBAR_SHOTS_DIR set the Settings row's "What it does" picture (combat_settings_hide.png / combat_settings_stay.png).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarCombatShotsTest {
    private val out: File? = System.getenv("CASTBAR_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val L = CastStripModel.Side.LEFT
    private val HIDE = CastStripModel.Combat.HIDE
    private val STAY = CastStripModel.Combat.STAY

    @After fun down() { CastStripModel.Live.pos = 0f; HomeSettings.init(app); HomeSettings.castBarCombat = HIDE; HomeSettings.castBarSide = CastStripModel.Side.OFF }

    private val doc = JSONObject("""{"type":"castbar","v":"cb3","n":6,"pick":6,"pp":[39,19],"e":[
        {"s":1,"id":403,"i":136048,"c":0},{"s":2,"id":8042,"i":136026,"c":1},{"s":3,"id":331,"i":136052,"c":2},
        {"s":4,"id":324,"i":136051,"c":3},{"s":5,"id":8017,"i":136086,"c":4},{"s":6,"id":2484,"i":136102,"c":5}]}""")
    private val bar get() = CastStripModel.parse(doc)!!

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun strip(taps: MutableList<Int>): CastStripView {
        val v = CastStripView(app) { taps.add(it) }
        v.side = L; v.update(bar)
        val b = CastStripModel.place(L, 1240f, 1080f - Strip.HEIGHT, UiKit.MODULE_GAP)!!
        lay(v, b.w.toInt(), b.h.toInt())
        return v
    }

    /** a tap on slot [i]; the DOWN's answer (false = the bar took no touch) */
    private fun tap(v: CastStripView, i: Int): Boolean {
        val r = v.slotRect(i); val t0 = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0)
        val took = v.onTouchEvent(d); d.recycle()
        ShadowSystemClock.advanceBy(Duration.ofMillis(60))
        val u = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0)
        v.onTouchEvent(u); u.recycle()
        return took
    }

    @Test fun hiddenTheBarTakesNoTouchAndCastsNothing() {
        val taps = ArrayList<Int>()
        val v = strip(taps)
        assertTrue(tap(v, 0)); assertEquals(listOf(0), taps)                 // at rest: casts
        v.fight(HIDE, true)
        assertTrue(v.fightHidden)
        assertFalse(tap(v, 1)); assertEquals(listOf(0), taps)                // a fight, Hide: no touch, no cast (even mid-fade)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
        assertEquals(0f, v.fightVis(), 0f)
        assertFalse(tap(v, 2)); assertEquals(listOf(0), taps)
        v.fight(HIDE, false)                                                  // the fight ends: back, and casting
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))
        assertEquals(1f, v.fightVis(), 0f)
        assertTrue(tap(v, 3)); assertEquals(listOf(0, 3), taps)
    }

    @Test fun stayCastsInAFight() {
        val taps = ArrayList<Int>()
        val v = strip(taps)
        v.fight(STAY, true)
        assertFalse(v.fightHidden); assertEquals(1f, v.fightVis(), 0f)
        assertTrue(tap(v, 2)); assertEquals(listOf(2), taps)
    }

    // ---------------------------------------------------------------- the real Home

    private fun page(): HomeView {
        HomeSettings.init(app)
        HomeSettings.castBarSide = L
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home, 1240, 1080 - Strip.HEIGHT)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        CastStripModel.Live.set(bar)
        home.onCastBar(bar)
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, 1240, 1080 - Strip.HEIGHT)
        return home
    }

    private fun fight(home: HomeView, on: Boolean) {
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true))
            .put("hudS", JSONObject().put("c", on))), true)
        home.tick()
        ShadowLooper.idleMainLooper()
    }

    private fun castStrip(home: HomeView): CastStripView {
        val f = HomeView::class.java.getDeclaredField("castStrip"); f.isAccessible = true
        return f.get(home) as CastStripView
    }

    @Test fun homeHidesTheBarForTheFightAndBringsItBack() {
        HomeSettings.init(app); HomeSettings.castBarCombat = HIDE
        val home = page()
        val s = castStrip(home)
        assertFalse(home.fightShown()); assertFalse(s.fightHidden); assertEquals(View.VISIBLE, s.visibility)
        fight(home, true)
        assertTrue(home.fightShown())
        assertTrue(s.fightHidden)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400)); assertEquals(0f, s.fightVis(), 0f)
        fight(home, false)
        assertFalse(home.fightShown())
        assertFalse(s.fightHidden)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400)); assertEquals(1f, s.fightVis(), 0f)
    }

    @Test fun stayKeepsTheBarLaidAndSeenInTheTakeover() {
        HomeSettings.init(app); HomeSettings.castBarCombat = STAY
        val home = page()
        val s = castStrip(home)
        fight(home, true)
        lay(home, 1240, 1080 - Strip.HEIGHT)
        assertTrue(home.fightShown())
        assertFalse(s.fightHidden); assertEquals(1f, s.fightVis(), 0f)
        assertEquals(View.VISIBLE, s.visibility)
        assertTrue(s.width > 0 && s.height > 0)
        // switched to Hide mid-fight: it goes at once
        HomeSettings.castBarCombat = HIDE
        home.tick()                                                           // (the settings listener's post waits for an attached view)
        assertTrue(s.fightHidden)
    }

    // ---------------------------------------------------------------- the picture

    @Test fun settingsPicture() {
        val d = out ?: return
        d.mkdirs()
        for ((hide, name) in listOf(true to "hide", false to "stay")) for ((t, tag) in listOf(0L to "", 1500L to "_late")) {
            val b = Bitmap.createBitmap(620, 700, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink)
            SettingsPreview.draw(c, RectF(10f, 10f, 610f, 690f), "In combat", SettingsPreview.State(castBar = 1, castCombatHide = hide), t,
                SettingsPreview.Glyph { _, _, _, _, _, _ -> })
            File(d, "combat_settings_$name$tag.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
