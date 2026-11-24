package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * THE PAUSE WHEEL'S TWO CORNER BUTTONS (feat/exit-now; the user, 2026-11-24: "In the pause menu I want the power button swapped to the
 * left side and then an edit button added on the right set to the x button that puts them into the home edit mode. Change the power
 * button to select or minus or whatever it is."): the REAL [HomeView], [Strip] and [HomePauseView] on the 1240 x 1080 bottom screen.
 * With PAUSE_SHOTS_DIR set (POWER_ART_ROOT = the art root): the wheel on both opening routes (Start; the bar's swirl / a touch), Home
 * at rest as the Thin row and as the Diamonds, Nintendo and Xbox glyphs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1240dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PauseSidesShotsTest {
    private val out: File? = System.getenv("PAUSE_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080
    private val HOME_H = H - Strip.HEIGHT

    @Before fun up() {
        System.getenv("POWER_ART_ROOT")?.let { root -> ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() {
        UiKit.pauseRoomy = false; UiKit.pauseBarsBottom = 0f; UiKit.pauseStripTop = 0f
        CastSettings.buttonLayout = CastSettings.ButtonLayout.NINTENDO; CastSettings.abxyLocation = CastSettings.AbxyLocation.NINTENDO
        HomeSettings.rest = HomeRest.ROW
    }

    private fun lay(v: View, l: Int, t: Int, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(l, t, l + w, t + h)
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private class Stage(val root: FrameLayout, val home: HomeView, val strip: Strip, val pause: HomePauseView, val did: ArrayList<String>)

    /** Home (a HUD document: three pages), the bar, the pause view over both, wired as CompanionHost wires them */
    private fun stage(rest: HomeRest = HomeRest.ROW, xbox: Boolean = false): Stage {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        HomeSettings.init(app)
        CastSettings.init(app)
        HomeSettings.rest = rest
        CastSettings.buttonLayout = if (xbox) CastSettings.ButtonLayout.XBOX else CastSettings.ButtonLayout.NINTENDO
        CastSettings.abxyLocation = if (xbox) CastSettings.AbxyLocation.XBOX else CastSettings.AbxyLocation.NINTENDO
        val holder = FrameLayout(act)
        val map = MapView(act); val cast = CastView(act) { }; val chat = ChatView(act) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(act, map, cast, chat) { }
        home.borrow()
        val strip = Strip(act) { }
        val did = ArrayList<String>()
        val pause = HomePauseView(act, strip, { null }) { true }
        val root = FrameLayout(act)
        root.addView(home); root.addView(strip); root.addView(pause)
        act.setContentView(root)
        lay(root, 0, 0, W, H); lay(home, 0, 0, W, HOME_H); lay(strip, 0, HOME_H, W, Strip.HEIGHT); lay(pause, 0, 0, W, H)
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", JSONArray()))))
        home.tick()
        ShadowLooper.idleMainLooper()
        lay(home, 0, 0, W, HOME_H)
        pause.barsBottom = { home.barsBottom() }
        pause.onSlide = { t ->
            home.pauseSlide(t, barsUp = true)
            strip.translationY = if (pause.isOpen) (strip.height + 4f) * (t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f) else 0f
        }
        pause.onClose = { _ -> home.pauseSlide(0f, barsUp = true); strip.translationY = 0f }
        pause.onPower = { did += "power" }
        pause.onPick = { s -> did += "pick:${s.key}" }
        return Stage(root, home, strip, pause, did)
    }

    private fun settle(ms: Long = 900) { var t = 0L; while (t < ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); t += 16 } }
    private fun frame(s: Stage) { val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); s.root.draw(Canvas(b)); ShadowLooper.idleMainLooper() }
    private fun shot(s: Stage): Bitmap {
        ShadowLooper.idleMainLooper(); frame(s)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(12, 12, 16)); s.root.draw(c)
        return b
    }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the wheel opened by [start] (Start: its press still down at the first report, then let go; else the bar's swirl) */
    private fun open(s: Stage, start: Boolean) {
        s.pause.open(byStart = start)
        val m = HomePauseView::class.java.getDeclaredMethod("buttons", Int::class.javaPrimitiveType).apply { isAccessible = true }
        if (start) { m.invoke(s.pause, 1 shl PadInput.SLOT_START); m.invoke(s.pause, 0) } else m.invoke(s.pause, 0)
        settle()
    }

    /** [slots] pressed, then let go */
    private fun press(v: HomePauseView, vararg slots: Int) {
        val m = HomePauseView::class.java.getDeclaredMethod("buttons", Int::class.javaPrimitiveType).apply { isAccessible = true }
        if (field(v, "seeded") == false) m.invoke(v, 0)
        var h = 0
        for (s in slots) h = h or (1 shl s)
        m.invoke(v, h); m.invoke(v, 0)
        ShadowLooper.idleMainLooper()
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper()
    }

    // ---------------------------------------------------------------- Select = the power button (left), X = Edit (right)

    @Test fun selectAsksTheOneConfirmAClosesBGoesBackYNoLonger() {
        for (start in listOf(true, false)) {
            val s = stage()
            open(s, start)
            press(s.pause, PadInput.SLOT_FACE_TOP)
            assertEquals("Y opens nothing now", PausePower.State.NONE, s.pause.powerState)
            assertTrue(s.pause.isOpen)
            press(s.pause, PadInput.SLOT_SELECT)
            assertEquals("Select: Close DS Forever?", PausePower.State.ASKING, s.pause.powerState)
            press(s.pause, PadInput.SLOT_FACE_LEFT)
            assertEquals("X while it asks: nothing", PausePower.State.ASKING, s.pause.powerState)
            assertTrue(s.did.isEmpty())
            press(s.pause, PadInput.SLOT_FACE_RIGHT)
            assertEquals("B: back to the wheel", PausePower.State.NONE, s.pause.powerState)
            assertTrue("the wheel stays", s.pause.isOpen)
            press(s.pause, PadInput.SLOT_SELECT)
            press(s.pause, PadInput.SLOT_FACE_BOTTOM)
            assertEquals(listOf("power"), s.did)
        }
    }

    @Test fun xClosesTheWheelAndOpensEditHome() {
        for (start in listOf(true, false)) {
            val s = stage()
            open(s, start)
            press(s.pause, PadInput.SLOT_FACE_LEFT)
            settle(200)
            assertFalse("the wheel goes", s.pause.isOpen)
            assertEquals("then Edit Home (CompanionHost: editHome)", listOf("pick:${HomePauseModel.EDIT.key}"), s.did)
        }
    }

    @Test fun theButtonsSitLeftAndRightAndTakeATouch() {
        val screen = RectF(0f, 0f, W.toFloat(), H.toFloat())
        val p = UiKit.pausePowerBox(screen)
        val e = UiKit.pauseEditBox(screen)
        assertTrue("the power button: the top-left corner $p", p.left < 40 && p.top < 40)
        assertTrue("Edit: the top-right corner $e", e.right > W - 40 && e.top < 40)
        assertEquals("the same size", p.width(), e.width(), 0.5f)
        val s = stage()
        open(s, false)
        tap(s.pause, p.centerX(), p.centerY())
        assertEquals(PausePower.State.ASKING, s.pause.powerState)
        tap(s.pause, 20f, H - 20f)
        assertEquals(PausePower.State.NONE, s.pause.powerState)
        tap(s.pause, e.centerX(), e.centerY())
        settle(200)
        assertFalse(s.pause.isOpen)
        assertEquals(listOf("pick:${HomePauseModel.EDIT.key}"), s.did)
    }

    // ---------------------------------------------------------------- the pictures

    @Test fun pictures() {
        for (start in listOf(true, false)) for (rest in HomeRest.values()) for (xbox in listOf(false, true)) {
            val s = stage(rest, xbox)
            open(s, start)
            val name = "${if (start) "start" else "swirl"}-${if (rest == HomeRest.DIAMONDS) "diamonds" else "thinrow"}-${if (xbox) "xbox" else "nintendo"}.png"
            save(shot(s), name)
            s.pause.close("test")
        }
        // the prompt Select opens (Nintendo / Xbox glyphs)
        for (xbox in listOf(false, true)) {
            val s = stage(HomeRest.ROW, xbox)
            open(s, false)
            press(s.pause, PadInput.SLOT_SELECT)
            save(shot(s), "prompt-select-${if (xbox) "xbox" else "nintendo"}.png")
        }
    }
}
