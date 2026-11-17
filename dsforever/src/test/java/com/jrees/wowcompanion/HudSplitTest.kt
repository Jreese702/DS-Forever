package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPLIT-HUD (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar" -> "Both"): the old "Controller bars" tile
 * (the controller bars with the game's buffs, debuffs and XP bar riding them on layout bit 1) is three tiles - Controller UI, Buffs, XP
 * bar - each with its own mode. The bit is shared (the five are held, the pad has no free row): it carries the parts' RIDING mode, and a
 * part whose mode differs tells the add-on so with a word on "lay" (Hub.lua hs1, columns 24-32). The pure half: the riding mode, the
 * words, the migration from the old element's mode, the tap, the policy's bit.
 */
class HudSplitTest {
    private val C = HudSplit.CONTROLLER_ID
    private val B = HudSplit.BUFFS_ID
    private val X = HudSplit.XP_ID
    private val ON = UiMode.ON; private val OFF = UiMode.OFF; private val DYN = UiMode.DYNAMIC; private val NIN = UiMode.NINTENDO

    @Test fun theThreeParts() {
        assertEquals(listOf("hud", "buffs", "xp"), HudSplit.PARTS)
        assertEquals("the controller UI keeps the element's id (its saved mode, its Nintendo choice)", "hud", C)
        assertEquals(listOf(ON, OFF, DYN, NIN), UiElements.modesFor(C))
        assertEquals("the buffs and the XP bar: On / Off / Dynamic", listOf(ON, OFF, DYN), UiElements.modesFor(B))
        assertEquals(listOf(ON, OFF, DYN), UiElements.modesFor(X))
        assertEquals("Controller UI", UiElements.of(C)!!.label)
        assertNull("no layout element of their own (no bit)", UiElements.of(B))
    }

    /** a saved Controller bars mode carries over: nothing changes until a tile is tapped */
    @Test fun theOldModeCarriesOver() {
        for (m in listOf(ON, OFF, DYN)) assertEquals(m, HudSplit.migrate(null, m))
        assertEquals("Nintendo was the bar's: the buffs and the XP bar stayed on", ON, HudSplit.migrate(null, NIN))
        assertEquals("a picked mode stands", OFF, HudSplit.migrate(OFF, DYN))
        // three parts on the old mode = no word at all, the bit the old one
        for (m in listOf(ON, OFF, DYN, NIN)) {
            val modes = mapOf(C to m, B to HudSplit.migrate(null, m), X to HudSplit.migrate(null, m))
            assertEquals("$m: no word", setOf(HudSplit.RIDE), GameUi.hsWant(modes).values.toSet())
            assertEquals("$m: the bit's mode", HudSplit.hideMode(m), HudSplit.riding(modes.values))
        }
    }

    @Test fun theRidingModeAndTheWords() {
        assertEquals(DYN, HudSplit.riding(listOf(ON, OFF, DYN)))
        assertEquals(OFF, HudSplit.riding(listOf(ON, OFF, ON)))
        assertEquals(ON, HudSplit.riding(listOf(NIN, ON, ON)))
        // Dynamic controller, buffs On, XP Off: the bit is Dynamic's, the buffs stand on, the XP bar stands off
        assertEquals(mapOf(C to "ride", B to "on", X to "off"), GameUi.hsWant(mapOf(C to DYN, B to ON, X to OFF)))
        // Off controller, buffs On, XP Off: the bit is Off's (shown while the companion is away), only the buffs have a word
        assertEquals(mapOf(C to "ride", B to "on", X to "ride"), GameUi.hsWant(mapOf(C to OFF, B to ON, X to OFF)))
        // Nintendo controller with the buffs hidden: the bar counts as On
        assertEquals(mapOf(C to "on", B to "ride", X to "on"), GameUi.hsWant(mapOf(C to NIN, B to OFF, X to ON)))
    }

    private fun hs(c: String? = null, b: String? = null, x: String? = null, n: Int = 0) = GameUi.hsFrom(JSONObject().put("v", "hs1").put("n", n)
        .apply { c?.let { put("c", it) }; b?.let { put("b", it) }; x?.let { put("x", it) } }
        .put("cols", JSONObject().put("C_ON", 24).put("C_OFF", 25).put("C_RIDE", 26).put("B_ON", 27).put("B_OFF", 28).put("B_RIDE", 29)
            .put("X_ON", 30).put("X_OFF", 31).put("X_RIDE", 32)))!!

    /** the one tap on "lay": the first part whose word differs, on the add-on's own column; nothing when the words match */
    @Test fun theTapGoesOnlyWhereAWordDiffers() {
        assertNull("one mode: no word, no tap", GameUi.hsCol(hs(), mapOf(C to OFF, B to OFF, X to OFF)))
        assertEquals("the buffs On beside an Off controller: B_ON", 27, GameUi.hsCol(hs(), mapOf(C to OFF, B to ON, X to OFF)))
        assertNull("... sent", GameUi.hsCol(hs(b = "on"), mapOf(C to OFF, B to ON, X to OFF)))
        assertEquals("the controller first", 24, GameUi.hsCol(hs(), mapOf(C to ON, B to DYN, X to OFF)))
        assertEquals("then the XP bar", 31, GameUi.hsCol(hs(c = "on"), mapOf(C to ON, B to DYN, X to OFF)))
        assertEquals("a word back to ride", 29, GameUi.hsCol(hs(b = "on"), mapOf(C to OFF, B to OFF, X to OFF)))
        assertNull("an add-on word it does not know reads as ride", GameUi.hsCol(hs(b = "maybe"), mapOf(C to OFF, B to OFF, X to OFF)))
        // an add-on without the columns: nothing
        assertNull(GameUi.hsCol(GameUi.hsFrom(JSONObject().put("v", "hs1"))!!, mapOf(C to OFF, B to ON, X to OFF)))
        assertNull(GameUi.hsFrom(null))
        val d = GameUi.hsFrom(JSONObject().put("ch", true).put("xh", true).put("bit", true).put("n", 3))!!
        assertTrue(d.hidden.getValue(C)); assertFalse(d.hidden.getValue(B)); assertTrue(d.hidden.getValue(X)); assertTrue(d.bit); assertEquals(3, d.n)
    }

    /** each part's own rule for the tile's word (the old element's, per part): Dynamic = the Cast room or Home */
    @Test fun eachPartsRule() {
        assertTrue(HudSplit.wantsHidden(OFF, "MAP", true))
        assertFalse("nothing hidden while the companion is away", HudSplit.wantsHidden(OFF, "MAP", false))
        assertTrue(HudSplit.wantsHidden(DYN, "CAST", true)); assertTrue(HudSplit.wantsHidden(DYN, "HOME", true))
        assertFalse(HudSplit.wantsHidden(DYN, "MAP", true))
        assertFalse(HudSplit.wantsHidden(NIN, "HOME", true))
    }

    /** the policy's bit for the element carries the riding mode (GameUi passes it in): Dynamic's rule while any part is Dynamic */
    @Test fun theBitCarriesTheRidingMode() {
        val modes = mapOf(C to OFF, B to DYN, X to ON)
        val p = GameUiPolicy { id -> if (id == C) HudSplit.riding(modes.values) else UiMode.ON }
        val el = HubEl("hud", 1, false)
        assertTrue("Dynamic: the Cast room", p.wantsHidden(el, "CAST", true))
        assertFalse("Dynamic: not the Map room", p.wantsHidden(el, "MAP", true))
        // the controller Off beside a Dynamic part stands off by its own word (hidden in the Map room too)
        assertEquals("off", GameUi.hsWant(modes)[C])
    }
}
