package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE SCREEN MODE'S TOP-SCREEN PRESET (SingleUi; the user's pick for "While playing": the game's own HUD plus our Nintendo bar): the
 * game's elements follow the preset while the mode is on ([GameUi.effMode]), the saved modes never change and Settings still draws
 * them ([GameUi.mode]); our Nintendo bar's switch is wanted; the switch's own moment sends no top-screen tap ([GameUi.holdTaps]).
 */
class GameUiSingleTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @After fun clean() { SingleScreen.reset(); GameUi.holdTaps(0L) }

    @Test fun twoScreensReadTheSavedModes() {
        for (id in listOf("minimap", "hud", "tracker", "chat", "dialogs", HudSplit.BUFFS_ID, HudSplit.XP_ID))
            assertEquals(id, GameUi.mode(id), GameUi.effMode(id))
        assertFalse("the default controller UI wants no Nintendo bar", GameUi.swWant(GameUi.SW_NBAR))
    }

    @Test fun singleScreenThePreset() {
        SingleScreen.set(true, SingleScreen.Why.USER)
        assertEquals(UiMode.ON, GameUi.effMode("minimap"))
        assertEquals(UiMode.NINTENDO, GameUi.effMode("hud"))
        assertEquals(UiMode.ON, GameUi.effMode("tracker"))
        assertEquals(UiMode.ON, GameUi.effMode("chat"))
        assertEquals(UiMode.OFF, GameUi.effMode("dialogs"))
        assertEquals(UiMode.ON, GameUi.effMode(HudSplit.BUFFS_ID))
        assertEquals(UiMode.ON, GameUi.effMode(HudSplit.XP_ID))
        assertTrue("our Nintendo bar", GameUi.swWant(GameUi.SW_NBAR))
        assertEquals("Nintendo reads as On for the layout bit", UiMode.ON, HudSplit.hideMode(GameUi.hudBitMode()))
        // the saved choices (Settings) are untouched: the defaults here
        assertEquals(UiMode.OFF, GameUi.mode("minimap")); assertEquals(UiMode.OFF, GameUi.mode("hud"))
        assertFalse("the game's cast bar shows (no bar of ours has every cast yet)", SingleUi.castBarHide(true))
        SingleScreen.set(false, null)
        assertEquals(UiMode.OFF, GameUi.effMode("minimap")); assertFalse(GameUi.swWant(GameUi.SW_NBAR))
    }

    @Test fun thePresetKeepsWhatItDoesNotKnow() {
        assertEquals(UiMode.DYNAMIC, SingleUi.mode("somethingNew", UiMode.DYNAMIC))
    }

    @Test fun thePresetIsNeverSaved() {
        val ui = File(src, "SingleUi.kt").readText()
        assertFalse(ui.contains("prefs") || ui.contains("edit()") || ui.contains("SharedPreferences"))
        val g = File(src, "GameUi.kt").readText()
        val eff = g.substringAfter("fun effMode(").substringBefore("\n    }")
        assertFalse(eff.contains("edit()") || eff.contains("putString"))
        // every reader that decides the GAME's state reads the effective mode
        assertTrue(g.contains("private val policy = GameUiPolicy({ tracking }) { id -> if (id == HudSplit.CONTROLLER_ID) hudBitMode() else effMode(id) }"))
        assertTrue(g.contains("private fun partModes(): Map<String, UiMode> = HudSplit.PARTS.associateWith { effMode(it) }"))
        assertTrue(g.contains("if (part == SW_NBAR) return nbarWanted(effMode(\"hud\"))"))
        assertTrue(g.contains("castBar.step(castBarHidden, castBarHideNow(), visible"))
    }

    @Test fun holdTapsSendsNothingUntilItsEnd() {
        GameUi.holdTaps(10_000L)
        assertTrue(GameUi.tapsHeld(9_999L))
        assertFalse(GameUi.tapsHeld(10_000L))
        GameUi.holdTaps(5_000L)                                    // the new host's shorter hold replaces the switch's longer one
        assertFalse(GameUi.tapsHeld(5_000L))
        val g = File(src, "GameUi.kt").readText()
        val rec = g.substringAfter("private fun reconcile() {")
        assertTrue("the hold comes before any tap of reconcile's",
            rec.indexOf("if (tapsHeld(now))") in 0 until rec.indexOf("TapCal.wanted"))
    }
}
