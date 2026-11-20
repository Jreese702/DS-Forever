package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #87 (the user's ruling, 2026-11-19: "accept that the live dots are gone"): the minimap probe is removed (Map.lua mp3; the add-on
 * side is tools/test_mmprobe_gone.py). Home's map filter keeps Rares and Sparkles and says one terse line instead of the live dots'
 * switches; the app no longer reads an "mmprobe" document, draws live dots or probe-seen chests; a kept "mapprobe" value asks Sparkles
 * only.
 */
class MinimapProbeGoneTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @Test fun theAdviceLineIsTerseAndTranslated() {
        assertEquals("Herbs/ore: use the game's minimap tracking", MinimapTracking.ADVICE)
        assertTrue("one line, no paragraph", MinimapTracking.ADVICE.length < 50 && !MinimapTracking.ADVICE.contains('\n'))
        val i18n = File(src, "../../../../assets/companion/i18n")
        for (lang in listOf("es", "fr")) {
            val plain = org.json.JSONObject(File(i18n, "$lang.json").readText()).getJSONObject("plain")
            val t = plain.optString(MinimapTracking.ADVICE)
            assertTrue("$lang has it", t.isNotBlank() && t != MinimapTracking.ADVICE)
            for (gone in listOf("Live dots", "Auto-switch", "Chests")) assertFalse("$lang: '$gone' is gone", plain.has(gone))
        }
    }

    @Test fun aKeptValueAsksSparklesOnly() {
        assertEquals("the old default (probe on)", 0, MinimapTracking.wanted(1))
        assertEquals("probe + auto-switch", 0, MinimapTracking.wanted(3))
        assertEquals("probe + sparkles", 4, MinimapTracking.wanted(5))
        assertEquals("all three", 4, MinimapTracking.wanted(7))
        assertEquals(4, MinimapTracking.wanted(4))
        assertEquals(0, MinimapTracking.wanted(0))
    }

    @Test fun theAppSideIsGone() {
        assertFalse("SeenChests is gone", File(src, "SeenChests.kt").exists())
        val host = File(src, "CompanionHost.kt").readText()
        assertFalse("no mmprobe route", host.contains("\"mmprobe\""))
        val mv = File(src, "MapView.kt").readText()
        for (gone in listOf("onMmProbe", "FILTER_LIVE", "FILTER_SWITCH", "FILTER_CHESTS", "showChests", "\"Live dots\"", "\"Auto-switch\"", "PROBE_ON"))
            assertFalse("MapView: $gone is gone", mv.contains(gone))
        assertTrue("the filter draws the advice line", mv.contains("Tr.t(MinimapTracking.ADVICE)"))
        assertTrue("Sparkles stays", mv.contains("Triple(FILTER_SPARKLES, \"Sparkles\", 0)"))
        assertTrue("the add-on's option is re-sent from the map snapshot", mv.contains("it.has(\"mpo\")"))
        val room = File(src, "WorldMapRoomView.kt").readText()
        assertFalse("the Map room's Chests filter is gone", room.contains("Filter(\"chest\""))
        assertTrue("its QuestieDB herb / ore layers stay", room.contains("Filter(\"herb\"") && room.contains("Filter(\"ore\""))
        val npcs = File(src, "SeenNpcs.kt").readText()
        assertFalse("the probe's dot helpers are gone", npcs.contains("fun dotSpot") || npcs.contains("fun nearest"))
    }

    @Test fun sparklesStillReachTheAddOn() {
        assertEquals(listOf(MapRoomPad.ROW to MapRoomPad.WP_A + 2, MapRoomPad.ROW to MapRoomPad.WP_B + 4), MapRoomPad.optCells(4))
        assertEquals(listOf(MapRoomPad.ROW to MapRoomPad.WP_A + 2, MapRoomPad.ROW to MapRoomPad.WP_B + 0), MapRoomPad.optCells(0))
    }
}
