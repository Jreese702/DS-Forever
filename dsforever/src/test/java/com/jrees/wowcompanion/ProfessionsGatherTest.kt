package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 87's follow-up (the player, 2026-11-23: "is there any to enable herb/ore tracking? Option seems greyed out in my professions
 * tab"; the developer: "Yeah I can turn that back on"): the book's Find Herbs / Find Minerals buttons were inert. With add-on p28 a card
 * button that is one of the game's tracking types carries `trk` (1 on / 0 off) and its ON / OFF cells (`ton` / `toff`) on the secure open
 * row. The room sends ONE explicit cell per press (never a toggle), greys a button with no tracking, and says where the dots show.
 */
class ProfessionsGatherTest {
    private val M = ProfessionsModel

    private fun overview(herbs: JSONObject, open: Boolean = true): ProfessionsModel.Overview {
        val rows = JSONObject().put(M.ROW_CTL, 1).put(M.ROW_SEL, 2)
        if (open) rows.put(M.ROW_OPEN, 3)
        val mining = JSONObject().put("pos", 2).put("id", 186).put("name", "Mining").put("craft", true).put("main", true).put("oslot", 4)
            .put("btn", JSONArray().put(JSONObject().put("n", "Smelting").put("rec", true).put("o", true))
                .put(JSONObject().put("n", "Find Minerals").put("trk", 1).put("ton", 5).put("toff", 6)))
        val herb = JSONObject().put("pos", 1).put("id", 182).put("name", "Herbalism").put("main", true).put("btn", JSONArray().put(herbs))
        return M.parseOverview(JSONObject().put("list", JSONArray().put(herb).put(mining)).put("ctl", JSONObject())
            .put("pad", JSONObject().put("rows", rows)))
    }

    private fun herbs(trk: Int? = 0, ton: Int? = 2, toff: Int? = 3) = JSONObject().put("n", "Find Herbs").apply {
        if (trk != null) put("trk", trk); if (ton != null) put("ton", ton); if (toff != null) put("toff", toff)
    }

    @Test fun aTrackingButtonSwitchesOnItsOwnCell() {
        val ov = overview(herbs())
        val b = ov.list[0].btn[0]
        assertTrue(b.tracking); assertFalse(b.on); assertTrue(M.gatherLive(ov, b))
        val on = M.gatherTap(ov, b, true)
        assertNotNull(on); assertEquals(M.ROW_OPEN, on!!.row); assertEquals(2, on.col)
        assertNull("already off: nothing sent", M.gatherTap(ov, b, false))
        val minerals = ov.list[1].btn[1]
        assertTrue(minerals.on)
        assertEquals(6, M.gatherTap(ov, minerals, false)!!.col)                 // OFF: its own cell, never the ON one
        assertNull(M.gatherTap(ov, minerals, true))
    }

    @Test fun greyedWhenTheCharacterHasNoSuchTracking() {
        val ov = overview(herbs(trk = null, ton = null, toff = null))            // an add-on before p28, or no Find Herbs known
        val b = ov.list[0].btn[0]
        assertFalse(b.tracking); assertFalse(M.gatherLive(ov, b)); assertNull(M.gatherTap(ov, b, true))
        // a recipe-window button is no tracking button
        val smelting = ov.list[1].btn[0]
        assertFalse(smelting.tracking); assertTrue(smelting.opens)
    }

    @Test fun notLiveUntilItsCellsAreOnTheRow() {
        val waiting = overview(herbs(ton = null, toff = null))                   // a fight's rebuild waits: trk shown, no cells yet
        assertTrue(waiting.list[0].btn[0].tracking)
        assertFalse(M.gatherLive(waiting, waiting.list[0].btn[0])); assertNull(M.gatherTap(waiting, waiting.list[0].btn[0], true))
        val noRow = overview(herbs(), open = false)                               // the pad's open row missing
        assertFalse(M.gatherLive(noRow, noRow.list[0].btn[0])); assertNull(M.gatherTap(noRow, noRow.list[0].btn[0], true))
    }

    @Test fun theNoteNamesTheTopScreensMinimapAndItsSetting() {
        assertEquals("Dots show on the top screen's minimap, not on our map.", M.GATHER_NOTE)
        // the Settings tab, the tile and the two modes that show the game's minimap, as the Settings room names them
        assertEquals("Settings > Top screen > Minimap: On or Tracking", M.GATHER_WHERE)
        assertEquals("Top screen", SettingsTabs.Tab.TOP.label)
        assertTrue("Minimap" in SettingsTabs.Block.GAME_UI.rows)
        assertEquals(listOf(UiMode.ON, UiMode.OFF, UiMode.DYNAMIC, UiMode.TRACKING), UiElements.modesFor("minimap"))
        val dir = java.io.File("../companion/app/src/main/assets/companion/i18n").takeIf { it.isDirectory }
            ?: java.io.File("companion/app/src/main/assets/companion/i18n")
        Tr.load("es", java.io.File(dir, "es.json").readText()); Tr.load("fr", java.io.File(dir, "fr.json").readText())
        assertEquals("Ajustes > Pantalla superior > Minimapa: Activado o Rastreo", Tr.t(M.GATHER_WHERE, "es"))
        assertEquals("Paramètres > Écran du haut > Minicarte : Activé ou Pistage", Tr.t(M.GATHER_WHERE, "fr"))
        // the words inside match the Settings room's own translations
        for ((lang, words) in mapOf("es" to listOf("Ajustes", "Pantalla superior", "Minimapa", "Activado", "Rastreo"),
                                    "fr" to listOf("Paramètres", "Écran du haut", "Minicarte", "Activé", "Pistage")))
            assertEquals(words, listOf("Settings", "Top screen", "Minimap", "On", "Tracking").map { Tr.t(it, lang) })
        assertEquals("Los puntos salen en el minimapa de la pantalla superior, no en nuestro mapa.", Tr.t(M.GATHER_NOTE, "es"))
        assertEquals("Les points s'affichent sur la minicarte de l'écran du haut, pas sur notre carte.", Tr.t(M.GATHER_NOTE, "fr"))
    }
}
