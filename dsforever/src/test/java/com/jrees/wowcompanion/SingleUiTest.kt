package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SINGLE SCREEN MODE'S PRESET, PLAY'S REFINEMENTS (SingleUi): with the play docks built (Home's own map and chat over the game) the game's
 * minimap and chat window go; the game's cast bar hides only once the add-on says single screen mode is on (`ss` = 1: ActionBar nbar25's
 * top cast bar has every cast) - always one cast bar on the top screen; nothing is saved; two screens never read it. Pure.
 */
class SingleUiTest {
    @After fun clean() { SingleScreen.reset(); SingleUi.reset() }

    @Test fun theDocksTakeTheMinimapAndTheChat() {
        SingleScreen.set(true, SingleScreen.Why.USER)
        assertEquals("no docks yet (CORE's preset): the game's minimap", UiMode.ON, GameUi.effMode("minimap"))
        assertEquals(UiMode.ON, GameUi.effMode("chat"))
        SingleUi.docks = true
        assertEquals("the docks built: Home's map instead", UiMode.OFF, GameUi.effMode("minimap"))
        assertEquals("Home's chat on the left instead", UiMode.OFF, GameUi.effMode("chat"))
        // the 1535 polish (the user: "The normal quest list should stay hidden and instead it should show our focused quest box")
        assertEquals("Home's quest card under the map instead of the game's tracker", UiMode.OFF, GameUi.effMode("tracker"))
        assertEquals("no Nintendo bar (the user: \"the Nintendo bar is BAD\")", UiMode.OFF, GameUi.effMode("hud"))
        assertEquals("the XP bar gives its place to the room row", UiMode.OFF, GameUi.effMode(HudSplit.XP_ID))
        assertEquals(UiMode.ON, GameUi.effMode(HudSplit.BUFFS_ID))
        assertEquals("the saved choice is untouched", UiMode.OFF, GameUi.mode("minimap"))
    }

    @Test fun theGameCastBarHidesOnlyWithTheAddOnsWord() {
        assertFalse("ss not 1: the game's cast bar shows (ours does not have every cast)", SingleUi.castBarHide(true))
        SingleUi.addonSingle = true
        assertTrue("ss = 1: ours has every cast, the game's hides", SingleUi.castBarHide(false))
        SingleUi.addonSingle = false
        assertFalse(SingleUi.castBarHide(true))
    }

    @Test fun twoScreensNeverReadIt() {
        SingleUi.docks = true; SingleUi.addonSingle = true
        for (id in listOf("minimap", "hud", "tracker", "chat", "dialogs")) assertEquals(id, GameUi.mode(id), GameUi.effMode(id))
    }
}
