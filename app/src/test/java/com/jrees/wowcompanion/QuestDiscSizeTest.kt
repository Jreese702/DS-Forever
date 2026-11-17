package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * THE GREY DISC IS THE TRACKED CIRCLE'S SIZE (the user, 2026-11-17: "the scaling on the untracked quest spot goes wacky when zooming out,
 * it does not stay the same as the other tracked circles"): Home's grey disc was the add-on's quest_disc.tga (its solid part 94 % of
 * the box) beside the kit's poi_disc (64 %), so at one size it stood about 1.46x bigger. Measured from the drawn pixels: the art the
 * kit has here (MK_ART_ROOT's ui/kit when set), else the plain fallback discs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuestDiscSizeTest {
    private fun artRoot() {
        val real = System.getenv("MK_ART_ROOT") ?: return
        val root = File("build/tmp/qd-art").absoluteFile
        if (!File(root, "ui").isDirectory) File(real, "ui").copyRecursively(File(root, "ui"))
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, root)
    }

    /** the drawn disc's solid width (alpha > 32 across its middle row) */
    private fun drawnW(draw: (Canvas) -> Unit): Int {
        val bm = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        draw(Canvas(bm))
        var lo = 999; var hi = -1
        for (x in 0 until 120) if (Color.alpha(bm.getPixel(x, 60)) > 32) { lo = minOf(lo, x); hi = x }
        return if (hi < lo) 0 else hi - lo + 1
    }

    @Test fun homesGreyDiscIsTheUnfocusedTrackedCirclesSize() {
        artRoot()
        MarkerArt.disc(RuntimeEnvironment.getApplication())           // the add-on's disc loaded, as MapView does
        val tracked = drawnW { UiKit.questDisc(it, 60f, 60f, 76f, focus = false, number = 2) }
        val grey = drawnW { UiKit.questDiscMuted(it, 60f, 60f, 76f, marker = false) }
        assertTrue("both drawn ($tracked, $grey)", tracked > 20 && grey > 20)
        assertEquals("Home's grey disc the tracked circle's size", tracked.toFloat(), grey.toFloat(), 2f)
        assertEquals("the hook says the same", tracked.toFloat(), UiKit.run { questDisc(Canvas(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)), 1f, 1f, 76f); lastDiscPx }, 3f)
    }

    @Test fun theMapRoomsGreyDiscIsItsYellowDiscsSize() {
        artRoot()
        MarkerArt.disc(RuntimeEnvironment.getApplication())
        val yellow = drawnW { UiKit.questDisc(it, 60f, 60f, 58f, focus = true, number = 2) }
        val grey = drawnW { UiKit.questDiscMuted(it, 60f, 60f, 58f, marker = true) }
        assertEquals("the room's grey disc its yellow disc's size", yellow.toFloat(), grey.toFloat(), 2f)
    }
}
