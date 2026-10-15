package com.jrees.wowcompanion

import com.jrees.wowcompanion.CastSettings.AbxyLocation
import com.jrees.wowcompanion.CastSettings.ButtonLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** f-abxy-split (the user 2026-10-15: one setting for the ABXY LOCATION, one for the BUTTON GLYPHS), off-device */
class AbxySplitTest {

    // ---------------------------------------------------------------- the migration: nobody sees a change

    @Test fun aFreshInstallIsTheThorsNintendoLocation() = assertEquals(AbxyLocation.NINTENDO, CastSettings.locationFor(null, null))
    @Test fun todaysNintendoKeepsTheSwap() = assertEquals(AbxyLocation.NINTENDO, CastSettings.locationFor(null, "NINTENDO"))
    @Test fun todaysXboxKeepsNoSwap() = assertEquals(AbxyLocation.XBOX, CastSettings.locationFor(null, "XBOX"))
    @Test fun todaysPlayStationKeepsNoSwap() = assertEquals(AbxyLocation.XBOX, CastSettings.locationFor(null, "PLAYSTATION"))
    @Test fun anOlderThorPresetReadsAsNintendo() = assertEquals(AbxyLocation.NINTENDO, CastSettings.locationFor(null, "THOR"))
    @Test fun aChosenLocationWinsOverTheGlyphs() {
        assertEquals(AbxyLocation.XBOX, CastSettings.locationFor("XBOX", "NINTENDO"))
        assertEquals(AbxyLocation.NINTENDO, CastSettings.locationFor("NINTENDO", "XBOX"))
    }

    // ---------------------------------------------------------------- the four combinations: the location moves, the glyphs re-picture

    /** the action drawn at each face position (left, top, right, bottom) for a location, as CastView and the stand-ins place them */
    private fun actionsAt(loc: AbxyLocation) = if (loc == AbxyLocation.NINTENDO) listOf("y", "x", "a", "b") else listOf("x", "y", "b", "a")
    /** that action's glyph in a set, through the kit's one choke point (UiKit.pad -> Glyphs.translate) */
    private fun glyphsAt(loc: AbxyLocation, set: ButtonLayout) = actionsAt(loc).map { Glyphs.translate("rev_$it", set) }

    @Test fun nintendoLocationNintendoGlyphs() =    // today's default, unchanged
        assertEquals(listOf("rev_y", "rev_x", "rev_a", "rev_b"), glyphsAt(AbxyLocation.NINTENDO, ButtonLayout.NINTENDO))
    @Test fun nintendoLocationXboxGlyphs() =        // a modded pad: Nintendo arrangement, Xbox pictures
        assertEquals(listOf("ltr_y", "ltr_x", "ltr_a", "ltr_b"), glyphsAt(AbxyLocation.NINTENDO, ButtonLayout.XBOX))
    @Test fun xboxLocationNintendoGlyphs() =
        assertEquals(listOf("rev_x", "rev_y", "rev_b", "rev_a"), glyphsAt(AbxyLocation.XBOX, ButtonLayout.NINTENDO))
    @Test fun xboxLocationXboxGlyphs() =
        assertEquals(listOf("ltr_x", "ltr_y", "ltr_b", "ltr_a"), glyphsAt(AbxyLocation.XBOX, ButtonLayout.XBOX))
    @Test fun playStationGlyphsFollowTheActionWhereverItSits() =
        assertEquals(listOf("shp_triangle", "shp_square", "shp_cross", "shp_circle"), glyphsAt(AbxyLocation.NINTENDO, ButtonLayout.PLAYSTATION))

    // ---------------------------------------------------------------- every glyph a room names in a fixed set follows the setting

    @Test fun shouldersTriggersAndSelectTranslate() {
        assertEquals("ltr_rshoulder", Glyphs.translate("rev_rshoulder", ButtonLayout.XBOX))
        assertEquals("shp_ltrigger", Glyphs.translate("rev_ltrigger", ButtonLayout.PLAYSTATION))
        assertEquals("ltr_view", Glyphs.translate("rev_minus", ButtonLayout.XBOX))
        assertEquals("shp_share", Glyphs.translate("ltr_view", ButtonLayout.PLAYSTATION))
        assertEquals("rev_minus", Glyphs.translate("shp_share", ButtonLayout.NINTENDO))
        assertEquals("rev_a", Glyphs.translate("shp_cross", ButtonLayout.NINTENDO))
    }

    @Test fun namesItDoesNotKnowAreKept() {
        for (n in listOf("ltr_left", "gen_lstickin", "gen_rtrigger", "rev_plus", "home", "rev_")) assertEquals(n, Glyphs.translate(n, ButtonLayout.XBOX))
    }

    @Test fun aGlyphAlreadyInTheChosenSetIsUnchanged() {
        for (n in listOf("ltr_a", "ltr_b", "ltr_rshoulder", "ltr_view")) assertEquals(n, Glyphs.translate(n, ButtonLayout.XBOX))
    }
}
