package com.jrees.wowcompanion

import com.jrees.wowcompanion.HomeMapPages.Page.BUFFS
import com.jrees.wowcompanion.HomeMapPages.Page.CONTROLS
import com.jrees.wowcompanion.HomeMapPages.Page.QUESTS
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOME'S OPEN MAP MODULE, ITS THREE PAGES (the user, 2026-11-17: "when opening the map, pressing to the right should open some map
 * controls, pressing right again goes to the buffs and left goes back to the quests. tapping in the map area should also change it to the
 * map controls. when using map controls it should function just like the map room with the sticks"): the page machine, the sticks' one
 * zoom rule and the stick catch's cells.
 */
class HomeMapPagesTest {
    @Test fun rightFromTheQuestsIsTheMapControlsAndRightAgainTheBuffs() {
        assertEquals(CONTROLS, HomeMapPages.press(QUESTS, "PADDRIGHT", buffsHave = true))
        assertEquals(BUFFS, HomeMapPages.press(CONTROLS, "PADDRIGHT", buffsHave = true))
    }

    @Test fun leftFromTheBuffsIsTheMapControlsAndLeftAgainTheQuests() {
        assertEquals(CONTROLS, HomeMapPages.afterBuffs("PADDLEFT", stillOn = false))
        assertEquals(QUESTS, HomeMapPages.press(CONTROLS, "PADDLEFT", buffsHave = true))
    }

    @Test fun bLeavesTheControlsForTheQuestListAndTheBuffsToo() {
        assertEquals(QUESTS, HomeMapPages.press(CONTROLS, "PAD2", buffsHave = true))
        assertEquals(QUESTS, HomeMapPages.afterBuffs("PAD2", stillOn = false))
        assertEquals("B on an aura's list or a card stays on the buffs", BUFFS, HomeMapPages.afterBuffs("PAD2", stillOn = true))
    }

    @Test fun withNothingOnTheBuffsPageRightStaysOnTheControls() =
        assertNull(HomeMapPages.press(CONTROLS, "PADDRIGHT", buffsHave = false))

    @Test fun everyOtherKeyIsThePagesOwn() {
        for (k in listOf("PADDUP", "PADDDOWN", "PAD1", "PAD3", "PAD4", "PADLSHOULDER", "PADRSHOULDER", "PADBACK")) {
            assertNull(k, HomeMapPages.press(QUESTS, k, true))
            assertNull(k, HomeMapPages.press(CONTROLS, k, true))
        }
        assertNull("Left on the list does nothing new", HomeMapPages.press(QUESTS, "PADDLEFT", true))
        assertNull("B on the list is the list's (the module's own chain)", HomeMapPages.press(QUESTS, "PAD2", true))
        assertNull("the buffs' keys are the pane's", HomeMapPages.press(BUFFS, "PADDLEFT", true))
    }

    @Test fun aTouchOnTheMapIsTheMapControls() {
        for (p in HomeMapPages.Page.values()) assertEquals(CONTROLS, HomeMapPages.touched(p))
    }

    @Test fun bLeadsBackInsideTheModuleOffTheList() {
        assertEquals("home", HomeMapPages.bLeads(QUESTS, "home"))
        assertEquals("back", HomeMapPages.bLeads(QUESTS, "back"))
        assertEquals("back", HomeMapPages.bLeads(CONTROLS, "home"))
    }

    @Test fun theControlsLineNamesTheSticksAndThePagesEitherSide() {
        val words = HomeMapPages.hints(buffs = true, act = null)
        assertEquals(listOf("lstick" to "Move", "rstick" to "Zoom", "left" to "Quests", "right" to "Buffs", "b" to "Back"), words)
        assertEquals(listOf("lstick", "rstick", "a", "left", "b"), HomeMapPages.hints(buffs = false, act = "Focus").map { it.first })
    }

    @Test fun theRightSticksZoomIsTheMapRoomsRule() {
        assertEquals(1f, MapRoomKeys.stickZoom(0.1f, 0.016f), 0f)
        val up = MapRoomKeys.stickZoom(-1f, 0.5f)
        assertEquals("full tilt up: e^(rate x dt) in", Math.exp(0.8).toFloat(), up, 1e-4f)
        assertEquals("down is the same out", 1f / up, MapRoomKeys.stickZoom(1f, 0.5f), 1e-4f)
        assertEquals("one frame after a gap, never a jump", 1f / 60f, MapRoomKeys.frameDt(5000L, 0L), 0f)
        assertEquals(0.05f, MapRoomKeys.frameDt(5000L, 1000L), 0f)
    }

    @Test fun theStickCatchCellsAreOp3OnThePinLanes() {
        val pad = JSONObject().put("rows", JSONObject().put("mapw", JSONObject()))
        assertEquals(listOf("mapw" to 9, "mapw" to 24), MapRoomPad.homeSticksCells(pad, true))
        assertEquals(listOf("mapw" to 9, "mapw" to 23), MapRoomPad.homeSticksCells(pad, false))
        assertNull("an older Map.lua without the row: nothing", MapRoomPad.homeSticksCells(JSONObject().put("rows", JSONObject()), true))
        assertTrue("the app says it again well inside the add-on's own let-go", MapRoomPad.HOME_STICKS_KEEP_MS * 2 < MapRoomPad.HOME_STICKS_TTL_S * 1000L)
    }
}
