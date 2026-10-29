package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up pages after the Thor test of 10-28: Update never silent ("my update button is not working when I press A"), a press
 * that shows by the pad ("actual feedback when I press the A button without touching it"), the bars on the top box ("I still want the
 * progress bars by the things on the top").
 */
class StartPressTest {
    private val G = SetupFlow.UpdateGate::class.java

    @Test fun updateIsNeverSilent() {
        // the device that day: a background art step ran (isHarvesting) -> the press waits, it doesn't vanish
        assertEquals(SetupFlow.UpdateGate.WAIT_ART, SetupFlow.updateGate(hasVersion = true, updating = false, launching = false, harvesting = true, storage = true))
        assertEquals(SetupFlow.UpdateGate.GO, SetupFlow.updateGate(true, false, false, false, true))
        assertEquals(SetupFlow.UpdateGate.WAIT_LAUNCH, SetupFlow.updateGate(true, false, true, false, true))
        assertEquals(SetupFlow.UpdateGate.BUSY, SetupFlow.updateGate(true, true, true, true, true))
        assertEquals(SetupFlow.UpdateGate.NO_STORAGE, SetupFlow.updateGate(true, false, false, true, false))
        assertEquals(SetupFlow.UpdateGate.NO_VERSION, SetupFlow.updateGate(false, false, false, false, true))
        assertEquals("Update starts after the art step: 63%", SetupFlow.updateGateLine(SetupFlow.UpdateGate.WAIT_ART, 63))
        assertEquals("Update starts after the art step", SetupFlow.updateGateLine(SetupFlow.UpdateGate.WAIT_ART, -1))
        // every gate has words (a refusal is said, never silent)
        for (g in G.enumConstants) assertTrue(g.name, SetupFlow.updateGateLine(g, 10).isNotEmpty())
    }

    @Test fun aPadPressShowsDownThenWaits() {
        val f = StartPress()
        f.press("UPDATE", 1_000)
        assertTrue(f.down("UPDATE", 1_000)); assertTrue(f.down("UPDATE", 1_149))
        assertFalse(f.down("UPDATE", 1_150))
        assertFalse(f.waiting("UPDATE", 1_100, busy = true))                // DOWN first
        assertTrue(f.waiting("UPDATE", 1_200, busy = false))               // a short wait even when nothing reports busy
        assertFalse(f.waiting("UPDATE", 1_000 + StartPress.MIN_WAIT_MS, busy = false))
        assertTrue(f.waiting("UPDATE", 60_000, busy = true))               // as long as the update runs or is queued
        assertFalse(f.waiting("UPDATE", 1_000 + StartPress.MAX_WAIT_MS, busy = true))
        assertFalse(f.down("PLAY", 1_000)); assertFalse(f.waiting("PLAY", 1_300, true))
        f.press("PLAY", 2_000); assertFalse(f.down("UPDATE", 2_000))        // a new press replaces it
        assertTrue(f.animating(2_050, false)); f.clear(); assertFalse(f.animating(2_050, true))
    }

    private val base = SetupFlow.Inputs(path = "/x", folderFound = true, art = SetupFlow.Art.READY)

    private fun barOf(prefix: String, i: SetupFlow.Inputs): Float? =
        SetupFlow.checks(i).firstOrNull { it.label.startsWith(prefix) }?.let { SetupFlow.bar(it, i) }

    @Test fun theTopBoxBars() {
        // the game art while it runs
        assertEquals(0.42f, barOf("Game art", base.copy(art = SetupFlow.Art.RUNNING, artPercent = 42))!!, 1e-4f)
        assertNull(barOf("Game art", base))
        // the game's update: its "(i/n)" step, else a moving bar
        val up = base.copy(client = "1.60.1.70658 (1.60.1.70685 needed)", gameOutdated = "1.60.1.70685", updating = true)
        assertEquals(0.3f, barOf("Client build", up.copy(busy = "Downloading data.012 (3/10)..."))!!, 1e-4f)
        assertEquals(SetupFlow.BAR_MOVING, barOf("Client build", up.copy(busy = "Connecting to Blizzard CDN...")))
        assertNull(barOf("Client build", up.copy(updating = false)))
        // the app's own download
        val app = base.copy(appLine = "DS Forever 1.0.13: update 1.0.14 available", busy = "Downloading the update: 55%")
        assertEquals(0.55f, barOf("DS Forever", app)!!, 1e-4f)
        // the background icons until they are all there
        assertEquals(0.25f, barOf("Icons", base.copy(iconsLine = "Icons: 250 / 1000 (background)"))!!, 1e-4f)
        assertNull(barOf("Icons", base.copy(iconsLine = "Icons: all icons ready")))
        assertEquals(0.5f, SetupFlow.fraction("Indoor maps (optional): 5 / 10 ready")!!, 1e-4f)
        assertNull(SetupFlow.fraction("Game folder: /x"))
    }
}
