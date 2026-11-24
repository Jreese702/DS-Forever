package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "Build last, leave out of count" (the user's pick, 2026-11-23: "Keep building them, but after everything else, and don't count them
 * toward 'finished'."). Dungeons and raids (buildings placed only on maps the game's Map table calls a dungeon or a raid) are built after
 * every other building, and the run is FINISHED once every other building is built or settled: its numbers count only those, and the
 * dungeons go on building in the same windows without changing the finished row ("+ dungeons building"). A game update re-arms both.
 */
class IndoorDungeonsLastTest {
    /** world buildings on continent 0, dungeons on map 36 (the Deadmines), one root on both (an entrance: counted with the world) */
    private class Gen(val rule: Set<String> = emptySet(), val transient: Set<String> = emptySet(), val instances: Set<Int>? = setOf(36),
                      val onProduce: (String) -> Unit = {}) : IndoorGenerator {
        val made = ArrayList<String>()
        var instanceAsks = 0
        override val algorithm = 1
        override fun instanceMaps(): Set<Int>? { instanceAsks++; return instances }
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> = WORKS
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

    private class Sig(var pos: IndoorBakeQueue.Position? = null, val lock: IndoorScheduler.WakeLock? = null) : IndoorScheduler.Signals {
        var t = 0L
        var busyUntil = -1L
        override fun position() = pos
        override fun busy() = t < busyUntil
        override fun hot() = false
        override fun stop() = false
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
        override fun wakeLock() = lock
    }

    private val build = "0123456789abcdef0123456789abcdef"
    private val app = "gen|a1|lib:aa"

    /** as on the device: every status written to status.json (IndoorSchedulerStep.publishStatus), the next run starts from it */
    private fun run(root: File, gen: IndoorGenerator, sig: Sig = Sig(), b: String = build, st: MutableList<IndoorScheduler.Status>? = null,
                    builder: String = app) =
        IndoorScheduler(IndoorBakeStore(root), root, b, gen, sig, File(root, "interiors_sched"), onStatus = {
            st?.add(it); File(root, "interiors_sched/" + IndoorScheduler.STATUS_FILE).writeText(it.toJson().toString())
        }, app = builder).run()

    @Test fun dungeonsAreBuiltAfterEverythingElse() {
        val root = Files.createTempDirectory("dng").toFile()
        val gen = Gen()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, gen))
        assertEquals(WORLD.toSet(), gen.made.take(WORLD.size).toSet())
        assertEquals(DUNGEON.toSet(), gen.made.drop(WORLD.size).toSet())
    }

    @Test fun nearMeFirstStillWorksAndNeverPullsADungeonAhead() {
        // the player by w_far: it comes first, the rest of the world next, the dungeons last
        val g1 = Gen()
        run(Files.createTempDirectory("dng2").toFile(), g1, Sig(IndoorBakeQueue.Position(0, 2005.0, 5.0)))
        assertEquals("w_far", g1.made.first())
        assertEquals(WORLD.toSet(), g1.made.take(WORLD.size).toSet())
        // a position inside a dungeon (its own map id): still the world first
        val g2 = Gen()
        run(Files.createTempDirectory("dng3").toFile(), g2, Sig(IndoorBakeQueue.Position(36, 5.0, 5.0)))
        assertEquals(WORLD.toSet(), g2.made.take(WORLD.size).toSet())
    }

    @Test fun finishedOnceTheWorldIsSettledWhileTheDungeonsGoOn() {
        val root = Files.createTempDirectory("dng4").toFile()
        val st = ArrayList<IndoorScheduler.Status>()
        var atFirstDungeon: IndoorScheduler.Status? = null
        val worldPending = ArrayList<Boolean>()
        val gen = Gen(rule = setOf("w_hall", "d_boss")) { k -> if (k.startsWith("d_") && atFirstDungeon == null) atFirstDungeon = st.last() }
        val outcome = IndoorScheduler(IndoorBakeStore(root), root, build, gen, Sig(), File(root, "interiors_sched"), app = app,
            onStatus = { st.add(it); worldPending.add(gen.made.count { m -> m.startsWith("w_") } < WORLD.size) }).run()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, outcome)
        val f = atFirstDungeon!!
        assertEquals("finished before the first dungeon is built", IndoorScheduler.Phase.DONE, f.phase)
        assertEquals("only the world counts", WORLD.size - 1, f.ready)
        assertEquals(WORLD.size, f.total)
        assertEquals(1, f.rejected)
        assertEquals(DUNGEON.size, f.dungeons)
        assertEquals("Indoor Maps (Optional): Finished: 3 maps, 1 place can't be mapped + dungeons building", IndoorSchedulerStep.readiness(f).label)
        assertTrue(IndoorSchedulerStep.readiness(f).done)
        // never "finished" while a world building is pending
        for ((i, s) in st.withIndex()) if (s.phase == IndoorScheduler.Phase.DONE) assertFalse("status $i: $s", worldPending[i])
        val firstDone = st.indexOfFirst { it.phase == IndoorScheduler.Phase.DONE }
        assertTrue("the row stays finished while the dungeons build: ${st.drop(firstDone).map { it.phase }}",
            st.drop(firstDone).all { it.phase == IndoorScheduler.Phase.DONE && it.total == WORLD.size })
        val end = st.last()
        assertEquals(0, end.dungeons)
        assertEquals("the numbers never take the dungeons in", WORLD.size - 1, end.ready)
        assertEquals("Indoor Maps (Optional): Finished: 3 maps, 1 place can't be mapped", IndoorSchedulerStep.readiness(end).label)
        assertEquals("$build|$app", end.finishedFor)
    }

    @Test fun aPauseWhileTheDungeonsBuildKeepsTheFinishedRow() {
        val root = Files.createTempDirectory("dng5").toFile()
        val sig = Sig()
        val st = ArrayList<IndoorScheduler.Status>()
        val gen = Gen { k -> if (k == DUNGEON.first()) sig.busyUntil = sig.t + 10_000L }
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, gen, sig, st = st))
        val firstDone = st.indexOfFirst { it.phase == IndoorScheduler.Phase.DONE }
        assertTrue(firstDone >= 0)
        assertEquals("no pause line over the finished row", emptyList<IndoorScheduler.Phase>(),
            st.drop(firstDone).map { it.phase }.filter { it != IndoorScheduler.Phase.DONE })
    }

    @Test fun aDungeonThatFailsLeavesTheRowFinishedAndTheStepOpen() {
        val root = Files.createTempDirectory("dng6").toFile()
        val st = ArrayList<IndoorScheduler.Status>()
        assertEquals("the step stays pending (not stamped): the dungeon is tried again", IndoorScheduler.Outcome.DEFERRED,
            run(root, Gen(transient = setOf("d_boss")), st = st))
        assertEquals(IndoorScheduler.Phase.DONE, st.last().phase)
        assertEquals(1, st.last().dungeons)
        assertTrue(IndoorSchedulerStep.readiness(st.last()).done)
        // the next run: only the dungeon, the row finished from its first status on
        val st2 = ArrayList<IndoorScheduler.Status>()
        val g2 = Gen()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, g2, st = st2))
        assertEquals(listOf("d_boss"), g2.made)
        assertTrue("finished from the start: ${st2.map { it.phase }}", st2.all { it.phase == IndoorScheduler.Phase.DONE })
    }

    @Test fun aWorldFailureIsNotFinishedEvenWithTheDungeonsBuilt() {
        val st = ArrayList<IndoorScheduler.Status>()
        assertEquals(IndoorScheduler.Outcome.DEFERRED, run(Files.createTempDirectory("dng7").toFile(), Gen(transient = setOf("w_far")), st = st))
        assertEquals(IndoorScheduler.Phase.DEFERRED, st.last().phase)
        assertEquals("1 to retry", st.last().note)
        assertTrue(st.none { it.phase == IndoorScheduler.Phase.DONE })
    }

    @Test fun aGameUpdateReArmsTheWorldAndTheDungeons() {
        val root = Files.createTempDirectory("dng8").toFile()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, Gen(rule = setOf("w_hall", "d_boss"))))
        val st = ArrayList<IndoorScheduler.Status>()
        val g = Gen(rule = setOf("w_hall", "d_boss"))
        // a new builder with the game update (an update that leaves every building's files as they were keeps them settled: 1525,
        // IndoorUpdateReuseTest): both rule rejections tried again, the world one first
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, g, b = "fedcba9876543210fedcba9876543210", st = st, builder = "gen|a1|lib:bb"))
        assertEquals("both rule rejections tried again, the world one first", listOf("w_hall", "d_boss"), g.made)
        assertEquals("a finished row of the old build is not shown for the new one", IndoorScheduler.Phase.CATALOGUE, st.first().phase)
    }

    @Test fun theDungeonMapsAreKeptWithTheCachedCatalogue() {
        val root = Files.createTempDirectory("dng9").toFile()
        run(root, Gen(transient = setOf("d_boss")))
        // the next run reads the cached catalogue: the dungeon maps with it (a generator that cannot answer now changes nothing)
        val g = Gen(instances = null)
        val st = ArrayList<IndoorScheduler.Status>()
        run(root, g, st = st)
        assertEquals(WORLD.size, st.last().total)
        // a cache from before (no dungeon maps in it): asked once, then kept
        val dir = File(root, "interiors_sched")
        val cache = File(dir, "catalogue-$build.json")
        val o = JSONObject(cache.readText()); o.remove("instances"); cache.writeText(o.toString())
        val g2 = Gen()
        run(root, g2)
        assertEquals(1, g2.instanceAsks)
        assertTrue(JSONObject(cache.readText()).has("instances"))
    }

    @Test fun noDungeonMapsKnownCountsEverything() {
        val st = ArrayList<IndoorScheduler.Status>()
        run(Files.createTempDirectory("dng10").toFile(), Gen(instances = null), st = st)
        assertEquals(WORKS.size, st.last().total)
        assertEquals(0, st.last().dungeons)
    }

    @Test fun theWakeLockIsHeldForTheDungeonsAndLetGoAtTheEnd() {
        var held = false; var acquires = 0
        val during = ArrayList<Boolean>()
        val lock = object : IndoorScheduler.WakeLock {
            override fun acquire(timeoutMs: Long) { held = true; acquires++ }
            override fun release() { held = false }
        }
        run(Files.createTempDirectory("dng11").toFile(), Gen { k -> if (k.startsWith("d_")) during.add(held) }, Sig(lock = lock))
        assertEquals(listOf(true, true), during)
        assertFalse(held)
    }

    @Test fun oneFailurePerRunCountsOnce() {
        val dir = Files.createTempDirectory("dng12").toFile()
        val d = IndoorDeferrals(File(dir, "deferred.json"), build, app)
        val w = WORKS.first()
        repeat(3) { d.defer(w, "interiors generator ran out of memory", 1L, run = "r1") }
        assertEquals(1, d.entry(w)!!.tries)
        assertFalse(d.skip(w))
        d.defer(w, "x out of memory", 2L, run = "r2"); d.defer(w, "x out of memory", 3L, run = "r3")
        assertTrue("the third run settles it", d.skip(w))
        // kept across a restart (a new instance over the same file)
        val again = IndoorDeferrals(File(dir, "deferred.json"), build, app)
        assertEquals(IndoorDeferrals.MAX_TRIES, again.entry(w)!!.tries)
        assertTrue(again.skip(w))
        assertFalse("another builder", IndoorDeferrals(File(dir, "deferred.json"), build, "gen|a1|lib:bb").skip(w))
    }

    @Test fun theStatusKeepsTheDungeonsAndItsFinishOnDisk() {
        val s = IndoorScheduler.Status(IndoorScheduler.Phase.DONE, 1085, 1306, 221, rejected = 221, dungeons = 547, finishedFor = "$build|$app")
        assertEquals(s, IndoorScheduler.Status.of(JSONObject(s.toJson().toString())))
        assertEquals("1085 / 1306 ready · finished · 221 can't be mapped · dungeons building", IndoorProgress.line(s, true))
        val row = SetupFlow.checks(SetupFlow.Inputs(path = "/x", folderFound = true, art = SetupFlow.Art.READY,
            indoor = IndoorSchedulerStep.readiness(s).label.substringAfter(": "), indoorDone = true)).first { it.label.startsWith("Indoor") }
        assertEquals(SetupFlow.Mark.OK, row.mark)
        assertEquals("Indoor maps finished: 1,085 maps, 221 places can't be mapped + dungeons building", row.label)
    }

    @Test fun theWaitBeforeARunKeepsAFinishedRowForThisBuildOnly() {
        val done = IndoorScheduler.Status(IndoorScheduler.Phase.DONE, 3, 4, 1, rejected = 1, dungeons = 2, finishedFor = "$build|$app")
        assertEquals(IndoorScheduler.Phase.DONE, IndoorSchedulerStep.waitStatus(done, "Waiting: game running", "$build|$app").phase)
        val other = IndoorSchedulerStep.waitStatus(done, "Waiting: game running", "fedcba9876543210fedcba9876543210|$app")
        assertEquals("a game update: the count waits, not finished", IndoorScheduler.Phase.PAUSED, other.phase)
        assertEquals(3, other.ready); assertEquals(4, other.total)
        assertEquals(IndoorScheduler.Phase.PAUSED, IndoorSchedulerStep.waitStatus(done, "w", null).phase)
        // Off in Settings and On again: still finished for the same build
        val off = IndoorSchedulerStep.offStatus(done)
        assertEquals(IndoorScheduler.Phase.STOPPED, off.phase)
        assertEquals(IndoorScheduler.Phase.DONE, IndoorSchedulerStep.waitStatus(off, "w", "$build|$app").phase)
        assertEquals(IndoorScheduler.Phase.PAUSED, IndoorSchedulerStep.waitStatus(null, "w", "k").phase)
    }

    @Test fun theDungeonLineIsTranslated() {
        for ((lang, want) in listOf("es" to "Terminado: 3 mapas + mazmorras en curso", "fr" to "Terminé : 3 cartes + donjons en cours")) {
            val f = File(System.getProperty("user.dir")).let { d ->
                listOf(File(d, "../companion/app/src/main/assets/companion/i18n/$lang.json"), File(d, "companion/app/src/main/assets/companion/i18n/$lang.json"))
                    .first { it.isFile }
            }
            Tr.load(lang, f.readText())
            assertEquals(want, Tr.t("Finished: 3 maps + dungeons building", lang))
            assertTrue(Tr.t("Indoor maps finished: 1,085 maps, 221 places can't be mapped + dungeons building", lang) !=
                "Indoor maps finished: 1,085 maps, 221 places can't be mapped + dungeons building")
        }
    }

    companion object {
        private fun w(k: String, i: Int, vararg at: Pair<Int, Double>) = IndoorBakeStore.Work(k, "%064x".format(i), 1,
            at.map { (c, x) -> IndoorBakeStore.Location(c, x, x + 10.0, 0.0, 10.0) })
        internal val WORKS = listOf(
            w("d_boss", 1, 36 to 0.0), w("d_hall", 2, 36 to 500.0),
            w("w_entrance", 3, 0 to 100.0, 36 to 900.0), w("w_hall", 4, 0 to 1000.0), w("w_inn", 5, 0 to 50.0), w("w_far", 6, 0 to 2000.0))
        val WORLD = listOf("w_entrance", "w_hall", "w_inn", "w_far")
        val DUNGEON = listOf("d_boss", "d_hall")
    }
}
