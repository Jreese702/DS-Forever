package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The 2026-11-08 native heap profile: the indoor-map scheduler held the open game files (~500 MB of index tables) through every pause.
 * A pause longer than RELEASE_AFTER_MS now ends the run with RELEASED (the step closes the files and waits without them).
 */
class IndoorReleaseTest {
    /** one building (1525: a listing that finds none is a failure - it would hide every building of the game build) */
    private class Gen : IndoorGenerator {
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            yield.point()
            return listOf(IndoorBakeStore.Work("wmo_a", "%064x".format(1), 1, listOf(IndoorBakeStore.Location(0, 0.0, 10.0, 0.0, 10.0))))
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) { IndoorFinishTest.building(out, work.key) }
        override fun close() {}
    }

    private class Sig(var busyNow: Boolean) : IndoorScheduler.Signals {
        var t = 0L
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = busyNow
        override fun hot() = false
        override fun stop() = false
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    @Test fun aLongPauseLetsTheGameFilesGo() {
        val root = Files.createTempDirectory("indoor").toFile()
        val sig = Sig(busyNow = true)
        val logs = ArrayList<String>()
        val s = IndoorScheduler(IndoorBakeStore(root), root, "build", Gen(), sig, File(root, "data"), log = { logs += it })
        assertEquals(IndoorScheduler.Outcome.RELEASED, s.run())
        assertTrue(sig.t >= IndoorScheduler.RELEASE_AFTER_MS)
        assertTrue(logs.any { it.contains("let go") })
    }

    @Test fun noPauseRunsToTheEnd() {
        val root = Files.createTempDirectory("indoor2").toFile()
        val s = IndoorScheduler(IndoorBakeStore(root), root, "0123456789abcdef0123456789abcdef", Gen(), Sig(busyNow = false), File(root, "data"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, s.run())
    }
}
