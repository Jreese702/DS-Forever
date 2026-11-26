package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * SINGLE SCREEN MODE'S TRANSITIONS (SingleFade; the 1535 polish - the user on test 1533, 2026-11-26: "Right now everything kind of pops
 * open, we need to have smooth transitions for everything"): the closing window's last picture (recorded from views the host has already
 * moved on from - a room set GONE, a lent page) draws where it stood; the ease runs to its end on Motion's curve and says when it lands,
 * never when cut; the top window's fade frames (its alpha, the panel's rise, the dim) for the sheet. SINGLE_FADE_SHOTS = a folder for the
 * frames (none: only the checks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SingleFadeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val out: File? = System.getenv("SINGLE_FADE_SHOTS")?.let { File(it).apply { mkdirs() } }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle(ms: Long) { var t = 0L; while (t < ms) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); ShadowLooper.idleMainLooper(); t += 16 } }

    @Test fun theGhostDrawsWhatWasShownEvenOnceItIsGone() {
        val root = FrameLayout(app)
        val room = View(app).apply { setBackgroundColor(Color.RED) }
        root.addView(room, FrameLayout.LayoutParams(200, 100).apply { leftMargin = 300; topMargin = 50 })
        lay(root, 800, 400)
        // the host has moved on: the room is GONE (show(Room.HOME) ran before the look did) - its last place and drawing remain
        room.visibility = View.GONE
        val pic = SingleFade.record(root, listOf(room))
        assertNotNull("recorded", pic)
        val g = SingleFade.Ghost(app)
        g.pic = pic
        assertEquals(View.VISIBLE, g.visibility)
        lay(g, 800, 400)
        val b = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        g.draw(Canvas(b))
        assertEquals("drawn where it stood", Color.RED, b.getPixel(400, 100))
        assertEquals("nothing else", 0, b.getPixel(100, 300) ushr 24)
        g.pic = null
        assertEquals("no picture: no view", View.GONE, g.visibility)
        assertNull("nothing with a size: no picture", SingleFade.record(root, listOf(View(app))))
    }

    @Test fun theEaseLandsAndSaysSoOnlyWhenItLands() {
        var v = -1f; var landed = 0
        val seen = ArrayList<Float>()
        SingleFade.run(0f, 1f, SingleFade.IN_MS, { v = it; seen += it }) { landed++ }
        settle(SingleFade.IN_MS * 2)
        assertEquals(1f, v); assertEquals(1, landed)
        assertTrue("it eases (never past its end): $seen", seen.all { it in 0f..1f })
        var cutLanded = 0
        val a = SingleFade.run(1f, 0f, SingleFade.OUT_MS, { }) { cutLanded++ }
        a.cancel(); settle(SingleFade.OUT_MS * 2)
        assertEquals("a cut fade never lands (a new look took over)", 0, cutLanded)
        assertTrue("short: a press is answered at once", SingleFade.IN_MS <= 200 && SingleFade.OUT_MS <= 200 && SingleFade.SUB_IN_MS <= 200)
    }

    /** the top window's fade-in as TopWindow lays it (frame alpha k, the panel risen RISE x (1 - k), the dim easing to the panel's), over
     *  a transparent 1920 x 1080 - tools/render_single_polish.py lays the frames over the game */
    @Test fun theTopWindowsFadeFrames() {
        val s = SinglePlayStage(app, partyOn = true)
        val frame = FrameLayout(app)
        val dim = View(app).apply { setBackgroundColor(Color.BLACK) }
        val slot = FrameLayout(app)
        frame.addView(dim, FrameLayout.LayoutParams(1920, 1080))
        frame.addView(slot, FrameLayout.LayoutParams(1920, 1080))
        val box = SingleScreen.panelBox(1920, 1080)
        (s.home.parent as? android.view.ViewGroup)?.removeView(s.home)
        slot.addView(s.home, FrameLayout.LayoutParams(box[2] - box[0], s.HH).apply { leftMargin = box[0] })
        for (k in listOf(0.0f, 0.33f, 0.66f, 1f)) {
            frame.alpha = k
            slot.translationY = SingleFade.RISE * (1f - k)
            dim.alpha = SingleLook.DIM_PANEL * k
            lay(frame, 1920, 1080)
            val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            // the window's own alpha (the compositor applies a decor's; drawn here as a layer)
            val layer = cv.saveLayerAlpha(0f, 0f, 1920f, 1080f, (k * 255).toInt())
            frame.draw(cv)
            cv.restoreToCount(layer)
            val a = b.getPixel(960, 500) ushr 24
            if (k == 0f) assertEquals("nothing at the start", 0, a)
            if (k == 1f) assertTrue("the panel whole at the end", a > 240)
            out?.let { d -> File(d, "fade_in_${(k * 100).toInt()}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            b.recycle()
        }
    }
}
