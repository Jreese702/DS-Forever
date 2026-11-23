package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix/lbrb-wheels (a tester's report, 2026-11-23): (1) with the game's target bumpers swapped (GamepadSwapTargetModifiers: "LB ... is
 * now target enemy for me") the LB party picture came up on LB all the same - it now follows the FRIENDLY bumper (RB while swapped), the
 * one the bottom band, its plates and the top screen already follow; (2) the Wheels tab showed both class-wheel tiles to every class,
 * while only Druid / Paladin (left), Warrior (right) and Hunter / Warlock (both: the pet's) have one.
 */
class LbSwapWheelsTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()
    private val cast = File(src, "CastView.kt").readText()

    // ---------------------------------------------------------------- (1) the friendly bumper

    @Test fun thePictureIsTheFriendlyBumpersAlone() {
        // not swapped: LB alone, as before
        assertTrue(LbPartyView.friendlyAlone(lb = true, rb = false, swapped = false))
        assertFalse(LbPartyView.friendlyAlone(lb = false, rb = true, swapped = false))
        assertFalse("LB + RB is the shortcuts layer", LbPartyView.friendlyAlone(lb = true, rb = true, swapped = false))
        // swapped: RB alone; LB (now the enemy bumper) never shows it
        assertTrue(LbPartyView.friendlyAlone(lb = false, rb = true, swapped = true))
        assertFalse("the tester's case: LB held with the swap on", LbPartyView.friendlyAlone(lb = true, rb = false, swapped = true))
        assertFalse(LbPartyView.friendlyAlone(lb = true, rb = true, swapped = true))
        assertFalse(LbPartyView.friendlyAlone(lb = false, rb = false, swapped = true))
        assertEquals("LB", LbPartyView.bumperWord(false))
        assertEquals("RB", LbPartyView.bumperWord(true))
    }

    @Test fun bothBumpersAreGatedOnTheTriggersAndAnOpenClassWheel() {
        // RB + RT is the right class wheel's chord: the picture never overlaps it (the 1.0.20 rule, now for either bumper)
        assertTrue(cast.contains("onShoulders?.invoke(lb && !lt && !rt && !classOpen, rb && !lt && !rt && !classOpen)"))
        // the swap is the hud document's cv.swapT (the band's own), the lbmap's word before the first hud document
        assertTrue(cast.contains("fun swapTargets(): Boolean = hud?.swapT ?: (LbMap.swap == true)"))
        // a hud document that changes it says so
        assertTrue(Regex("val swapWas = old\\?\\.swapT\\s+hud = h\\s+if \\(swapWas != h\\.swapT\\) onSwapTargets\\?\\.invoke\\(h\\.swapT\\)")
            .containsMatchIn(cast))
    }

    @Test fun theHostAsksForTheFriendlyBumperAndReEvaluatesOnASwap() {
        assertTrue(host.contains("castView.onShoulders = { lb, rb -> shLb = lb; shRb = rb; " +
            "lbAloneNow = LbPartyView.friendlyAlone(lb, rb, castView.swapTargets()); lbEval() }"))
        assertTrue(host.contains("castView.onSwapTargets = { sw -> lbAloneNow = LbPartyView.friendlyAlone(shLb, shRb, sw); lbEval() }"))
        // the gate itself is unchanged (LbHomeOnlyTest / TalkTabsTest pin its text)
        assertTrue(host.contains("lbView.lb(lbAloneNow && free)"))
    }

    @Test fun theLbmapDocumentsSwapWord() {
        LbMap.update(JSONObject("""{"type":"lbmap","v":"m187","mod":"CTRL","swap":true,"fb":"RB","self":"PAD1","p1":"PADDUP"}"""))
        assertEquals(true, LbMap.swap)
        assertEquals("PADDUP", LbMap.keyOf("party1"))
        LbMap.update(JSONObject("""{"type":"lbmap","v":"m187","mod":"CTRL","swap":false,"fb":"LB","self":"PAD1"}"""))
        assertEquals(false, LbMap.swap)
        // an older add-on set (no swap field): no word, the hud document decides
        LbMap.update(JSONObject("""{"type":"lbmap","v":"m186","mod":"CTRL","self":"PAD1","p1":"PADDUP"}"""))
        assertNull(LbMap.swap)
    }

    // ---------------------------------------------------------------- (2) the class wheels' tiles

    @Test fun classWheelTilesOnlyForTheClassesThatHaveThem() {
        val l = ControllerPictures.CLASS_L; val r = ControllerPictures.CLASS_R
        for (cls in listOf("DRUID", "PALADIN")) { assertTrue(cls, ControllerTiles.wheelClass(l, cls)); assertFalse(cls, ControllerTiles.wheelClass(r, cls)) }
        assertFalse(ControllerTiles.wheelClass(l, "WARRIOR")); assertTrue(ControllerTiles.wheelClass(r, "WARRIOR"))
        for (cls in listOf("HUNTER", "WARLOCK")) { assertTrue(cls, ControllerTiles.wheelClass(l, cls)); assertTrue(cls, ControllerTiles.wheelClass(r, cls)) }
        for (cls in listOf("PRIEST", "SHAMAN", "MAGE", "ROGUE", "DEATHKNIGHT", "MONK", "DEMONHUNTER", "EVOKER")) {
            assertFalse(cls, ControllerTiles.wheelClass(l, cls)); assertFalse(cls, ControllerTiles.wheelClass(r, cls))
        }
        assertTrue("the class's case does not matter", ControllerTiles.wheelClass(l, "Druid"))
        // the class not known yet: both shown (as Smart Res / Auto Shot before the game says)
        for (cls in listOf(null, "")) { assertTrue(ControllerTiles.wheelClass(l, cls)); assertTrue(ControllerTiles.wheelClass(r, cls)) }
        // the other wheels are every class's
        for (id in listOf(ControllerPictures.PAUSE, ControllerPictures.ITEM, ControllerPictures.SPELL))
            for (cls in listOf(null, "MAGE", "WARRIOR")) assertTrue("$id $cls", ControllerTiles.wheelClass(id, cls))
    }

    @Test fun theWheelsSetIsActionBarsClassActions() {
        // the add-on's own list: CLASS_FLYOUT (Druid / Paladin left, Warrior right) and the pet classes' both sides
        val ab = File(src, "../../../../assets/companion/addons/WowCompanion/ActionBar.lua").readText()
        assertTrue(ab.contains("local CLASS_FLYOUT = { DRUID = { 262, \"l\" }, PALADIN = { 270, \"l\" }, WARRIOR = { 269, \"r\" } }"))
        assertTrue(ab.contains("elseif cls == \"HUNTER\" or cls == \"WARLOCK\" then"))
        assertEquals(setOf("DRUID", "PALADIN", "HUNTER", "WARLOCK"), ControllerTiles.CLASS_WHEEL_L)
        assertEquals(setOf("WARRIOR", "HUNTER", "WARLOCK"), ControllerTiles.CLASS_WHEEL_R)
        // the Wheels row is filtered by it, on the character's class
        val tiles = File(src, "ControllerTiles.kt").readText()
        assertTrue(tiles.contains(".filter { wheelClass(it, GameUi.playerClass) }.map { Slot(it) }"))
    }
}
