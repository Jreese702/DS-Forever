package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Give up after 3 tries" (the user's pick, 2026-11-23: "After 3 failed runs it counts as 'can't be mapped', so the build can finish.
 * It's tried again after a game or builder update."). A failure that may pass (out of memory, unreadable input, killed mid-build) that
 * fails on 3 separate runs for the same building, game build and builder is settled like a rule rejection; one failure per run counts
 * once; the count is on disk (deferred.json) so it survives the app's restarts and kills. The real scheduler and store over a made-up
 * generator (IndoorFinishTest's shape).
 */
class IndoorGiveUpTest {
    private class Gen(val keys: List<String>, val rule: Set<String> = emptySet(), val transient: Set<String> = emptySet(),
                      val onProduce: (String) -> Unit = {}) : IndoorGenerator {
        val made = ArrayList<String>()
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> = keys.mapIndexed { i, k ->
            IndoorBakeStore.Work(k, "%064x".format(i + 1), 1, listOf(IndoorBakeStore.Location(0, i * 50.0, i * 50.0 + 10.0, 0.0, 10.0)))
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            made.add(work.key)
            onProduce(work.key)
            yield.point()
            if (work.key in rule) throw IndoorGenerator.Rejected("room exceeds the bounded layout policy; requires its own spatial/vertical subdivision")
            if (work.key in transient) throw IllegalStateException("interiors generator ran out of memory")
            IndoorFinishTest.building(out, work.key)
        }
        override fun close() {}
    }

    private class Sig : IndoorScheduler.Signals {
        var t = 0L
        @Volatile var gameUp = false
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = false
        override fun gameRunning() = gameUp
        override fun hot() = false
        override fun stop() = false
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    private val build = "0123456789abcdef0123456789abcdef"
    private val keys = listOf("wmo_a", "wmo_b", "wmo_c")

    private fun run(root: File, gen: IndoorGenerator, app: String = "gen|a1|lib:aa", b: String = build, sig: Sig = Sig(),
                    st: MutableList<IndoorScheduler.Status>? = null, logs: MutableList<String>? = null): IndoorScheduler.Outcome =
        IndoorScheduler(IndoorBakeStore(root), root, b, gen, sig, File(root, "interiors_sched"),
            onStatus = { st?.add(it) }, log = { logs?.add(it) }, app = app).run()

    private fun tries(root: File, key: String): Int =
        JSONObject(File(root, "interiors_sched/deferred.json").readText()).getJSONObject(key).getInt("tries")

    @Test fun aFailureThatMayPassOnThreeRunsCountsAsCantBeMapped() {
        val root = Files.createTempDirectory("giveup").toFile()
        // runs 1 and 2: wmo_c fails in a way that may pass, the build stays open ("1 to retry")
        for (n in 1..2) {
            val st = ArrayList<IndoorScheduler.Status>()
            assertEquals("run $n", IndoorScheduler.Outcome.DEFERRED, run(root, Gen(keys, rule = setOf("wmo_b"), transient = setOf("wmo_c")), st = st))
            assertEquals("1 to retry", st.last().note)
            assertEquals("the count is on disk after run $n", n, tries(root, "wmo_c"))
        }
        // run 3: the third failed run settles it - the build is finished, wmo_c counted with the places that can't be mapped
        val st = ArrayList<IndoorScheduler.Status>()
        val logs = ArrayList<String>()
        val third = Gen(keys, rule = setOf("wmo_b"), transient = setOf("wmo_c"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, third, st = st, logs = logs))
        assertEquals(listOf("wmo_c"), third.made)
        val end = st.last()
        assertEquals(IndoorScheduler.Phase.DONE, end.phase)
        assertEquals(1, end.ready); assertEquals(2, end.rejected)
        assertEquals("Indoor Maps (Optional): Finished: 1 map, 2 places can't be mapped", IndoorSchedulerStep.readiness(end).label)
        assertTrue(IndoorSchedulerStep.readiness(end).done)
        assertTrue("logged as given up: $logs", logs.any { it.contains("wmo_c") && it.contains("3 failed runs") })
        // run 4 (a new session, same game and builder): nothing is tried again
        val fourth = Gen(keys, rule = setOf("wmo_b"), transient = setOf("wmo_c"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, fourth))
        assertEquals(emptyList<String>(), fourth.made)
    }

    @Test fun aSuccessInBetweenClearsTheCount() {
        val root = Files.createTempDirectory("giveup2").toFile()
        run(root, Gen(keys, transient = setOf("wmo_c")))
        run(root, Gen(keys, transient = setOf("wmo_c")))
        assertEquals(2, tries(root, "wmo_c"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, Gen(keys)))
        assertFalse("built: its deferral is gone", JSONObject(File(root, "interiors_sched/deferred.json").readText()).has("wmo_c"))
    }

    @Test fun aGameUpdateOrANewBuilderResetsTheCount() {
        val root = Files.createTempDirectory("giveup3").toFile()
        repeat(3) { run(root, Gen(keys, transient = setOf("wmo_c"))) }
        val same = Gen(keys, transient = setOf("wmo_c"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, same))
        assertEquals(emptyList<String>(), same.made)
        // a new native library: tried again, the count starts over at 1 (not settled at once)
        val newLib = Gen(keys, transient = setOf("wmo_c"))
        assertEquals(IndoorScheduler.Outcome.DEFERRED, run(root, newLib, app = "gen|a1|lib:bb"))
        assertEquals(listOf("wmo_c"), newLib.made)
        assertEquals(1, tries(root, "wmo_c"))
        // a game update: the same
        val newGame = Gen(keys, transient = setOf("wmo_c"))
        assertEquals(IndoorScheduler.Outcome.DEFERRED, run(root, newGame, app = "gen|a1|lib:bb", b = "fedcba9876543210fedcba9876543210"))
        assertEquals(listOf("wmo_c"), newGame.made)
        assertEquals(1, tries(root, "wmo_c"))
    }

    @Test fun aReleaseForTheGameIsNoFailedRun() {
        val root = Files.createTempDirectory("giveup4").toFile()
        repeat(4) {
            val sig = Sig()
            assertEquals(IndoorScheduler.Outcome.RELEASED, run(root, Gen(keys) { k -> if (k == "wmo_c") sig.gameUp = true }, sig = sig))
        }
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, Gen(keys)))
        val f = File(root, "interiors_sched/deferred.json")
        assertFalse("a game starting is never a failure of the building", f.isFile && JSONObject(f.readText()).has("wmo_c"))
    }

    /**
     * KILLED MID-BUILD (Android's low-memory kill on an 8 GB Thor, a native crash): the process ends inside the building, so nothing
     * catches it. Each run that was killed in it counts as one failed run; the third settles it. A "kill" here: the run's thread is left
     * blocked inside the building and the next run starts over the same folders, as a restarted app does.
     */
    @Test fun aRunKilledMidBuildCountsAsAFailedRun() {
        val root = Files.createTempDirectory("giveup5").toFile()
        val hold = CountDownLatch(1)
        val abandoned = ArrayList<Thread>()
        fun killedRun() {
            val inside = CountDownLatch(1)
            val gen = Gen(keys) { k -> if (k == "wmo_c") { inside.countDown(); hold.await() } }
            abandoned += Thread { runCatching { run(root, gen) } }.apply { isDaemon = true; start() }
            assertTrue("the run reached the building", inside.await(20, TimeUnit.SECONDS))
        }
        try {
            killedRun()
            killedRun()
            killedRun()
            val st = ArrayList<IndoorScheduler.Status>()
            val after = Gen(keys)
            assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, after, st = st))
            assertEquals("killed in it on 3 runs: can't be mapped, not tried a 4th time", emptyList<String>(), after.made)
            assertEquals(2, st.last().ready); assertEquals(1, st.last().rejected)
            assertEquals(3, tries(root, "wmo_c"))
        } finally {
            hold.countDown()
            abandoned.forEach { it.join(5_000) }
        }
    }

    /** a kill while the building waits (the player came back, the device is warm) is not the building's: never counted */
    @Test fun aKillWhileTheBuildingWaitsIsNotCounted() {
        val root = Files.createTempDirectory("giveup7").toFile()
        val hold = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val sig = object : IndoorScheduler.Signals {
            @Volatile var t = 0L
            @Volatile var busyNow = false
            override fun position(): IndoorBakeQueue.Position? = null
            override fun busy() = busyNow
            override fun hot() = false
            override fun stop() = false
            override fun now() = t
            override fun sleep(ms: Long) { if (busyNow) { waiting.countDown(); hold.await() }; t += ms }
        }
        val t = Thread {
            runCatching {
                IndoorScheduler(IndoorBakeStore(root), root, build, Gen(keys) { k -> if (k == "wmo_c") sig.busyNow = true }, sig,
                    File(root, "interiors_sched"), app = "gen|a1|lib:aa").run()
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(waiting.await(20, TimeUnit.SECONDS))
            assertFalse("no kill marker while it waits", File(root, "interiors_sched/" + IndoorDeferrals.ATTEMPT_FILE).exists())
            val logs = ArrayList<String>()
            assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, Gen(keys), logs = logs))
            assertTrue(logs.none { it.contains(IndoorScheduler.KILLED) })
        } finally { hold.countDown(); t.join(5_000) }
    }

    @Test fun aRunKilledOnceIsTriedAgainAndBuilds() {
        val root = Files.createTempDirectory("giveup6").toFile()
        val hold = CountDownLatch(1)
        val inside = CountDownLatch(1)
        val t = Thread { runCatching { run(root, Gen(keys) { k -> if (k == "wmo_c") { inside.countDown(); hold.await() } }) } }
            .apply { isDaemon = true; start() }
        try {
            assertTrue(inside.await(20, TimeUnit.SECONDS))
            val after = Gen(keys)
            assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, after))
            assertEquals(listOf("wmo_c"), after.made)
        } finally { hold.countDown(); t.join(5_000) }
    }
}
