package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** DESIGN RENDER of the even spell radials (Radial.lua q2; the user's "Flat top" pick): the editor's wheel and Home's; runs when TRADE_SHOTS_DIR is set */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadialEvenShotsTest {
    private val out: File? = System.getenv("TRADE_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("TRADE_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun save(b: Bitmap, name: String) {
        val o = out ?: return
        o.mkdirs()
        File(o, "$name.png").outputStream().use { s -> b.compress(Bitmap.CompressFormat.PNG, 100, s) }
    }

    private val icons = listOf(136048, 136052, 136026, 136051, 135824, 136023, 136052, 135825)

    @Test fun wheels() {
        if (out == null) return
        artRoot()
        val counts = listOf(1, 2, 3, 4, 5, 8)
        val b = Bitmap.createBitmap(560 * 3, 560 * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(225, 210, 180))
        for ((k, n) in counts.withIndex()) {
            val cx = 280f + (k % 3) * 560f; val cy = 280f + (k / 3) * 560f
            // the editor: n - 1 spells + the "+" (n = 8: eight spells, no "+")
            val spells = if (n == 8) 8 else n - 1
            val items = (0 until n).map { i -> if (i < spells) UiKit.RadialItem(Icons.get(icons[i]), "Spell $i", -1) else null }
            UiKit.radialPreview(c, cx, cy, 520f, items, pointed = 0, even = n, plusSlot = if (spells < n) spells else -1)
        }
        save(b, "radial_even_editor")
        // Home's wheel, two spells, the right one pointed
        val h = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val hc = Canvas(h)
        hc.drawColor(Color.rgb(20, 24, 32))
        val two = listOf(UiKit.RadialItem(Icons.get(136048), "Lightning Bolt", -1), UiKit.RadialItem(Icons.get(136052), "Healing Wave", -1))
        UiKit.consumableRadial(hc, RectF(0f, 0f, 1240f, 1080f), android.graphics.PointF(620f, 1020f), 140f, two, pointed = 1, even = 2)
        save(h, "radial_even_home2")
        val h3 = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val hc3 = Canvas(h3)
        hc3.drawColor(Color.rgb(20, 24, 32))
        val five = (0 until 5).map { i -> UiKit.RadialItem(Icons.get(icons[i]), "Spell $i", -1) }
        UiKit.consumableRadial(hc3, RectF(0f, 0f, 1240f, 1080f), android.graphics.PointF(620f, 1020f), 140f, five, pointed = 2, even = 5)
        save(h3, "radial_even_home5")
    }
}
