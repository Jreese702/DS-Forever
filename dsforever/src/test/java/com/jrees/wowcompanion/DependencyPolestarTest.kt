package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polestar (the user, 2026-10-17), now a third-party add-on of the registry (Map's word, 1.0.9): pinned (v0.2.5, its size and sha256),
 * its package's two folders, switched by Map's "Quest tracking"; the SHA256SUMS reader stays DependencyAddons' own
 */
class DependencyPolestarTest {
    private val pole = ThirdPartyAddons.POLESTAR

    @Test fun pinnedTheZipNotTheRecorder() {
        assertEquals("Polestar-0.2.5.zip", pole.core.asset)
        assertEquals(38_112_129L, pole.core.bytes)
        assertTrue(pole.core.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(listOf("Polestar", "Polestar_Nav"), pole.core.folders)
        assertTrue(pole.modules.isEmpty() && pole.inUse != null)
        assertTrue(DependencyAddons.DEPS.none { it.folder == "Polestar" })        // one path in, never two
    }

    @Test fun theChecksumLine() {
        val a = "a".repeat(64); val b = "B".repeat(64)
        val sums = "$a  Polestar_Recorder-1.4.2.zip\n$b *Polestar-1.4.2.zip\n"
        assertEquals("b".repeat(64), DependencyAddons.sumFor(sums, "Polestar-1.4.2.zip"))
        assertNull(DependencyAddons.sumFor(sums, "Polestar-9.zip"))
    }

    @Test fun wantedOnlyWhenChosen() {
        // no settings (a fresh install): Vanilla, so QuestieDB only, and Polestar not wanted
        assertTrue(DependencyAddons.wanted(DependencyAddons.DEPS.first { it.folder == "QuestieDB" }))
        assertFalse(ThirdPartyAddons.coreWanted(pole))
    }
}
