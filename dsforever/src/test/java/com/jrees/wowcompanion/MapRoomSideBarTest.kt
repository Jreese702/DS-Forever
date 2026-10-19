package com.jrees.wowcompanion

import com.jrees.wowcompanion.CastSettings.ButtonLayout.NINTENDO
import com.jrees.wowcompanion.CastSettings.ButtonLayout.PLAYSTATION
import com.jrees.wowcompanion.CastSettings.ButtonLayout.XBOX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** the user after test12: X is the quest SIDE BAR's (not the map's bubbles); the Zoom hint is the right stick, arrows over and under */
class MapRoomSideBarTest {
    private fun ctx(region: MapRoomKeys.Region = MapRoomKeys.Region.MAP, sideBarOn: Boolean = true, sideBarHere: Boolean = true) =
        MapRoomKeys.Ctx(region, zoneView = true, hoverZone = null, hoverQuest = null, listQuest = null, hasParent = true,
            listHasQuests = sideBarOn && sideBarHere, sideBarOn = sideBarOn, sideBarHere = sideBarHere)

    @Test fun xTogglesTheSideBarOnAMap() {
        assertEquals(MapRoomKeys.Do.ToggleSideBar, MapRoomKeys.press("PAD3", ctx()))
        assertEquals(MapRoomKeys.Do.ToggleSideBar, MapRoomKeys.press("PAD3", ctx(sideBarOn = false)))
    }

    @Test fun xDoesNothingOnTheWorldWhichHasNoSideBar() =
        assertEquals(MapRoomKeys.Do.None, MapRoomKeys.press("PAD3", ctx(sideBarHere = false)))

    @Test fun theLegendSaysWhatXDoesNext() {
        assertTrue(MapRoomKeys.Prompt.HIDE_QUESTS in MapRoomKeys.legend(ctx()))
        assertTrue(MapRoomKeys.Prompt.SHOW_QUESTS in MapRoomKeys.legend(ctx(sideBarOn = false)))
        val world = MapRoomKeys.legend(ctx(sideBarHere = false))
        assertFalse(MapRoomKeys.Prompt.HIDE_QUESTS in world || MapRoomKeys.Prompt.SHOW_QUESTS in world)
    }

    /** a collapsed side bar has no list to go to: D-pad right stays on the map */
    @Test fun dpadRightNeedsTheSideBarOut() =
        assertEquals(MapRoomKeys.Do.None, MapRoomKeys.press("PADDRIGHT", ctx(sideBarOn = false)))

    /** LB / RB still step the zoom (the right stick zooms smoothly in the view) */
    @Test fun theShouldersStillZoom() {
        assertEquals(MapRoomKeys.Do.ZoomOut, MapRoomKeys.press("PADLSHOULDER", ctx()))
        assertEquals(MapRoomKeys.Do.ZoomIn, MapRoomKeys.press("PADRSHOULDER", ctx()))
    }

    @Test fun theZoomGlyphIsTheRightStickUpAndDown() {
        assertTrue(MapRoomKeys.Prompt.ZOOM in MapRoomKeys.legend(ctx()))
        assertEquals("gen_rstickup" to "gen_rstickdown", Glyphs.upDown(MapRoomKeys.ZOOM_GLYPH))
        assertEquals("gen_lstickup" to "gen_lstickdown", Glyphs.upDown("gen_lstickupdown"))
        assertNull(Glyphs.upDown("gen_rstickup"))
    }

    /** the game's stick glyphs are the same for every pad: no button set turns them into another */
    @Test fun theStickGlyphKeepsItsNameInEverySet() =
        listOf(NINTENDO, XBOX, PLAYSTATION).forEach { assertEquals(MapRoomKeys.ZOOM_GLYPH, Glyphs.translate(MapRoomKeys.ZOOM_GLYPH, it)) }
}
