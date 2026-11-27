package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1529'S PERFORMANCE MODE IN SINGLE SCREEN MODE (SinglePlayLayout.paced, CompanionHost's PLAY wiring): the top panel runs up to 120 Hz, so
 * Home's self-repeating frames (the docked map's trail and arrow above all) are paced to ~30 Hz while the player moves or fights with
 * Home's rest being the game (the PLAY state: Home not drawn, its map in a dock) - not only while Home's page shows; a panel over the game
 * (a room) runs at full rate; a touch gives full rate for a moment (FramePace's own rule). Two screens: the probe as it was.
 */
class FramePaceSingleTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @After fun tearDown() = FramePace.reset()

    @Test fun pacedWhilePlayingWithHomeNotDrawn() {
        assertTrue("PLAY, moving: paced", SinglePlayLayout.paced(onHome = true, homeShown = false, restElsewhere = true, fight = false, moving = true))
        assertTrue("PLAY, fighting: paced", SinglePlayLayout.paced(true, false, true, fight = true, moving = false))
        assertFalse("PLAY, standing: every frame (nothing repeats anyway)", SinglePlayLayout.paced(true, false, true, false, false))
        assertFalse("a room over the game: full rate", SinglePlayLayout.paced(onHome = false, homeShown = false, restElsewhere = false, fight = true, moving = true))
        assertTrue("Home's glance (shown): as Home on two screens", SinglePlayLayout.paced(true, homeShown = true, restElsewhere = false, fight = false, moving = true))
        assertFalse("Home neither shown nor the game's rest (another overlay): full rate", SinglePlayLayout.paced(true, false, false, true, true))
    }

    @Test fun theGateFollowsTheProbeAndATouch() {
        var playing = true
        FramePace.probe = { SinglePlayLayout.paced(true, false, playing, fight = true, moving = false) }
        assertTrue(FramePace.busy(10_000L))
        FramePace.touched(10_050L)
        assertFalse("a touch: full rate for a moment", FramePace.busy(10_200L))
        assertTrue(FramePace.busy(10_050L + FramePace.TOUCH_HOLD_MS + FramePace.PROBE_MS))
        playing = false
        assertFalse("a panel up: full rate", FramePace.busy(10_050L + FramePace.TOUCH_HOLD_MS + 3 * FramePace.PROBE_MS))
    }

    @Test fun theHostSetsItOnlyInSingleScreenMode() {
        val h = File(src, "CompanionHost.kt").readText()
        assertTrue("the two-screen probe is as it was",
            h.contains("FramePace.probe = { room == Room.HOME && homeView.isShown && (homeView.inFight() || lastBook?.combat == true || mapView.movingNow()) }"))
        val play = h.substringAfter("// ===== SINGLE: PLAY (builder play edits only here) =====").substringBefore("// ===== SINGLE: NPC")
        val wire = play.substringAfter("private fun singlePlayWire() {").substringBefore("\n    }\n")
        assertTrue("single screen mode's probe, behind single != null", wire.contains("single ?: return") && wire.contains("FramePace.probe = playProbe"))
        assertTrue("it reads Home's rest being the game", play.contains("SinglePlayLayout.paced(") && play.contains("homeView.restElsewhere"))
        assertTrue("held weakly", play.substringAfter("private val playProbe").substringBefore("\n    }\n").contains("WeakReference(this)"))
        assertTrue("let go with the host", play.contains("if (FramePace.probe === playProbe) FramePace.probe = null"))
    }
}
