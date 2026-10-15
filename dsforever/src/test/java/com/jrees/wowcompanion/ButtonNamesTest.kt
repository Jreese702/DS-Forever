package com.jrees.wowcompanion

import com.jrees.wowcompanion.CastSettings.ButtonLayout.NINTENDO
import com.jrees.wowcompanion.CastSettings.ButtonLayout.PLAYSTATION
import com.jrees.wowcompanion.CastSettings.ButtonLayout.XBOX
import org.junit.Assert.assertEquals
import org.junit.Test

/** f-abxy-split, words follow too (the user 2026-10-15: "Text names match the chosen glyph set everywhere"): Glyphs.name per set */
class ButtonNamesTest {
    private fun names(layout: CastSettings.ButtonLayout) =
        listOf("a", "b", "x", "y", "lshoulder", "rshoulder", "ltrigger", "rtrigger", "select", "lstick", "rstick").map { Glyphs.name(it, layout) }

    @Test fun nintendo() = assertEquals(listOf("A", "B", "X", "Y", "L", "R", "ZL", "ZR", "Minus", "L Stick", "R Stick"), names(NINTENDO))
    @Test fun xbox() = assertEquals(listOf("A", "B", "X", "Y", "LB", "RB", "LT", "RT", "View", "L Stick", "R Stick"), names(XBOX))
    @Test fun playstation() = assertEquals(
        listOf("Cross", "Circle", "Square", "Triangle", "L1", "R1", "L2", "R2", "Share", "L Stick", "R Stick"), names(PLAYSTATION))

    @Test fun anUnknownActionComesBackAsItIs() = assertEquals("start", Glyphs.name("start", XBOX))

    /** no setting stored (tests have no prefs): today's Nintendo words, so existing text is unchanged */
    @Test fun theDefaultIsNintendo() = assertEquals("ZR", Glyphs.name("rtrigger"))

    @Test fun theTriggerWordsOfEachStyle() {
        assertEquals("ZL" to "ZR", SpellsModel.triggerNames(SpellsModel.PadStyle.REV))
        assertEquals("LT" to "RT", SpellsModel.triggerNames(SpellsModel.PadStyle.LTR))
        assertEquals("L2" to "R2", SpellsModel.triggerNames(SpellsModel.PadStyle.SHP))
    }

    @Test fun theAbxyLocationLineSpeaksTheChosenSet() {
        assertEquals("A right, B bottom, X top, Y left", SettingsView.abxyLine(swap = true, layout = NINTENDO))
        assertEquals("A bottom, B right, X left, Y top", SettingsView.abxyLine(swap = false, layout = XBOX))
        assertEquals("Cross bottom, Circle right, Square left, Triangle top", SettingsView.abxyLine(swap = false, layout = PLAYSTATION))
    }

    @Test fun bagsBindableButtonsAreNamedAndOthersAreNot() {
        assertEquals("Minus", BagsView.bindKeyName("PADBACK"))
        assertEquals("ZR", BagsView.bindKeyName("PADRTRIGGER"))
        assertEquals(null, BagsView.bindKeyName("PADSTART"))
    }
}
