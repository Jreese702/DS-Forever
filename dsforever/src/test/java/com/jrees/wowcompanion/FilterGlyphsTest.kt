package com.jrees.wowcompanion

import com.jrees.wowcompanion.CastSettings.ButtonLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix/filter-glyphs (the user, test 1504: "the filters on both the map and the chat both have a backwards arrow instead of the
 * appropiate button glyph for my controller"). Select opens both filters (MapCursor / MapRoomKeys / ChatPad: PADBACK); the rooms drew
 * the generic pad's "gen_back" (a back arrow, the same on every set) beside the funnel. They draw [Glyphs.select] now: Minus on the
 * Nintendo set, View on Xbox, Share on PlayStation. Start's Send cap the same way ([Glyphs.start]: Plus on Nintendo).
 */
class FilterGlyphsTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @Test fun selectFollowsTheButtonGlyphs() {
        assertEquals("rev_minus", Glyphs.select(ButtonLayout.NINTENDO))
        assertEquals("ltr_view", Glyphs.select(ButtonLayout.XBOX))
        assertEquals("shp_share", Glyphs.select(ButtonLayout.PLAYSTATION))
    }

    @Test fun startFollowsTheButtonGlyphs() {
        assertEquals("rev_plus", Glyphs.start(ButtonLayout.NINTENDO))
        assertEquals("gen_forward", Glyphs.start(ButtonLayout.XBOX))
        assertEquals("gen_forward", Glyphs.start(ButtonLayout.PLAYSTATION))
    }

    @Test fun bothFiltersDrawSelectsLayoutGlyph() {
        // the Home map's filter button, the Map room's funnel, the Chat room's funnel
        for ((file, anchor) in listOf("MapView.kt" to "btnFilter.left", "WorldMapRoomView.kt" to "funnelR.left", "ChatView.kt" to "funnel.left")) {
            val t = File(src, file).readText()
            assertTrue("$file: the filter's glyph is Select's in the chosen set", t.contains("UiKit.pad(c, Glyphs.select(), $anchor"))
        }
    }

    @Test fun noRoomHardCodesTheGenericBackOrStartGlyph() {
        val bad = src.listFiles { f -> f.name.endsWith(".kt") }!!.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                (l.contains("\"gen_back\"") || l.contains("\"gen_forward\"")) && !(f.name == "CastSettings.kt" && l.contains("gen_forward"))
            }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }
        assertEquals("a fixed generic Select / Start glyph ignores the player's layout (use Glyphs.select() / Glyphs.start())", emptyList<String>(), bad)
    }
}
