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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * THE MENU'S REGION BUTTON (the user, 2026-11-07: "There should be a change region option in the menu when starting the app"): both
 * menus (the waiting screen's and the chooser's) drawn on the 1240 x 1080 bottom screen in the hosts' own areas; the panel must fit.
 * Since 2026-11-17 (the user: "it is closing the menu before I can even confirm and downloading a new build") Region opens a list and
 * a confirm card (Cancel focused): both drawn on both screens, in English, Spanish and French, the pad's box on the focused button.
 * PNGs saved when TRADE_SHOTS_DIR is set (TRADE_ART_ROOT = an art root laid out like the app's).
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
        StartMenu.focusId()?.let { f -> hits.firstOrNull { it.second == f } }?.let { (r, _) ->
            UiKit.focus(c, RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f))
        }
        out?.let { it.mkdirs(); File(it, "$name.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        // every button but the X inside the host's area
        for ((r, id) in hits) if (id != 12) assertTrue("$name: button $id at $r leaves $area", r.top >= area.top - 0.5f && r.bottom <= area.bottom + 0.5f)
        return hits
    }

    @Test
    fun regionButton() {
        artRoot()
        val ctx = RuntimeEnvironment.getApplication()
        Tr.init(ctx)
        // the words as the app ships them (in case the test's assets lack them)
        listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }
            .firstOrNull { it.isDirectory }?.let { dir -> for (l in listOf("es", "fr")) Tr.load(l, File(dir, "$l.json").readText()) }
        var code = "us"
        StartMenu.region = { code }
        StartMenu.onRegion = { code = it }
        val g = shot("menu_game", true)
        assertTrue(g.any { it.second == 15 })
        shot("menu_chooser", false)
        // the Region button saves nothing: it opens the list (the old one stepped and saved on every press)
        val saved = ArrayList<String>()
        StartMenu.onRegion = { saved += it; code = it }
        StartMenu.regionPreview = null
        StartMenu.open = true
        try {
            for (lang in listOf("en", "es", "fr")) {
                Tr.setChoice(RuntimeEnvironment.getApplication(), if (lang == "en") "auto" else lang)
                StartMenu.press(StartMenu.REGION)
                assertEquals((0 until 4).map { StartMenu.ROW0 + it }, shot("region_list_chooser_$lang", false).map { it.second }.filter { it >= StartMenu.ROW0 })
                shot("region_list_game_$lang", true)
                StartMenu.pickerMove(0, 1)
                StartMenu.pickerA()
                StartMenu.picker.preview = RegionPicker.Preview.DOWNLOAD
                val card = shot("region_confirm_download_chooser_$lang", false).map { it.second }
                assertTrue(StartMenu.REGION_CANCEL in card && StartMenu.REGION_SWITCH in card)
                assertEquals(StartMenu.REGION_CANCEL, StartMenu.focusId())
                shot("region_confirm_download_game_$lang", true)
                StartMenu.picker.preview = RegionPicker.Preview.INSTALL
                shot("region_confirm_install_chooser_$lang", false)
                shot("region_confirm_install_game_$lang", true)
                StartMenu.picker.preview = RegionPicker.Preview.CHECKING
                shot("region_confirm_checking_chooser_$lang", false)
                StartMenu.back(); StartMenu.back()
            }
            assertTrue(saved.isEmpty())
            code = "kr"
            StartMenu.press(StartMenu.REGION)
            shot("region_list_chooser_kr_current", false)
        } finally {
            Tr.setChoice(RuntimeEnvironment.getApplication(), "auto")
            StartMenu.open = false; StartMenu.onRegion = null
        }
    }
}
