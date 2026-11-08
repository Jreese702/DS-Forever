package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE MENU'S REGION BUTTON (the user, 2026-11-07: "There should be a change region option in the menu when starting the app"): both
 * menus (the waiting screen's and the chooser's) drawn on the 1240 x 1080 bottom screen in the hosts' own areas; the panel must fit and
 * Region must step US, EU, Korea, Taiwan, US. PNGs saved when TRADE_SHOTS_DIR is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartMenuShotsTest {
    private val out: File? = System.getenv("TRADE_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("TRADE_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun shot(name: String, game: Boolean): List<Pair<RectF, Int>> {
        val b = Bitmap.createBitmap(1240, 1080, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 24, 32))
        val hits = ArrayList<Pair<RectF, Int>>()
        val area = if (game) {
            val top = 1080f - StartView.ROW_DOWN - StartView.BUTTON_H
            val left = (1240f - StartMenu.WIDTH) / 2f
            StartMenu.draw(c, RectF(left, StartView.GAP, left + StartMenu.WIDTH, top - StartView.GAP), -1, hits,
                RectF(StartView.SIDE_M, top, StartView.SIDE_M + StartView.SIDE_W, top + StartView.BUTTON_H), game = true)
            RectF(left, StartView.GAP, left + StartMenu.WIDTH, top - StartView.GAP)
        } else {
            val footY = 1080f - ChooserPanel.MARGIN - ChooserPanel.BUTTON_H
            val a = RectF(ChooserPanel.MARGIN, ChooserPanel.MARGIN + 110f, 1240f - ChooserPanel.MARGIN, footY - ChooserPanel.GAP * 2)
            StartMenu.draw(c, a, -1, hits, RectF(ChooserPanel.MARGIN, footY, ChooserPanel.MARGIN + 220f, footY + ChooserPanel.BUTTON_H), game = false)
            a
        }
        out?.let { it.mkdirs(); File(it, "$name.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        // every button but the X inside the host's area
        for ((r, id) in hits) if (id != 12) assertTrue("$name: button $id at $r leaves $area", r.top >= area.top - 0.5f && r.bottom <= area.bottom + 0.5f)
        return hits
    }

    @Test
    fun regionButton() {
        artRoot()
        var code = "us"
        StartMenu.region = { code }
        StartMenu.onRegion = { code = it }
        val g = shot("menu_game", true)
        assertTrue(g.any { it.second == 15 })
        shot("menu_chooser", false)
        val seen = ArrayList<String>()
        repeat(4) { StartMenu.press(15); seen += code }
        assertEquals(listOf("eu", "kr", "tw", "us"), seen)
        code = "kr"
        shot("menu_chooser_kr", false)
    }
}
