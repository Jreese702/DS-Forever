package com.jrees.wowcompanion

import android.view.View
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
 * THE SIX TABS FIT (the user, 2026-11-12: "fit all six" - no scrolling): on the Settings room's real bar (the room's full 1240 px width:
 * the tab row stands above the page and the preview panel), every tab whole inside the row between the L / R ends, in English, Spanish
 * and French, measured with the game's font as the bar draws it (THEME_ART_ROOT, else the art root on this PC, else the fallback font).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTabFitTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun art() {
        val root = System.getenv("THEME_ART_ROOT") ?: "E:/wow-build/runtime-art/r1/files"
        if (!File(root, "fonts/frizqt__.ttf").canRead()) return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
    }

    private fun words() {
        for (l in listOf("es", "fr")) {
            val f = listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
                .first { it.canRead() }
            Tr.load(l, f.readText())
        }
    }

    @Test fun allSixFitInEveryLanguage() {
        art(); words()
        Settings.init(app); SettingsTabs.init(app)
        // the bar's real width: the room lays it out across the whole bottom screen
        val room = SettingsRoom(app, SettingsView(app) { }) { null }
        room.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080 - Strip.HEIGHT, View.MeasureSpec.EXACTLY))
        room.layout(0, 0, 1240, 1080 - Strip.HEIGHT)
        val barW = room.tabBar.width.toFloat()
        assertEquals(1240f, barW)
        val avail = barW - 32f - 2f * UiKit.TAB_END_W
        try {
            for (lang in listOf("en", "es", "fr")) {
                Tr.setChoice(app, lang)
                for (chosen in SettingsTabs.visible.indices) {
                    val lay = SettingsTabBar.layoutFor(barW, chosen)
                    assertTrue("$lang: the six tabs take ${lay.total} px of $avail", lay.total <= avail)
                    assertEquals("$lang: no scroll", 0f, lay.offset)
                    for ((i, t) in SettingsTabs.visible.withIndex()) {
                        val (x0, x1) = lay.xs[i]
                        assertTrue("$lang ${t.label} inside the row", x0 >= 0f && x1 <= avail)
                        // its words whole inside its tab, with the tab art's room at both ends
                        assertTrue("$lang ${Tr.t(t.label)} fits its tab", x1 - x0 >= SettingsTabBar.labelW(t.label) + 2f * SettingsTabBar.TAB_PAD - 0.5f)
                    }
                }
            }
        } finally { Tr.setChoice(app, "auto") }
        // never below the room's smallest words (22)
        assertTrue(SettingsTabBar.LABEL_SIZE >= 22f)
    }
}
