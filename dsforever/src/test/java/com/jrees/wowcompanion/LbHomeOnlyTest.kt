package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix/lb-home-only (the user 2026-11-19: "Pressing LB in menus like the auction house shows the party selected. That should only
 * appear on the home menu"): the LB party picture shows only with Home in front - the class wheel's own overlay gate (overlayUp:
 * Interact windows, dialogs, the spells pop-up, wheels, the keyboard tool, the bar cursor, Home's full-screen chat / map) - and a room
 * or one of those coming up while LB is held drops it at once.
 */
class LbHomeOnlyTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()
    private fun body(name: String): String {
        val at = host.indexOf("private fun $name(")
        assertTrue("$name is in the host", at >= 0)
        return host.substring(at, host.indexOf("\n\n", at).let { if (it < 0) host.length else it })
    }

    @Test fun dropsAtOnceOffHomeOrCovered() {
        assertFalse(LbPartyView.dropNow(onHome = true, covered = false))      // Home in front: the picture stays (or fades on LB let go)
        assertTrue(LbPartyView.dropNow(onHome = true, covered = true))        // the AH / a vendor / mail ... over Home
        assertTrue(LbPartyView.dropNow(onHome = false, covered = false))      // a room opened
        assertTrue(LbPartyView.dropNow(onHome = false, covered = true))
    }

    @Test fun theGateIsTheClassWheelsOverlayList() {
        // ONE list: the class wheel's gate is overlayUp, and the LB picture's gate uses it too
        assertEquals(1, Regex("castView\\.overlayOpen = \\{ overlayUp\\(\\) \\}").findAll(host).count())
        val up = body("overlayUp")
        assertTrue("the Interact window (AH, vendor, mail, bank, trainer, loot) covers Home",
            up.contains("interactView.visibility == View.VISIBLE"))
        assertTrue(up.contains("dialogView.covers()"))
        assertTrue(up.contains("homeView.toolWanted()"))                     // the keyboard tool
        assertTrue(up.contains("radialView.isOpen") && up.contains("pauseView.isOpen"))
        val eval = body("lbEval")
        assertTrue(Regex("val free = room == Room\\.HOME && !holdTalk\\.takesPad && !overlayUp\\(\\) && !tradOpen\\(\\)").containsMatchIn(eval))
        assertTrue(eval.contains("lbView.lb(lbAloneNow && free)"))
        // shown and no longer in front: dropped at once, Home's sides back unless the pause / target radial owns the slide
        assertTrue(eval.contains("if (lbView.isShown2 && LbPartyView.dropNow(room == Room.HOME, overlayUp() || tradOpen())) {"))
        assertTrue(eval.contains("lbView.drop()"))
        // while it shows, the cover is looked at again (a window opening while LB is held has no shoulder change)
        assertTrue(eval.contains("if (lbView.isShown2) ui.postDelayed(lbWatch, LB_WATCH_MS)"))
    }

    @Test fun aRoomOpeningWhileLbIsHeldReEvaluates() {
        val at = host.indexOf("        room = r\n")
        assertTrue(at >= 0)
        assertTrue(host.substring(at, at + 300).contains("if (r != was && ::lbView.isInitialized && lbView.isShown2) lbEval()"))
    }
}
