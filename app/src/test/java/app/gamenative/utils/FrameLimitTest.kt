package app.gamenative.utils

import com.jrees.wowcompanion.FrameGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame limit (board n-fps-40), off-device: the one-time 1529 move to 60 (1.0.8's 40 nobody picked), the container's limit, the restart rule */
class FrameLimitTest {
    // test build 1529 (the user, 2026-11-24: "60 by default" = "New default 60 for everyone who never changed it; anyone who picked a
    // value keeps it"): the one-time move to 60 (FrameGen.limit60)
    @Test fun aNewInstallStartsAtSixtyNeverForty() {
        assertEquals(60, FrameGen.LIMIT_DEFAULT)
        assertEquals(60, FrameGen.limit60(done = false, migrated108 = false, noteLeft = false, containerLimit = 60))
        assertEquals(60, FrameGen.limit60(done = false, migrated108 = false, noteLeft = false, containerLimit = 30))   // every container, as 1.0.8
    }

    @Test fun fortyFromTheOldMigrationThatNobodyPickedGoesToSixty() {
        assertEquals(60, FrameGen.limit60(done = false, migrated108 = true, noteLeft = true, containerLimit = 40))
    }

    @Test fun aPlayersOwnPickIsKept() {
        // a Settings pick (of 40 too) removed the note: kept
        assertNull(FrameGen.limit60(done = false, migrated108 = true, noteLeft = false, containerLimit = 40))
        assertNull(FrameGen.limit60(done = false, migrated108 = true, noteLeft = false, containerLimit = 60))
        assertNull(FrameGen.limit60(done = false, migrated108 = true, noteLeft = false, containerLimit = 0))
        // the quick menu's own value (the note still there from 1.0.8, the value not 40): kept
        assertNull(FrameGen.limit60(done = false, migrated108 = true, noteLeft = true, containerLimit = 45))
        assertNull(FrameGen.limit60(done = false, migrated108 = true, noteLeft = true, containerLimit = 0))       // Off
    }

    @Test fun theMoveRunsOnlyOnce() {
        assertNull(FrameGen.limit60(done = true, migrated108 = true, noteLeft = true, containerLimit = 40))
        assertNull(FrameGen.limit60(done = true, migrated108 = false, noteLeft = false, containerLimit = 40))
    }

    @Test fun theOldLimitIsStillASettingsChoice() {
        assertEquals(40, FrameGen.LIMIT_MIGRATED)
        assertTrue(FrameGen.LIMIT_MIGRATED in FrameGen.LIMITS)
        assertTrue(FrameGen.LIMIT_DEFAULT in FrameGen.LIMITS)
        // 30 / 40 / 60 / Off (based on a pull request by RenaeTheRaven: 30 x2 = an exact 60 on the 60 Hz panel); the default stays 60
        assertEquals(listOf(30, 40, 60, 0), FrameGen.LIMITS)
        assertEquals(60, FrameGen.LIMIT_DEFAULT)
    }

    @Test fun theLaunchersMigrationReadsTheContainerAndAQuickMenuPickClearsTheNote() {
        val fg = java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion/FrameGen.kt").readText()
        assertTrue(fg.contains("if (fromQuickMenu) { e.remove(KEY_ASK).remove(KEY_NOTE);"))
        assertTrue(fg.contains("p.edit().putBoolean(KEY_60, true).putBoolean(KEY_MIGRATED, true).remove(KEY_NOTE).apply()"))
        val lm = java.io.File("src/main/java/app/gamenative/utils/LsfgVkManager.kt").readText()
        assertTrue(lm.contains("if (fg.migrateLimit(before) {"))
    }

    @Test fun theContainersLimitFromItsExtras() {
        assertEquals(60, FrameGen.containerLimit(null, null))          // never set: the launcher's own default (on, 60)
        assertEquals(40, FrameGen.containerLimit("true", "40"))
        assertEquals(0, FrameGen.containerLimit("false", "40"))        // off
        assertEquals(45, FrameGen.containerLimit("true", "45"))        // the quick menu's own value, shown as it is
        assertEquals(60, FrameGen.containerLimit(null, "bad"))
        assertEquals(30, FrameGen.containerLimit(null, "30"))          // enabled absent = on
    }

    @Test fun aRestartOnlyWhenTheLimitWouldChange() {
        assertFalse(FrameGen.limitNeedsRestart(null, 40))               // no game started by this run
        assertFalse(FrameGen.limitNeedsRestart(40, 40))
        assertTrue(FrameGen.limitNeedsRestart(40, 60))
        assertTrue(FrameGen.limitNeedsRestart(0, 40))
    }

    // Based on a pull request by RenaeTheRaven: with frame generation on and the NATIVE backend, nothing paced the guest (the Present
    // extension's limiter was set to 0 for "the layer"), so the Frame limit did nothing. Only the lsfg-vk LAYER really paces it
    @Test fun theFrameLimitPacesTheGuestWithNativeFrameGeneration() {
        val xs = java.io.File("src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()
        val f = xs.substringAfter("fun applyFpsLimiterToEngines(limit: Int) {").substringBefore("fun effectiveFpsLimit()")
        assertTrue(f.contains("val layerPaces = isLsfgAvailable && lsfgMultiplier >= 2 && !LsfgVkManager.isNativeBackend(container)"))
        assertTrue(f.contains("?.setFrameRateLimit(if (layerPaces) 0 else limit)"))
        // the renderer's display hint stays off with frame generation, as before
        assertTrue(f.contains("xServerView?.setFrameRateLimit(if (isLsfgAvailable && lsfgMultiplier >= 2) 0 else limit)"))
        assertTrue(f.contains("ShmFramePacer.setFrameRateLimit(limit)"))
    }

    @Test fun labels() {
        assertEquals("40", FrameGen.limitLabel(40))
        assertEquals("Off", FrameGen.limitLabel(0))
    }
}
