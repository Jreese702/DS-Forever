package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * D1 (the user, 2026-11-23, on test 1523: "I've been playing for months and it still just never says finished"; the pick "Done when all
 * mappable built": "Shows 'Finished: 1,632 maps, 221 places can't be mapped', the row turns green, and it stops re-running. It only
 * retries those places after a game or builder update"). About 221 of the 1,853 buildings are turned down by the builder's own rules on
 * every try (65 city halls too big for the room rule, no floors, a budget), and a turned-down building counted as "not done", so the
 * build could never be done and started over after every game. These tests run the real scheduler and store over a made-up generator.
 */
class IndoorFinishTest {
    /** a made-up generator: [rule] keys are turned down by its rule, [transient] ones fail in a way that may pass, the rest build */
    private class Gen(val keys: List<String>, val rule: Set<String> = emptySet(), val transient: Set<String> = emptySet(),
                      val onProduce: (String) -> Unit = {}) : IndoorGenerator {
        val made = ArrayList<String>()
        override val algorithm = 1
        override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> {
            yield.point()
            return keys.mapIndexed { i, k ->
                IndoorBakeStore.Work(k, ("%064x".format(i + 1)), 1, listOf(IndoorBakeStore.Location(0, i * 50.0, i * 50.0 + 10.0, 0.0, 10.0)))
            }
        }
        override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {
            made.add(work.key)
            onProduce(work.key)
            yield.point()
            if (work.key in rule) throw IndoorGenerator.Rejected("room exceeds the bounded layout policy; requires its own spatial/vertical subdivision")
            if (work.key in transient) throw IllegalStateException("interiors generator ran out of memory")
            building(out, work.key)
        }
        override fun close() {}
    }

    private class Sig(val lock: IndoorScheduler.WakeLock? = null) : IndoorScheduler.Signals {
        var t = 0L
        var gameUp = false
        var stopNow = false
        override fun position(): IndoorBakeQueue.Position? = null
        override fun busy() = false
        override fun gameRunning() = gameUp
        override fun hot() = false
        override fun stop() = stopNow
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
        override fun wakeLock() = lock
    }

    private val build = "0123456789abcdef0123456789abcdef"

    private fun run(root: File, gen: Gen, app: String = "gen|a1|lib:aa", b: String = build, sig: Sig = Sig(),
                    statuses: MutableList<IndoorScheduler.Status>? = null, logs: MutableList<String>? = null): IndoorScheduler.Outcome =
        IndoorScheduler(IndoorBakeStore(root), root, b, gen, sig, File(root, "interiors_sched"),
            onStatus = { statuses?.add(it) }, log = { logs?.add(it) }, app = app).run()

    // ------------------------------------------------------------------ the finish line

    @Test fun ruleRejectionsAreSettledSoTheBuildFinishes() {
        val root = Files.createTempDirectory("finish").toFile()
        val st = ArrayList<IndoorScheduler.Status>()
        val gen = Gen(listOf("wmo_a", "wmo_b", "wmo_c"), rule = setOf("wmo_b"))
        assertEquals("every building built or turned down by the rule: finished", IndoorScheduler.Outcome.COMPLETE, run(root, gen, statuses = st))
        val end = st.last()
        assertEquals(IndoorScheduler.Phase.DONE, end.phase)
        assertEquals(2, end.ready); assertEquals(3, end.total); assertEquals(1, end.rejected)
        assertEquals("Indoor Maps (Optional): Finished: 2 maps, 1 place can't be mapped", IndoorSchedulerStep.readiness(end).label)
        assertTrue("the row turns green", IndoorSchedulerStep.readiness(end).done)
        // the next run (a new game session) tries nothing again and is finished at once
        val again = Gen(listOf("wmo_a", "wmo_b", "wmo_c"), rule = setOf("wmo_b"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, again))
        assertEquals("the rule's rejection is not tried again for the same game and builder", emptyList<String>(), again.made)
    }

    @Test fun aFailureThatMayPassKeepsItOpenAndIsTriedAgain() {
        val root = Files.createTempDirectory("finish2").toFile()
        val st = ArrayList<IndoorScheduler.Status>()
        val gen = Gen(listOf("wmo_a", "wmo_b", "wmo_c"), rule = setOf("wmo_b"), transient = setOf("wmo_c"))
        assertEquals(IndoorScheduler.Outcome.DEFERRED, run(root, gen, statuses = st))
        assertEquals(IndoorScheduler.Phase.DEFERRED, st.last().phase)
        assertEquals("1 to retry", st.last().note)
        assertFalse("not finished", IndoorSchedulerStep.readiness(st.last()).done)
        // the next run tries the transient one again (and only it); it builds now: finished
        val again = Gen(listOf("wmo_a", "wmo_b", "wmo_c"), rule = setOf("wmo_b"))
        val st2 = ArrayList<IndoorScheduler.Status>()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, again, statuses = st2))
        assertEquals(listOf("wmo_c"), again.made)
        assertEquals("Indoor Maps (Optional): Finished: 2 maps, 1 place can't be mapped", IndoorSchedulerStep.readiness(st2.last()).label)
        assertEquals(2, st2.last().ready); assertEquals(1, st2.last().rejected)
    }

    @Test fun aNativeOutOfMemoryIsNoRuleButTheRoomPolicyIs() {
        // the PC full-catalogue run's reasons (E:/wc-int-out/d/fc-d7ed255f.log, 2026-10-13): all 221 are the builder's rule
        for (why in listOf("architectural section preview has no storeys", "empty_walk_requires_investigation",
                "height grid contains surfaces assigned to no storey or stair", "merged Recast span budget exceeded",
                "no section storey has a supporting kept walk surface",
                "room exceeds the bounded layout policy; requires its own spatial/vertical subdivision",
                "room has no measured walk at an actual boundary portal", "walk-section face budget exceeded or empty walk"))
            assertTrue(why, IndoorGenerator.isRule(why))
        for (why in listOf("interiors generator ran out of memory", "Recast allocation limit: heightfield", "group 1234 unavailable",
                "terrain ADT 55 unavailable", "interiors generator: vector too long", "", null))
            assertFalse("$why", IndoorGenerator.isRule(why))
    }

    @Test fun aNewBuilderOrAGameUpdateTriesTheRuleRejectionsAgain() {
        val root = Files.createTempDirectory("finish3").toFile()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, Gen(listOf("wmo_a", "wmo_b"), rule = setOf("wmo_b"))))
        val sameBuilder = Gen(listOf("wmo_a", "wmo_b"), rule = setOf("wmo_b"))
        run(root, sameBuilder)
        assertEquals(emptyList<String>(), sameBuilder.made)
        // a new native library (the builder's key): tried once more
        val newLib = Gen(listOf("wmo_a", "wmo_b"), rule = setOf("wmo_b"))
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, newLib, app = "gen|a1|lib:bb"))
        assertEquals(listOf("wmo_b"), newLib.made)
        // a game update (another build): once more
        val newGame = Gen(listOf("wmo_a", "wmo_b"), rule = setOf("wmo_b"))
        run(root, newGame, app = "gen|a1|lib:bb", b = "fedcba9876543210fedcba9876543210")
        assertEquals(listOf("wmo_b"), newGame.made)
    }

    @Test fun theBuildersKeyHoldsItsLibraryNotTheApk() {
        assertEquals("gen|a3|interiors-native recast 6dc1667f|lib:0123abcd", IndoorScheduler.builderKey("a3|interiors-native recast 6dc1667f", "0123abcd"))
        assertTrue("the old constant never matches a real key", IndoorScheduler.builderKey("a3|x", "y") != IndoorDeferrals.LEGACY_APP)
    }

    @Test fun deferralsFromBeforeThisBuildKeepTheirRuleOnesSettled() {
        // 1.0.22 - 1.0.39 recorded every deferral as build|gen|signature with no kind: the rule's ones stay settled for the current
        // builder (no 10-minute re-try just for this update), the others are tried again
        val root = Files.createTempDirectory("finish4").toFile()
        val dir = File(root, "interiors_sched").apply { mkdirs() }
        val works = Gen(listOf("wmo_a", "wmo_b", "wmo_c")).catalogue { }
        val old = JSONObject()
        old.put("wmo_b", JSONObject().put("id", "$build|gen|${works[1].signature}").put("why", "room exceeds the bounded layout policy; requires its own").put("at", 1L))
        old.put("wmo_c", JSONObject().put("id", "$build|gen|${works[2].signature}").put("why", "interiors generator ran out of memory").put("at", 1L))
        File(dir, "deferred.json").writeText(old.toString())
        val gen = Gen(listOf("wmo_a", "wmo_b", "wmo_c"))
        val st = ArrayList<IndoorScheduler.Status>()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, gen, statuses = st))
        assertEquals("the old out-of-memory one is tried again, the rule's one is not", listOf("wmo_a", "wmo_c"), gen.made.sorted())
        assertEquals(1, st.last().rejected)
        // saved with its kind: the next run skips it under the new identity too
        val saved = JSONObject(File(dir, "deferred.json").readText())
        assertTrue(saved.getJSONObject("wmo_b").getBoolean("rule"))
        assertEquals("$build|gen|a1|lib:aa|${works[1].signature}", saved.getJSONObject("wmo_b").getString("id"))
    }

    @Test fun anOldDeferralSettlesOnlyForAKnownRuleOfTheBuilder() {
        // review of 2026-11-23: 1.0.22 - 1.0.39 recorded ANY Throwable's message (a Java out-of-memory, a full disk, a stale catalogue).
        // Classified by "not transient-looking" they were settled as the builder's rule: left out until the next game update while the
        // row said finished. Only the builder's known rules stay settled; the rest are tried once more
        val root = Files.createTempDirectory("finish5").toFile()
        val dir = File(root, "interiors_sched").apply { mkdirs() }
        val keys = listOf("wmo_a", "wmo_b", "wmo_c", "wmo_d", "wmo_e")
        val works = Gen(keys).catalogue { }
        val old = JSONObject()
        fun put(i: Int, why: String) = old.put(keys[i], JSONObject().put("id", "$build|gen|${works[i].signature}").put("why", why).put("at", 1L))
        put(1, "Failed to allocate a 41943056 byte allocation with 25165824 free bytes and 30MB until OOM, target footprint 536870912")
        put(2, "/data/user/0/app/files/art/interiors/staging/x/l0.png: write failed: ENOSPC (No space left on device)")
        put(3, "building wmo_d is not in this game build's catalogue")
        put(4, "walk-section face budget exceeded or empty walk")
        File(dir, "deferred.json").writeText(old.toString())
        val gen = Gen(keys)
        val st = ArrayList<IndoorScheduler.Status>()
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, gen, statuses = st))
        assertEquals("only the known rule stays settled", listOf("wmo_a", "wmo_b", "wmo_c", "wmo_d"), gen.made.sorted())
        assertEquals(4, st.last().ready); assertEquals(1, st.last().rejected)
        assertTrue(IndoorGenerator.isKnownRule("room exceeds the bounded layout policy; requires its own spa"))
        assertFalse(IndoorGenerator.isKnownRule("invalid group bounds"))
        assertFalse(IndoorGenerator.isKnownRule(""))
    }

    @Test fun aPauseWhileListingKeepsTheLastCount() {
        // review of 2026-11-23 (fix 2's other half): each run began with CATALOGUE 0/0, so a pause (or a kill) while it listed the
        // buildings - minutes after a game update - wrote 0/0 to status.json and the row lost its count
        val root = Files.createTempDirectory("finish6").toFile()
        val dir = File(root, "interiors_sched").apply { mkdirs() }
        File(dir, IndoorScheduler.STATUS_FILE).writeText(IndoorScheduler.Status(IndoorScheduler.Phase.DONE, 1632, 1853, 221, rejected = 221).toJson().toString())
        var busyUntil = 5_000L
        val sig = object : IndoorScheduler.Signals {
            var t = 0L
            override fun position(): IndoorBakeQueue.Position? = null
            override fun busy() = t < busyUntil
            override fun hot() = false
            override fun stop() = false
            override fun now() = t
            override fun sleep(ms: Long) { t += ms }
        }
        val st = ArrayList<IndoorScheduler.Status>()
        IndoorScheduler(IndoorBakeStore(root), root, build, Gen(listOf("wmo_a")), sig, dir, onStatus = { st.add(it) }).run()
        val paused = st.first { it.phase == IndoorScheduler.Phase.PAUSED }
        assertEquals("the pause keeps the last count, not 0/0", 1632, paused.ready)
        assertEquals(1853, paused.total)
        assertTrue("never 0/0 before the listing ends: ${st.map { "${it.phase} ${it.ready}/${it.total}" }}",
            st.takeWhile { it.phase != IndoorScheduler.Phase.BAKING }.none { it.total == 0 })
        assertEquals("Indoor Maps (Optional): 1632/1853 ready - Waiting: game busy", IndoorSchedulerStep.readiness(paused).label)
    }

    @Test fun theStatusKeepsItsRejectedCountOnDisk() {
        val s = IndoorScheduler.Status(IndoorScheduler.Phase.DONE, 1632, 1853, 221, list = listOf("k: why"), rejected = 221)
        assertEquals(s, IndoorScheduler.Status.of(JSONObject(s.toJson().toString())))
        assertEquals("Indoor Maps (Optional): Finished: 1,632 maps, 221 places can't be mapped", IndoorSchedulerStep.readiness(s).label)
        assertEquals("1632 / 1853 ready · finished · 221 can't be mapped", IndoorProgress.line(s, true))
        assertEquals("Indoor Maps (Optional): Finished: 1,853 maps",
            IndoorSchedulerStep.readiness(IndoorScheduler.Status(IndoorScheduler.Phase.DONE, 1853, 1853)).label)
    }

    @Test fun theSetupRowTurnsGreenOnceFinished() {
        val base = SetupFlow.Inputs(path = "/x", folderFound = true, art = SetupFlow.Art.READY)
        val open = SetupFlow.checks(base.copy(indoor = "1616/1853 ready - Builds when minimized or screen off")).first { it.label.startsWith("Indoor") }
        assertEquals(SetupFlow.Mark.OPTIONAL, open.mark)
        val done = SetupFlow.checks(base.copy(indoor = "Finished: 1,632 maps, 221 places can't be mapped", indoorDone = true)).first { it.label.startsWith("Indoor") }
        assertEquals(SetupFlow.Mark.OK, done.mark)
        assertEquals("Indoor maps finished: 1,632 maps, 221 places can't be mapped", done.label)
        assertEquals("Indoor maps finished", SetupFlow.terse(listOf(done)).single().label)
    }

    @Test fun theStampIsStaleForANewBuilderOnly() {
        assertTrue("a stamp from before (no inputs) re-arms once", ArtHarvest.inputsStale(null, "gen|a3|x|lib:1"))
        assertFalse(ArtHarvest.inputsStale("gen|a3|x|lib:1", "gen|a3|x|lib:1"))
        assertTrue("a new native library", ArtHarvest.inputsStale("gen|a3|x|lib:1", "gen|a3|x|lib:2"))
        assertFalse("a step with no inputs of its own", ArtHarvest.inputsStale(null, null))
    }

    // ------------------------------------------------------------------ D4: the wake lock

    private class Lock : IndoorScheduler.WakeLock {
        var held = false; var acquires = 0; var releases = 0; var lastTimeout = 0L
        val heldDuring = ArrayList<Boolean>()
        override fun acquire(timeoutMs: Long) { held = true; acquires++; lastTimeout = timeoutMs }
        override fun release() { held = false; releases++ }
    }

    @Test fun theCpuIsHeldAwakeOnlyWhileBuildingAndAlwaysLetGo() {
        val root = Files.createTempDirectory("wake").toFile()
        val lock = Lock()
        val logs = ArrayList<String>()
        val gen = Gen(listOf("wmo_a", "wmo_b", "wmo_c"), rule = setOf("wmo_b")) { lock.heldDuring.add(lock.held) }
        assertEquals(IndoorScheduler.Outcome.COMPLETE, run(root, gen, sig = Sig(lock), logs = logs))
        assertEquals("held while each building was made", listOf(true, true, true), lock.heldDuring)
        assertFalse("let go at the end", lock.held)
        assertEquals("a hard limit on each hold", IndoorScheduler.WAKE_LIMIT_MS, lock.lastTimeout)
        assertTrue(IndoorScheduler.WAKE_LIMIT_MS <= 10 * 60_000L)
        assertTrue("acquire and release logged with the reason", logs.any { it.startsWith("wake lock acquired: ") } &&
            logs.any { it.startsWith("wake lock released: the run ended") })
    }

    @Test fun aGameStartingLetsTheLockGoAtOnceAndNeverTakesIt() {
        val root = Files.createTempDirectory("wake2").toFile()
        val lock = Lock()
        val sig = Sig(lock)
        val gen = Gen(listOf("wmo_a", "wmo_b", "wmo_c")) { k -> if (k == "wmo_b") sig.gameUp = true }
        assertEquals(IndoorScheduler.Outcome.RELEASED, run(root, gen, sig = sig))
        assertFalse("let go for the game", lock.held)
        val before = lock.acquires
        // a run while the game is up never takes it
        val gen2 = Gen(listOf("wmo_x"))
        run(Files.createTempDirectory("wake3").toFile(), gen2, sig = sig)
        assertEquals(before, lock.acquires)
        assertFalse(lock.held)
    }

    @Test fun aStopOrAFailureLetsItGoToo() {
        val lock = Lock()
        val sig = Sig(lock)
        val gen = Gen(listOf("wmo_a", "wmo_b")) { k -> if (k == "wmo_b") sig.stopNow = true }
        assertEquals(IndoorScheduler.Outcome.STOPPED, run(Files.createTempDirectory("wake4").toFile(), gen, sig = sig))
        assertFalse(lock.held)
        // a catalogue that throws (an unreadable table): the lock goes with the run
        val lock2 = Lock()
        val bad = object : IndoorGenerator {
            override val algorithm = 1
            override fun catalogue(yield: IndoorGenerator.Yield): List<IndoorBakeStore.Work> = error("Map.db2 unreadable")
            override fun produce(work: IndoorBakeStore.Work, out: File, yield: IndoorGenerator.Yield) {}
            override fun close() {}
        }
        val root = Files.createTempDirectory("wake5").toFile()
        runCatching { IndoorScheduler(IndoorBakeStore(root), root, build, bad, Sig(lock2), File(root, "d")).run() }
        assertTrue(lock2.acquires >= 1)
        assertFalse("let go after the failure", lock2.held)
    }

    @Test fun aPauseLetsItGoAndTheNextBuildingTakesItAgain() {
        val lock = Lock()
        var busyUntil = 0L
        val sig = object : IndoorScheduler.Signals {
            var t = 0L
            override fun position(): IndoorBakeQueue.Position? = null
            override fun busy() = t < busyUntil
            override fun hot() = false
            override fun stop() = false
            override fun now() = t
            override fun sleep(ms: Long) { t += ms }
            override fun wakeLock() = lock
        }
        val heldInPause = ArrayList<Boolean>()
        val gen = Gen(listOf("wmo_a", "wmo_b")) { k -> if (k == "wmo_a") busyUntil = sig.t + 10_000L }
        val root = Files.createTempDirectory("wake6").toFile()
        val s = IndoorScheduler(IndoorBakeStore(root), root, build, gen, sig, File(root, "d"),
            onStatus = { if (it.phase == IndoorScheduler.Phase.PAUSED) heldInPause.add(lock.held) })
        assertEquals(IndoorScheduler.Outcome.COMPLETE, s.run())
        assertEquals("let go while paused", listOf(false), heldInPause.distinct())
        assertTrue("taken again after the pause", lock.acquires >= 2 && s.wakeAcquires >= 2)
        assertFalse(lock.held)
    }

    // ------------------------------------------------------------------ fix 5 (fix 6: IndoorNearMeTest)

    @Test fun theArtIndexAlwaysAsksForTheCapitalsAndAgainWhenRootsGrow() {
        val dir = Files.createTempDirectory("roots").toFile()
        val f = File(dir, IndoorScheduler.ROOTS_FILE)
        val none = WmoMinimap.wantedRoots(f)
        assertEquals(setOf(7962574, 7801267, 7675285, 7964229), none)
        IndoorScheduler.writeRoots(f, listOf("7801267_d0_r3", "123456_d0_u1", "123456_d1_u2"))
        val grown = WmoMinimap.wantedRoots(f)
        assertEquals(none + 123456, grown)
        assertTrue("a grown set is new inputs: the step runs again", WmoMinimap.rootsKey(none) != WmoMinimap.rootsKey(grown))
        assertEquals(WmoMinimap.rootsKey(grown), WmoMinimap.rootsKey(java.util.TreeSet(grown)))
        // the scheduler writes the file at each publication
        val root = Files.createTempDirectory("roots2").toFile()
        run(root, Gen(listOf("777_d0_a", "888_d0_b"), rule = setOf("888_d0_b")))
        assertEquals(setOf(777), IndoorScheduler.readRoots(File(root, "interiors_sched/" + IndoorScheduler.ROOTS_FILE)))
    }

    companion object {
        /** one building's files as the store seals them: its indexes, a level image, a 1 x 1 height grid, one placement */
        fun building(out: File, key: String) {
            File(out, "l0.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            val d = Deflater(); d.setInput(ByteArray(4)); d.finish()
            val buf = ByteArray(64); val n = d.deflate(buf); d.end()
            File(out, "grid.bin").writeBytes(buf.copyOf(n))
            val level = JSONObject().put("img", "l0.png").put("z0", 0).put("z1", 4).put("lxmin", 0).put("lxmax", 10).put("lymin", 0).put("lymax", 10)
            val grid = JSONObject().put("file", "grid.bin").put("cell", 1.0).put("nx", 1).put("ny", 1).put("lx0", 0).put("ly0", 0)
                .put("zmin", 0).put("zscale", 1.0).put("slots", 4)
            File(out, "models.json").writeText(JSONObject().put(key, JSONObject().put("levels", JSONArray().put(level)).put("grid", grid)).toString())
            File(out, "interiors.json").writeText(JSONObject().put("models", JSONObject().put(key, JSONObject())).toString())
            File(out, "places.json").writeText(JSONObject().put("0", JSONArray().put(JSONObject().put("m", key)
                .put("l2w", JSONArray(listOf(0.0, 0.0, 1.0, 0.0))).put("wxmin", 0).put("wxmax", 10).put("wymin", 0).put("wymax", 10))).toString())
        }
    }
}
