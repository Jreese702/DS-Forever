package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
 * THE ONE-TIME "TRY THE NEW TAP-TO-CAST BAR?" PROMPT (the user, 2026-11-13: "a one time pop up asking people if they want to try the
 * new touch input bar" and "show a little picture with the pop up showing how it looks with it animating away in combat"): the real
 * [SetupView] with the question's picture - touch Yes / No, the D-pad's gold box + A, B = No - and with CASTBAR_SHOTS_DIR set the
 * prompt at rest, mid-slide and slid away (ask_prompt_rest / _mid / _hidden.png, plus ask_prompt.png = rest with the pad's glyphs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CastBarAskShotsTest {
    private val out: File? = System.getenv("CASTBAR_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240; private val H = 1080 - Strip.HEIGHT

    private fun prompt(answers: MutableList<Boolean>): SetupView {
        val v = SetupView(app)
        v.ask(CompanionSetup.Prompt(Tr.t(CastBarAsk.TITLE), emptyList(), ""), "Yes", "No",
            onYes = { answers.add(true) }, onNo = { answers.add(false) },
            picture = SetupView.Picture(330f, animated = true) { c, r, t -> SettingsPreview.askPicture(c, r, t) }, cursor = true)
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, W, H)
        return v
    }

    private fun render(v: View): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        return b
    }

    private fun tapAt(v: View, x: Float, y: Float) {
        val t0 = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0); v.onTouchEvent(d); d.recycle()
        val u = MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_UP, x, y, 0); v.onTouchEvent(u); u.recycle()
    }

    /** the buttons' centres (SetupView lays them 20 px either side of the middle, 300 wide, 111 tall, 50 above the box's foot) */
    private fun buttonY(v: SetupView): Float {
        // the bottom-most row of the gold-edged buttons: found from the drawing's hit boxes
        val f = SetupView::class.java.getDeclaredField("hits"); f.isAccessible = true
        render(v)
        @Suppress("UNCHECKED_CAST") val hits = f.get(v) as List<Pair<android.graphics.RectF, String>>
        return hits.first { it.second == "setup" }.first.centerY()
    }

    @Test fun touchYesAndNo() {
        val a = ArrayList<Boolean>()
        val v = prompt(a)
        val y = buttonY(v)
        tapAt(v, W / 2f - 170f, y); assertEquals(listOf(true), a)
        tapAt(v, W / 2f + 170f, y); assertEquals(listOf(true, false), a)
    }

    @Test fun padBoxAndA_BIsNo() {
        val a = ArrayList<Boolean>()
        val v = prompt(a)
        // attached (the press lands after its 120 ms pushed-in look: View.postDelayed needs a window)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        act.setContentView(v); idle()
        v.padKey("PAD1"); idle(); assertTrue("no pad until Home holds it", a.isEmpty())
        v.padOn = true
        assertTrue(v.focusedYes)
        v.padKey("PADDRIGHT"); assertFalse(v.focusedYes)
        v.padKey("PAD1"); idle(); assertEquals(listOf(false), a)                  // A on the boxed No
        v.padKey("PADDLEFT"); assertTrue(v.focusedYes)
        v.padKey("PAD1"); idle(); assertEquals(listOf(false, true), a)
        v.padKey("PAD2"); idle(); assertEquals(listOf(false, true, false), a)    // B = No, wherever the box is
    }

    @Test fun animatesOnlyWhileShown() {
        val v = prompt(ArrayList())
        v.close()
        assertEquals(View.GONE, v.visibility)
        // closed: the picture is dropped, so nothing keeps asking for frames
        val f = SetupView::class.java.getDeclaredField("picture"); f.isAccessible = true
        assertEquals(null, f.get(v))
    }

    private fun idle() { ShadowSystemClock.advanceBy(Duration.ofMillis(200)); ShadowLooper.idleMainLooper() }

    @Test fun shots() {
        val d = out ?: return
        d.mkdirs()
        val v = prompt(ArrayList())
        v.padOn = true
        val start = SystemClock.uptimeMillis()
        for ((ms, tag) in listOf(200L to "rest", 1_275L to "mid", 2_000L to "hidden", 2_900L to "back")) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(start + ms - SystemClock.uptimeMillis()))
            val b = render(v)
            File(d, "ask_prompt_$tag.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tag == "rest") File(d, "ask_prompt.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
