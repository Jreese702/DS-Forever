package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.jrees.wowcompanion.stream.PairingView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * STREAMING'S PAIRING PANEL ON THE THOR'S TOP SCREEN (the PC's address inside the code, the user's pick 2026-11-18): 1920 x 1080 at the
 * Thor's 369 dpi (about 832 x 468 dp), the panel 640 dp wide as StreamTop places it. The panel must fit the screen's height with the
 * code's address line under the six boxes. PNGs when PAIRING_SHOTS_DIR is set:
 *   PAIRING_SHOTS_DIR=<dir> gradlew :app:testModernDebugUnitTest --tests "*PairingShotsTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingShotsTest {
    private val out: File? = System.getenv("PAIRING_SHOTS_DIR")?.let { File(it) }

    private val activity by lazy { org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get() }

    /** the panel in a window (its texts are posted: they land once it is attached) */
    private fun screen(v: PairingView): FrameLayout {
        val dp = activity.resources.displayMetrics.density
        return FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(v, FrameLayout.LayoutParams((640 * dp).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            activity.setContentView(this)
        }
    }

    private fun render(root: View, name: String) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        out?.let { d -> d.mkdirs(); File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** the panel and every row in it are whole: nothing measured taller than the screen, no row squeezed under its own wish */
    private fun assertFits(v: PairingView) {
        // what the panel wants at its width with no height limit: no taller than the screen (the old panel wanted ~1150 px)
        val dp = v.resources.displayMetrics.density
        v.measure(View.MeasureSpec.makeMeasureSpec((640 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("panel wants ${v.measuredHeight} px of 1080", v.measuredHeight <= 1080)
        (v.parent as View).let { it.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)); it.layout(0, 0, 1920, 1080) }
        assertTrue("panel ${v.height} px of 1080", v.height <= 1080 && v.top >= 0)
        fun walk(x: View) {
            if (x.visibility == View.VISIBLE && x is android.widget.Button)
                assertTrue("${x.text}: ${x.height} px < ${x.measuredHeight}", x.height >= x.measuredHeight && x.height > 0)
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) {
                val c = x.getChildAt(i)
                // inside its row: the code boxes once sat 5 px past theirs (a centred row with unequal margins), their bottom cut
                if (c.visibility == View.VISIBLE) assertTrue("${c.javaClass.simpleName} ${c.top}..${c.bottom} in ${x.height}", c.top >= 0 && c.bottom <= x.height)
                walk(c)
            }
        }
        walk(v)
    }

    @Test fun theNotReachableLineIsTranslated() {
        val app = RuntimeEnvironment.getApplication()
        val line = com.jrees.wowcompanion.stream.PairCode.notReachable(listOf("192.168.1.23", "100.71.133.93"))
        try {
            for ((l, want) in listOf("es" to "PC no accesible en 192.168.1.23 / 100.71.133.93", "fr" to "PC injoignable à 192.168.1.23 / 100.71.133.93")) {
                listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                    .first { it.canRead() }.let { Tr.load(l, it.readText()) }
                Tr.setChoice(app, l)
                assertEquals(want, Tr.t(line))
            }
        } finally { Tr.setChoice(app, "en") }
        assertEquals("PC not reachable at 192.168.1.23 / 100.71.133.93", Tr.t(line))
    }

    @Test fun shots() {
        val ctx = activity
        val v = PairingView(ctx, onPair = {}, onBack = {}, onQuality = {}, onPcStep = {})
        val root = screen(v)
        v.setPc("PC: DEN (192.168.1.20)")
        render(root, "P1_empty")
        assertFits(v)
        for (k in listOf(KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_3)) v.pad(k)
        render(root, "P2_secret_only")
        assertFits(v)
        for (c in "1002790") v.pad(KeyEvent.KEYCODE_0 + (c - '0'))
        assertEquals("4829131002790", v.entry.code)
        render(root, "P3_with_address")
        assertFits(v)
        v.reset()
        for (c in "482913100279404928933") v.pad(KeyEvent.KEYCODE_0 + (c - '0'))
        assertEquals("482913100279404928933", v.entry.code)
        v.pad(KeyEvent.KEYCODE_DPAD_LEFT); v.pad(KeyEvent.KEYCODE_DPAD_LEFT)
        render(root, "P4_lan_and_tailscale_cursor")
        assertFits(v)
        v.setMessage("PC not reachable at 192.168.1.23 / 100.71.133.93")
        render(root, "P5_not_reachable")
        assertFits(v)
    }
}
