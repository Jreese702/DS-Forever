package app.gamenative.utils

import com.jrees.wowcompanion.FrameGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame limit (board n-fps-40), off-device: the one-time 1.0.8 migration to 40, the container's limit, the restart rule */
class FrameLimitTest {
    @Test fun theMigrationRunsExactlyOnce() {
        var done = false
        val written = ArrayList<Int>()
        val m = FrameGen.LimitMigration({ done }, { done = true })
        assertTrue(m.run { written.add(it) })            // the first start of 1.0.8: every container to 40
        assertFalse(m.run { written.add(it) })           // the next start: nothing
        assertFalse(m.run { written.add(it) })
        assertEquals(listOf(40), written)
    }

    @Test fun aMigrationDoneBeforeNeverRuns() {
        val written = ArrayList<Int>()
        assertFalse(FrameGen.LimitMigration({ true }, { throw AssertionError("marked again") }).run { written.add(it) })
        assertTrue(written.isEmpty())
    }

    @Test fun theMigratedLimitIsFortyAndASettingsChoice() {
        assertEquals(40, FrameGen.LIMIT_MIGRATED)
        assertTrue(FrameGen.LIMIT_MIGRATED in FrameGen.LIMITS)
        assertEquals(listOf(40, 60, 0), FrameGen.LIMITS)
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

    @Test fun labels() {
        assertEquals("40", FrameGen.limitLabel(40))
        assertEquals("Off", FrameGen.limitLabel(0))
    }
}
