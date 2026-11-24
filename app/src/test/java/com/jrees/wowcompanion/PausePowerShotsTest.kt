package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
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
 * feat/pause-power (the user, 2026-11-18: "I want the pause radial to also push the top controller bar up out of the way so that it can be
 * a bit larger. with that I also want the text at the bottom on the Dpad to also be larger. on the top right corner of the screen I want
 * to add some kind of power button that effectively opens a prompt to close DS forever"): the REAL [HomeView] (its controller bars), the
 * rooms' [Strip] and the REAL [HomePauseView] laid out as the host lays them on the 1240 x 1080 bottom screen. Pinned: the controller
 * slides fully off the top (and back on the close), the target radial keeps it, the wheel is larger, Y / the button / A / B / touch on
 * the prompt, a fight's refusal. With POWER_SHOTS_DIR set (POWER_ART_ROOT = the art root, E:/wow-build/runtime-art/r1/files): the
 * pictures before / after, the prompt in Local and Streaming words, the close under way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1240dp-h1080dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PausePowerShotsTest {
    private val out: File? = System.getenv("POWER_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080
    private val HOME_H = H - Strip.HEIGHT

    @Before fun up() {
        System.getenv("POWER_ART_ROOT")?.let { root -> ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root)) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() { UiKit.pauseRoomy = false; UiKit.pauseBarsBottom = 0f; UiKit.pauseStripTop = 0f }

    private fun lay(v: View, l: Int, t: Int, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(l, t, l + w, t + h)
    }

    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)

    private class Stage(val root: FrameLayout, val home: HomeView, val strip: Strip, val pause: HomePauseView, val did: ArrayList<String>)

    /** Home with its controller (a HUD document: three pages, the diamonds), the bar under it, the pause view over both */
    private fun stage(streaming: Boolean = false, fight: Boolean = false): Stage {
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        HomeSettings.init(app)
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
        // the host's wiring (CompanionHost): the pause pushes Home's controller up; the bar drops out of the way while it is open
        pause.barsBottom = { home.barsBottom() }
        pause.onSlide = { t ->
            home.pauseSlide(t, barsUp = true)
            strip.translationY = if (pause.isOpen) (strip.height + 4f) * (t / UiKit.PAUSE_OPEN_MS).coerceIn(0f, 1f) else 0f
        }
        pause.onClose = { _ -> home.pauseSlide(0f, barsUp = true); strip.translationY = 0f }
        pause.streaming = { streaming }
        // the user's pick (2026-11-24, "Keep the confirm, allow in fights"): the host sets no refusal, so [fight] wires nothing
        pause.onPower = { did += "power" }
        return Stage(root, home, strip, pause, did)
    }

    private fun settle(ms: Long = 900) { var t = 0L; while (t < ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); t += 16 } }

    /** one frame as the device draws it: the pause view's draw moves Home's parts (onSlide), which show from the next frame on */
    private fun frame(s: Stage) { val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); s.root.draw(Canvas(b)); ShadowLooper.idleMainLooper() }

    private fun shot(s: Stage): Bitmap {
        ShadowLooper.idleMainLooper()
        frame(s)
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(12, 12, 16))
        s.root.draw(c)
        return b
    }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bars(home: HomeView): View {
        @Suppress("UNCHECKED_CAST")
        val views = field(home, "views") as Map<Any, View>
        return views.entries.first { it.key.toString() == "BARS" }.value
    }

    /** the pad as the view's reader reports it: the first report seeds, then [slots] pressed, then let go */
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

    // ---------------------------------------------------------------- the controller goes up, the wheel grows

    @Test fun theControllerSlidesOffTheTopAndBackOnTheClose() {
        val s = stage()
        val b = bars(s.home)
        s.pause.open(byStart = false)
        settle(); frame(s)
        assertTrue("the controller's bars fully above the screen: ${b.bottom} + ${b.translationY}", b.bottom + b.translationY <= 0f)
        assertTrue(UiKit.pauseRoomy)
        s.pause.close("test")
        settle()
        assertEquals("back down on the close", 0f, b.translationY, 0.5f)
        // the target radial's rule is unchanged: the controller stays, dimmed (f-pause-bars)
        UiKit.pauseBarsBottom = s.home.barsBottom()
        s.home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = false)
        assertEquals(0f, b.translationY, 0.5f)
    }

    @Test fun theWheelIsLargerWithTheControllerAway() {
        val s = stage()
        val screen = RectF(0f, 0f, W.toFloat(), H.toFloat())
        UiKit.pauseStripTop = HOME_H.toFloat()
        UiKit.pauseBarsBottom = s.home.barsBottom()
        fun span(): Int = (0 until H).count { y -> UiKit.pauseRadialHit(screen, W / 2f, y.toFloat()) != -1 }
        UiKit.pauseRoomy = false
        val before = span()
        UiKit.pauseRoomy = true
        val after = span()
        assertTrue("larger: $before -> $after", after > before * 1.05)
        // the D-pad's words: a tap on the larger "Emotes" lands where the old one did not
        assertTrue(UiKit.pauseOptionHit(screen, W / 2f, H - (H - HOME_H) / 2f - 52f) == UiKit.PAUSE_OPT_UP)
    }

    // ---------------------------------------------------------------- the power button and its prompt

    @Test fun selectAsksBGoesBackAConfirms() {
        val s = stage()
        s.pause.open(byStart = false); settle()
        press(s.pause, PadInput.SLOT_SELECT)
        assertEquals(PausePower.State.ASKING, s.pause.powerState)
        press(s.pause, PadInput.SLOT_FACE_RIGHT)
        assertEquals("B: back to the wheel", PausePower.State.NONE, s.pause.powerState)
        assertTrue("the wheel stays", s.pause.isOpen)
        press(s.pause, PadInput.SLOT_SELECT)
        press(s.pause, PadInput.SLOT_FACE_BOTTOM)
        assertEquals(listOf("power"), s.did)
        assertEquals(PausePower.State.CLOSING, s.pause.powerState)
        press(s.pause, PadInput.SLOT_FACE_RIGHT)
        s.pause.close("a room change")
        assertTrue("nothing closes the wheel while DS Forever closes", s.pause.isOpen)
    }

    @Test fun theButtonAndThePromptByTouch() {
        val s = stage()
        s.pause.open(byStart = false); settle()
        val screen = RectF(0f, 0f, W.toFloat(), H.toFloat())
        val b = UiKit.pausePowerBox(screen)
        assertTrue("the top-left corner (the user, 2026-11-24: the power button swapped to the left side)", b.left < 40 && b.top < 40)
        tap(s.pause, b.centerX(), b.centerY())
        assertEquals(PausePower.State.ASKING, s.pause.powerState)
        tap(s.pause, 20f, H - 20f)
        assertEquals("a tap outside: back", PausePower.State.NONE, s.pause.powerState)
        tap(s.pause, b.centerX(), b.centerY())
        val close = (0 until W step 4).flatMap { x -> (0 until H step 4).map { y -> x to y } }
            .first { (x, y) -> UiKit.pausePowerPromptHit(screen, x.toFloat(), y.toFloat()) == UiKit.POWER_CLOSE }
        tap(s.pause, close.first + 8f, close.second + 8f)
        assertEquals(listOf("power"), s.did)
    }

    /** the user's pick (2026-11-24): in a fight the prompt still asks, and its Yes closes DS Forever */
    @Test fun aFightStillAsksAndYesCloses() {
        val s = stage(fight = true)
        s.pause.open(byStart = false); settle()
        press(s.pause, PadInput.SLOT_SELECT)
        assertEquals("the one confirm", PausePower.State.ASKING, s.pause.powerState)
        assertEquals("no refusal line", null, field(s.pause, "line"))
        press(s.pause, PadInput.SLOT_FACE_BOTTOM)
        assertEquals(listOf("power"), s.did)
    }

    @Test fun noDoesNothing() {
        val s = stage(fight = true)
        s.pause.open(byStart = false); settle()
        press(s.pause, PadInput.SLOT_SELECT)
        press(s.pause, PadInput.SLOT_FACE_RIGHT)
        assertEquals(PausePower.State.NONE, s.pause.powerState)
        assertTrue("B: nothing closes", s.did.isEmpty())
        assertTrue("the wheel stays", s.pause.isOpen)
    }

    // ---------------------------------------------------------------- the pictures

    @Test fun pictures() {
        // BEFORE: the controller kept (f-pause-bars), the wheel between it and the D-pad bar, the old D-pad words, no power button
        run {
            val s = stage()
            val screen = RectF(0f, 0f, W.toFloat(), H.toFloat())
            UiKit.pauseRoomy = false
            UiKit.pauseBarsBottom = s.home.barsBottom(); UiKit.pauseStripTop = HOME_H.toFloat()
            s.home.pauseSlide(UiKit.PAUSE_OPEN_MS, barsUp = false)
            s.strip.translationY = s.strip.height + 4f
            val bmp = shot(s)
            val slices = HomePauseModel.Flow().let { f -> f.slices().map { it?.let { sl -> UiKit.PauseSlice(sl.key, sl.label, sl.enabled) } } }
            UiKit.pauseRadial(Canvas(bmp), screen, PointF(W / 2f, HOME_H + Strip.HEIGHT / 2f), 140f, slices, HomePauseModel.DEFAULT_PAGE, -1,
                left = HomePauseModel.TRACKPAD_WORD, right = HomePauseModel.KEYBOARD_WORD)
            save(bmp, "1-before.png")
        }
        // AFTER: the controller gone up, the larger wheel, the larger D-pad words, the power button
        run {
            val s = stage()
            s.pause.open(byStart = false); settle()
            save(shot(s), "2-after.png")
            // the open half-way: the controller on its way up
            val m = stage()
            m.pause.previewT = 150f
            m.pause.open(byStart = false); settle(100)
            save(shot(m), "3-after-opening.png")
        }
        run {
            val s = stage()
            s.pause.open(byStart = false); settle()
            press(s.pause, PadInput.SLOT_SELECT)
            save(shot(s), "4-prompt-local.png")
        }
        run {
            val s = stage(streaming = true)
            s.pause.open(byStart = false); settle()
            press(s.pause, PadInput.SLOT_SELECT)
            save(shot(s), "5-prompt-streaming.png")
            press(s.pause, PadInput.SLOT_FACE_BOTTOM)
            settle(200)
            save(shot(s), "6-closing-streaming.png")
        }
        run {
            val s = stage(fight = true)
            s.pause.open(byStart = false); settle()
            press(s.pause, PadInput.SLOT_SELECT)
            save(shot(s), "7-prompt-in-a-fight.png")
        }
    }
}
