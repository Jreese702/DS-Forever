package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/**
 * A GAME UPDATE REUSES WHAT DID NOT CHANGE (the user's pick, 2026-11-24, after the device read on test 1524 - the game moved to
 * 1.60.1.71037 and every indoor map was hidden, interiors/current.json still stamped with the old build: "Reuse what didn't change": "Right
 * after an update, check each building's files. Ones that didn't change are carried over straight away, and only changed buildings get
 * rebuilt. Most maps show again within minutes."). A building's signature (its builder's algorithm, its key and the digest of its spec and
 * of the CASC content key of every file it reads) holds no game build, so after an update the CARRY-OVER lists the buildings again and
 * publishes every unchanged one under the NEW build at once (a whole publication, the same checks as any), queues only the changed and
 * the new ones, and keeps the builder's rule turn-downs of unchanged buildings settled. These tests run the real scheduler and store over
 * made-up generators. The carry-over is called by reflection, so this file runs (and fails) on the code before it (99b540d37).
 */
class IndoorUpdateReuseTest {
    /** a made-up generator: [inputs] per key (its digest; a change = a changed building), [rule] keys turned down by its rule */
    private class Gen(val inputs: Map<String, Int>, val rule: Set<String> = emptySet(), val instances: Set<Int>? = null,
                      val maps: Map<String, Int> = emptyMap(), val onList: () -> Unit = {}) : IndoorGenerator {
        val made = ArrayList<String>()
        var listed = 0
        override val algorithm = 1
        override fun instanceMaps(): Set<Int>? = instances
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            listed++
            onList()
            yield.point()
            return inputs.entries.mapIndexed { i, (k, v) ->
                IndoorBakeStore.Work(k, "%064x".format(v), 1, listOf(IndoorBakeStore.Location(maps[k] ?: 0, i * 50.0, i * 50.0 + 10.0, 0.0, 10.0)))
            }
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            yield.point()
            made.add(work.key)
            if (work.key in rule) throw IndoorGenerator.Rejected("room exceeds the bounded layout policy; requires its own spatial/vertical subdivision")
            IndoorFinishTest.building(out, work.key)
        }
        override fun close() {}
    }

    private class Sig : IndoorScheduler.Signals {
        var t = 0L
        var busyUntil = -1L
        var stopNow = false
        var stopWhen: () -> Boolean = { false }
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = t < busyUntil
        override fun hot() = false
        override fun stop() = stopNow || stopWhen()
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    private val A = "0123456789abcdef0123456789abcdef"
    private val B = "ad887adcf63b8746155dd40e1930b80f".reversed()
    private val C = "0acab1e6aa41b46165d2ca66853b417b"
    private val app = "gen|a1|lib:aa"

    private fun dir(root: File) = File(root, "interiors_sched")

    /** as on the device: every status written to status.json (IndoorSchedulerStep.publishStatus) */
    private fun scheduler(root: File, gen: IndoorGenerator, b: String, st: MutableList<IndoorScheduler.Status>? = null, sig: Sig = Sig(),
                          builder: String = app) =
        IndoorScheduler(IndoorBakeStore(root), root, b, gen, sig, dir(root), onStatus = {
            st?.add(it); dir(root).mkdirs(); File(dir(root), IndoorScheduler.STATUS_FILE).writeText(it.toJson().toString())
        }, app = builder)

    private fun run(root: File, gen: IndoorGenerator, b: String, st: MutableList<IndoorScheduler.Status>? = null, sig: Sig = Sig(),
                    builder: String = app) = scheduler(root, gen, b, st, sig, builder).run()

    /** IndoorScheduler.carryOver() (1525), by name: on the code before it this fails here, not at compile time */
    private fun carry(s: IndoorScheduler): Any? = try {
        IndoorScheduler::class.java.getMethod("carryOver").invoke(s)
    } catch (e: InvocationTargetException) { throw e.targetException }

    private fun prop(o: Any, name: String): Any? = o.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercaseChar() }).invoke(o)

    /** the published buildings for [b] (the keys of its continent-0 models index), or null when nothing is published for it */
    private fun published(root: File, b: String): Set<String>? {
        val s = IndoorAssets.snapshot(root, b)
        if (!s.directory.isDirectory) return null
        val f = s.models(0)
        return if (f.isFile) JSONObject(f.readText()).keys().asSequence().toSet() else emptySet()
    }

    private fun pointerBuild(root: File) = JSONObject(File(root, "interiors/current.json").readText()).optString("build")

    // ------------------------------------------------------------------ the carry-over

    @Test fun aGameUpdateCarriesTheUnchangedBuildingsOverAtOnce() {
        val root = Files.createTempDirectory("reuse").toFile()
        val before = mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 3)
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, Gen(before), A))
        assertEquals(setOf("wmo_a", "wmo_b", "wmo_c"), published(root, A))
        // the game update: wmo_c's files changed, wmo_d is new
        val after = mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 33, "wmo_d" to 4)
        assertNull("before the carry-over the new build shows nothing (MapView needs an exact build)", published(root, B))
        val gen = Gen(after)
        val st = ArrayList<IndoorScheduler.Status>()
        val carried = carry(scheduler(root, gen, B, st))
        assertNotNull(carried)
        assertEquals("nothing is built by the carry-over", emptyList<String>(), gen.made)
        assertEquals("the unchanged buildings show under the new build at once", setOf("wmo_a", "wmo_b"), published(root, B))
        assertEquals(B, pointerBuild(root))
        assertNull("the old build's pointer is gone (one current publication)", published(root, A))
        assertEquals(2, prop(carried!!, "kept")); assertEquals(2, prop(carried, "rebuild"))
        // the row: the carried count, never 0/0
        val last = st.last()
        assertEquals(2, last.ready); assertEquals(4, last.total)
        assertEquals("Indoor Maps (Optional): Updated for the new game build: 2 kept, 2 to rebuild", IndoorSchedulerStep.readiness(last).label)
        assertTrue("never 0/0: ${st.map { "${it.ready}/${it.total}" }}", st.none { it.total == 0 })
        // the bake that follows builds only the changed and the new building, and does not list the buildings again
        val bake = Gen(after)
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, bake, B))
        assertEquals(setOf("wmo_c", "wmo_d"), bake.made.toSet())
        assertEquals("the carry-over's listing is the bake's (one listing per game update)", 0, bake.listed)
        assertEquals(setOf("wmo_a", "wmo_b", "wmo_c", "wmo_d"), published(root, B))
    }

    @Test fun aWaitAfterTheCarryOverKeepsItsCount() {
        val root = Files.createTempDirectory("reuse2").toFile()
        run(root, Gen(mapOf("wmo_a" to 1, "wmo_b" to 2)), A)
        val st = ArrayList<IndoorScheduler.Status>()
        carry(scheduler(root, Gen(mapOf("wmo_a" to 1, "wmo_b" to 22)), B, st))
        val wait = IndoorSchedulerStep.waitStatus(st.last(), PlayGate.GAME_RUNNING, IndoorScheduler.finishKey(B, app))
        assertEquals(1, wait.ready); assertEquals(2, wait.total)
        assertEquals("Indoor Maps (Optional): Updated for the new game build: 1 kept, 1 to rebuild", IndoorSchedulerStep.readiness(wait).label)
        // a later game update with no carry-over yet: the old line is not shown for it
        val next = IndoorSchedulerStep.waitStatus(st.last(), PlayGate.GAME_RUNNING, IndoorScheduler.finishKey(C, app))
        assertEquals("Indoor Maps (Optional): 1/2 ready - Waiting: game running", IndoorSchedulerStep.readiness(next).label)
    }

    @Test fun nothingChangedFinishesAtOnce() {
        val root = Files.createTempDirectory("reuse3").toFile()
        val keys = mapOf("wmo_a" to 1, "wmo_b" to 2)
        run(root, Gen(keys, rule = setOf("wmo_b")), A)
        val st = ArrayList<IndoorScheduler.Status>()
        val gen = Gen(keys, rule = setOf("wmo_b"))
        carry(scheduler(root, gen, B, st))
        assertEquals(setOf("wmo_a"), published(root, B))
        assertEquals("every building unchanged and the rule's one settled: finished for the new build", IndoorScheduler.Phase.DONE, st.last().phase)
        assertEquals(IndoorScheduler.finishKey(B, app), st.last().finishedFor)
        assertEquals("Indoor Maps (Optional): Finished: 1 map, 1 place can't be mapped", IndoorSchedulerStep.readiness(st.last()).label)
        assertEquals(emptyList<String>(), gen.made)
    }

    // ------------------------------------------------------------------ the builder's rule across a game update

    @Test fun aSettledRuleRejectionStaysSettledAcrossAGameUpdate() {
        val root = Files.createTempDirectory("reuse4").toFile()
        val keys = mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 3)
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, Gen(keys, rule = setOf("wmo_b", "wmo_c")), A))
        // the game update: wmo_b unchanged, wmo_c's files changed
        val gen = Gen(mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 30), rule = setOf("wmo_b", "wmo_c"))
        val st = ArrayList<IndoorScheduler.Status>()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, gen, B, st))
        assertEquals("the unchanged turn-down is not tried again, the changed one is", listOf("wmo_c"), gen.made)
        assertEquals(2, st.last().rejected)
        val saved = JSONObject(File(dir(root), "deferred.json").readText())
        assertTrue("re-keyed to the new build", saved.getJSONObject("wmo_b").getString("id").startsWith("$B|$app|"))
        // a new builder (its native library) still tries it again
        val newLib = Gen(mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 30), rule = setOf("wmo_b", "wmo_c"))
        run(root, newLib, B, builder = "gen|a1|lib:bb")
        assertEquals(setOf("wmo_b", "wmo_c"), newLib.made.toSet())
    }

    @Test fun aBuilderChangeAndAGameUpdateTogetherKeepTheBuildingsButTryTheRuleAgain() {
        val root = Files.createTempDirectory("reuse5").toFile()
        val keys = mapOf("wmo_a" to 1, "wmo_b" to 2)
        run(root, Gen(keys, rule = setOf("wmo_b")), A)
        val gen = Gen(keys, rule = setOf("wmo_b"))
        val st = ArrayList<IndoorScheduler.Status>()
        val carried = carry(scheduler(root, gen, B, st, builder = "gen|a1|lib:bb"))
        assertEquals("the built one is the same output (the same algorithm and inputs)", setOf("wmo_a"), published(root, B))
        assertEquals(1, prop(carried!!, "kept")); assertEquals("the rule's one is open again for the new builder", 1, prop(carried, "rebuild"))
        assertEquals(IndoorScheduler.Phase.PAUSED, st.last().phase)
    }

    // ------------------------------------------------------------------ robust

    @Test fun anInterruptedCarryOverIsSafe() {
        val root = Files.createTempDirectory("reuse6").toFile()
        run(root, Gen(mapOf("wmo_a" to 1, "wmo_b" to 2)), A)
        val oldPointer = File(root, "interiors/current.json").readText()
        // stopped while it lists the buildings (the game starting: the art run's pause; a kill is the same - nothing written yet)
        val sig = Sig()
        val gen = Gen(mapOf("wmo_a" to 1, "wmo_b" to 22)) { sig.stopNow = true }
        try { carry(scheduler(root, gen, B, sig = sig)); fail("the stop goes to its caller") } catch (e: IndoorGenerator.Stopped) { }
        catch (e: NoSuchMethodException) { throw e }
        assertEquals("the old publication untouched", oldPointer, File(root, "interiors/current.json").readText())
        assertEquals(setOf("wmo_a", "wmo_b"), published(root, A))
        assertFalse("no half-listed catalogue cached", File(dir(root), "catalogue-$B.json").exists())
        assertTrue("no publication staging left", File(root, "interiors/snapshots").listFiles()!!.none { it.name.startsWith(".pending_") })
        // killed after the listing, before the publication (the catalogue cached, the pointer still the old build): the next one publishes
        val gen2 = Gen(mapOf("wmo_a" to 1, "wmo_b" to 22))
        IndoorScheduler.writeCatalogue(File(dir(root), "catalogue-$B.json"), 1, gen2.catalogue { })
        gen2.listed = 0
        val carried = carry(scheduler(root, gen2, B))
        assertNotNull(carried)
        assertEquals(setOf("wmo_a"), published(root, B))
        assertEquals(0, gen2.listed)
        // and once published for this build, a carry-over again has nothing to do
        assertNull("not due again", carry(scheduler(root, Gen(mapOf("wmo_a" to 1, "wmo_b" to 22)), B)))
    }

    @Test fun aGameUpdateMidBuildThenAnotherOne() {
        val root = Files.createTempDirectory("reuse7").toFile()
        run(root, Gen(mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 3)), A)
        // B: wmo_b and wmo_c changed; its bake is stopped after one building (the next game update stops it)
        val genB = Gen(mapOf("wmo_a" to 1, "wmo_b" to 20, "wmo_c" to 30))
        assertEquals(1, prop(carry(scheduler(root, genB, B))!!, "kept"))
        val sig = Sig().apply { stopWhen = { genB.made.isNotEmpty() } }
        assertEquals(IndoorScheduler.Outcome.STOPPED, run(root, genB, B, sig = sig))
        assertEquals(listOf("wmo_b"), genB.made)
        // C: wmo_c changed again, wmo_d is new: what B's bake finished is kept too
        val genC = Gen(mapOf("wmo_a" to 1, "wmo_b" to 20, "wmo_c" to 300, "wmo_d" to 4))
        val carried = carry(scheduler(root, genC, C))
        assertEquals(setOf("wmo_a", "wmo_b"), published(root, C))
        assertEquals(2, prop(carried!!, "kept")); assertEquals(2, prop(carried, "rebuild"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, genC, C))
        assertEquals(setOf("wmo_c", "wmo_d"), genC.made.toSet())
        // two updates in a row with no carry-over between them (the game played at once): unchanged since the last built one is kept
        val genE = Gen(mapOf("wmo_a" to 1, "wmo_b" to 20, "wmo_c" to 300, "wmo_d" to 44))
        val E = "e".repeat(32)
        assertEquals(3, prop(carry(scheduler(root, genE, E))!!, "kept"))
        assertEquals(setOf("wmo_a", "wmo_b", "wmo_c"), published(root, E))
    }

    @Test fun anEmptyListingIsNeverCachedOrPublished() {
        // a listing that found nothing (the game files not readable yet after an update) must not hide every building for the whole build
        val root = Files.createTempDirectory("reuse8").toFile()
        run(root, Gen(mapOf("wmo_a" to 1)), A)
        val pointer = File(root, "interiors/current.json").readText()
        runCatching { carry(scheduler(root, Gen(emptyMap()), B)) }
        assertEquals(pointer, File(root, "interiors/current.json").readText())
        assertFalse(File(dir(root), "catalogue-$B.json").exists())
        runCatching { run(root, Gen(emptyMap()), B) }
        assertEquals("the bake's own listing too", pointer, File(root, "interiors/current.json").readText())
        assertFalse(File(dir(root), "catalogue-$B.json").exists())
    }

    @Test fun theDungeonsStayLastAndOutOfTheCount() {
        val root = Files.createTempDirectory("reuse9").toFile()
        val keys = mapOf("w_a" to 1, "w_b" to 2, "d_a" to 3, "d_b" to 4)
        val maps = mapOf("d_a" to 36, "d_b" to 36)
        run(root, Gen(keys, instances = setOf(36), maps = maps), A)
        val st = ArrayList<IndoorScheduler.Status>()
        val carried = carry(scheduler(root, Gen(mapOf("w_a" to 1, "w_b" to 20, "d_a" to 3, "d_b" to 40), instances = setOf(36), maps = maps), B, st))
        assertEquals(setOf("w_a", "d_a"), published(root, B))
        assertEquals("the world only", 1, prop(carried!!, "kept")); assertEquals(1, prop(carried, "rebuild"))
        assertEquals(2, st.last().total); assertEquals(1, st.last().ready); assertEquals(1, st.last().dungeons)
    }

    // ------------------------------------------------------------------ never 0/0

    @Test fun aZeroCountOnDiskIsReadFromTheBuildingsOnDisk() {
        // the device read (test 1524): status.json PAUSED 0/0 "waiting: game running" after the game update, the buildings all on disk
        val root = Files.createTempDirectory("reuse10").toFile()
        run(root, Gen(mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 3)), A)
        File(dir(root), IndoorScheduler.STATUS_FILE).writeText(IndoorScheduler.Status(IndoorScheduler.Phase.PAUSED, 0, 0, note = PlayGate.GAME_RUNNING).toJson().toString())
        val sig = Sig().apply { busyUntil = 10_000L }
        val st = ArrayList<IndoorScheduler.Status>()
        run(root, Gen(mapOf("wmo_a" to 1, "wmo_b" to 2, "wmo_c" to 3)), A, st, sig)
        val first = st.first()
        assertEquals("the count the buildings on disk give, not 0/0", 3, first.total)
        assertEquals(3, first.ready)
    }
}
