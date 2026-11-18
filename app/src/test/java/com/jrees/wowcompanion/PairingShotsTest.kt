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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * STREAMING'S PAIRING PANEL ON THE THOR'S TOP SCREEN (design B "Two steps", the user's picks 2026-11-18): 1920 x 1080 at the Thor's
 * 369 dpi (about 832 x 468 dp), the panel 640 dp wide as StreamTop places it. The panel must fit the screen's height with the Other
 * network row open under the six boxes (15 boxes, the longest real code). PNGs when PAIRING_SHOTS_DIR is set:
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

    @Test fun theNewWordsAreTranslated() {
        val app = RuntimeEnvironment.getApplication()
        val line = com.jrees.wowcompanion.stream.PairCode.notReachable(listOf("192.168.1.23", "100.71.133.93"))
        val want = mapOf(
            "es" to listOf("PC no accesible en 192.168.1.23 / 100.71.133.93", "Otra red", "PC no encontrado", "Revisa el código de Otra red."),
            "fr" to listOf("PC injoignable à 192.168.1.23 / 100.71.133.93", "Autre réseau", "PC introuvable", "Vérifiez le code Autre réseau."))
        val en = listOf(line, com.jrees.wowcompanion.stream.PairCode.OTHER_NETWORK, com.jrees.wowcompanion.stream.PairCode.NOT_FOUND,
            com.jrees.wowcompanion.stream.CodeEntry.BAD_OTHER)
        try {
            for ((l, words) in want) {
                listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                    .first { it.canRead() }.let { Tr.load(l, it.readText()) }
                Tr.setChoice(app, l)
                assertEquals(words, en.map { Tr.t(it) })
            }
        } finally { Tr.setChoice(app, "en") }
        assertEquals("PC not reachable at 192.168.1.23 / 100.71.133.93", Tr.t(line))
    }

    private fun keys(v: PairingView, digits: String) { for (c in digits) v.pad(KeyEvent.KEYCODE_0 + (c - '0')) }

    @Test fun shots() {
        val ctx = activity
        val v = PairingView(ctx, onPair = { paired.add(it) }, onBack = {}, onQuality = {}, onPcStep = {})
        val root = screen(v)
        v.setPc("PC: DEN (192.168.1.20)")
        render(root, "T1_empty")
        assertFits(v)
        keys(v, "482")
        render(root, "T2_typing")
        assertFits(v)
        keys(v, "913")
        render(root, "T3_filled")
        assertFits(v)
        // D-pad down past the boxes: the Other network button (gold ring); A presses it
        v.pad(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(v.entry.onButton)
        render(root, "T4_dpad_down_other_network")
        assertFits(v)
        v.pad(KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(v.entry.otherOpen); assertEquals(0, v.entry.otherCursor)
        assertTrue("A on the button opens the row, it doesn't pair", paired.isEmpty())
        render(root, "T5_other_network_open")
        assertFits(v)
        keys(v, "100279")
        render(root, "T6_other_home_filled")
        assertFits(v)
        v.pad(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("482913100279"), paired)
        // Pair found no PC: the row opens by itself, the cursor in it, "PC not found" under it
        v.reset(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        v.setPc("")
        keys(v, "482913")
        assertFalse(v.entry.otherOpen)
        v.notFound()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(v.entry.otherOpen); assertEquals(0, v.entry.otherCursor)
        render(root, "T7_auto_open_pc_not_found")
        assertFits(v)
        keys(v, "70027904")
        render(root, "T8_other_tailscale_typing")
        assertFits(v)
        keys(v, "9289390")
        assertEquals("482913700279049289390", v.entry.code)
        render(root, "T9_other_tailscale_filled")
        assertFits(v)
        v.setMessage("PC not reachable at 192.168.1.23 / 100.71.133.93")
        render(root, "T10_not_reachable")
        assertFits(v)
        // a typo in the network code: refused on the screen, nothing paired
        paired.clear()
        v.pad(KeyEvent.KEYCODE_DPAD_UP)
        v.pad(KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(paired.isEmpty())
    }

    private val paired = ArrayList<String>()
}
