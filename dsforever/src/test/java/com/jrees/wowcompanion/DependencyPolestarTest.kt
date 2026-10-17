package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Polestar as an opt-in dependency (the user, 2026-10-17): its versioned zip, its SHA256SUMS, wanted only when chosen */
class DependencyPolestarTest {
    private val pole = DependencyAddons.DEPS.first { it.folder == "Polestar" }

    @Test fun theZipNotTheRecorder() {
        val re = pole.assetRegex!!
        assertTrue(re.matches("Polestar-1.4.2.zip")); assertTrue(re.matches("Polestar-v2.0.0-beta.1.zip"))
        assertFalse(re.matches("Polestar_Recorder-1.4.2.zip")); assertFalse(re.matches("SHA256SUMS"))
        assertTrue(pole.multi && pole.optIn)
    }

    @Test fun theChecksumLine() {
        val a = "a".repeat(64); val b = "B".repeat(64)
        val sums = "$a  Polestar_Recorder-1.4.2.zip\n$b *Polestar-1.4.2.zip\n"
        assertEquals("b".repeat(64), DependencyAddons.sumFor(sums, "Polestar-1.4.2.zip"))
        assertNull(DependencyAddons.sumFor(sums, "Polestar-9.zip"))
    }

    @Test fun wantedOnlyWhenChosen() {
        // no settings (a fresh install): Vanilla, so QuestieDB only
        assertTrue(DependencyAddons.wanted(DependencyAddons.DEPS.first { it.folder == "QuestieDB" }))
        assertFalse(DependencyAddons.wanted(pole))
    }
}
