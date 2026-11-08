package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.17: the player reports' fixes (the user, 2026-11-08: GitHub reports, crashes, the four Thor models, slow art) */
class FieldFixesTest {
    @Test fun aLooseFilesRefusalIsFinal() {
        // level3.blizzard.com answers 403 for a file it keeps only in an archive (#17 / #22 / #23 / #25 / #32): ~7 s per file before
        assertTrue(GameFiles.looseRefusal("HTTP 403"))
        assertTrue(GameFiles.looseRefusal("HTTP 404"))
        assertFalse(GameFiles.looseRefusal("HTTP 503"))
        assertFalse(GameFiles.looseRefusal("timed out"))
    }

    @Test fun theFourThors() {
        // Android's counts in the reports: 7191 (8 GB), 11265 (12 GB), 15255 (16 GB)
        assertTrue(DeviceTier.lowMemory(7191)); assertTrue(DeviceTier.lowMemory(11265)); assertFalse(DeviceTier.lowMemory(15255))
        assertFalse(DeviceTier.lowMemory(0))                                 // unknown: never lighter by guess
        assertEquals("Thor Base (8 GB)", DeviceTier.model(7191, false))
        assertEquals("Thor Pro (12 GB)", DeviceTier.model(11265, false))
        assertEquals("Thor Max (16 GB)", DeviceTier.model(15255, false))
        assertEquals("Thor Lite (Snapdragon 865, 8 GB)", DeviceTier.model(7191, true))
        assertTrue(DeviceTier.isLite("SM8250", "qcom", null))
        assertTrue(DeviceTier.isLite(null, "kona", null))
        assertFalse(DeviceTier.isLite("QCS8550", "qcom", "Adreno (TM) 740"))
    }

    @Test fun theCrashQuestion() {
        assertEquals("Android closed DS Forever because the device ran low on memory",
            LastRun.badReason(android.app.ApplicationExitInfo.REASON_LOW_MEMORY))
        assertNull(LastRun.badReason(android.app.ApplicationExitInfo.REASON_USER_REQUESTED))   // a swipe-away is no crash
        assertNull(LastRun.badReason(android.app.ApplicationExitInfo.REASON_EXIT_SELF))
        assertEquals("DS Forever crashed. The game was running (in the world, 3 min in).",
            LastRun.words("DS Forever crashed", wasRunning = true, inWorld = true, ranMs = 180_000))
        assertNull(LastRun.words(null, wasRunning = true, inWorld = true, ranMs = 1_000))   // the mark alone: the player's force stop
        assertNull(LastRun.words(null, wasRunning = false, inWorld = false, ranMs = 0))
    }

    @Test fun anSdCardFolderIsSaid() {
        assertTrue(StartupDiag.sdCard("/storage/67AF-93D9/WoW Forever"))
        assertTrue(StartupDiag.sdCard("/storage/0000-0000/WoW Forever"))
        assertFalse(StartupDiag.sdCard("/storage/emulated/0/WoW Forever"))
    }

    @Test fun theFirmwareVersionSurvivesThePrivacyPass() {
        val t = Redact.text("build Thor_V1.0.0.71_20260206_165408_user, connected to 192.168.1.25")
        assertTrue(t, t.contains("Thor_V1.0.0.71_20260206"))
        assertTrue(t, t.contains("<ip>") && !t.contains("192.168"))
    }
}
