package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * LIGHT 8 (LIGHT8_PLAN.md, the user's answers 2026-11-21): the gate (DeviceTier.light8, read live), the counting, the loading pictures,
 * the per-zone cap with its trim to the player's zone and neighbours, the load-time holds with their 90 s limits, the DXVK options and
 * the one-line tip before Play - all on the 8 GB Thors only. The 12 GB and 16 GB Thors' sameness: Light8UnchangedTest.
 */
class Light8Test {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val repo = src.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile
    private fun text(name: String) = File(src, name).readText()

    private fun setTotalMb(mb: Long) =
        DeviceTier::class.java.getDeclaredField("totalMb").apply { isAccessible = true }.setLong(null, mb)

    private val clock0 = LoadHold.clock; private val light0 = LoadHold.lightNow; private val window0 = LoadHold.windowNow
    private val player0 = LoadHold.playerNow; private val say0 = LoadHold.say; private val mem0 = MemWatch.light8Now
    private val onMain0 = RouteWork.mainNow; private val elapsed0 = RouteWork.elapsed
    private val said = ArrayList<String>()
    private var now = 1_000_000L

    @Before fun seams() {
        LoadHold.reset(); said.clear()
        LoadHold.clock = { now }; LoadHold.say = { synchronized(said) { said.add(it) } }
    }

    @After fun reset() {
        setTotalMb(0L); DeviceTier.light8Override = null
        LoadHold.clock = clock0; LoadHold.lightNow = light0; LoadHold.windowNow = window0; LoadHold.playerNow = player0; LoadHold.say = say0
        LoadHold.reset()
        RouteWork.mainNow = onMain0; RouteWork.elapsed = elapsed0
        MemWatch.light8Now = mem0; MemWatch.light8MarkReset()
        MemWatch.onTrim8("light8test") { 0L }; MemWatch.onTrim("light8test-plain") { 0L }
    }

    // ------------------------------------------------------------------ the gate

    @Test fun theGateIsTheEightGbThorsAndTheLiteOnceItsRamIsKnown() {
        assertTrue(DeviceTier.light8(7_191L, false)); assertTrue(DeviceTier.light8(7_700L, false))
        assertFalse(DeviceTier.light8(11_265L, false)); assertFalse(DeviceTier.light8(15_255L, false))
        assertFalse("an unknown RAM", DeviceTier.light8(0L, false))
        assertTrue("the Lite with its RAM known", DeviceTier.light8(7_100L, true))
        assertFalse("the Lite before the RAM is known (eightGb(0, true) is false)", DeviceTier.light8(0L, true))
        // the tests' override, both ways
        assertTrue(DeviceTier.light8(15_255L, false, override = true)); assertTrue(DeviceTier.light8(0L, false, override = true))
        assertFalse(DeviceTier.light8(7_191L, false, override = false))
        // read live: 0 until init, then the Thor's own
        setTotalMb(0L); assertFalse(DeviceTier.light8())
        setTotalMb(7_191L); assertTrue(DeviceTier.light8())
        setTotalMb(11_265L); assertFalse(DeviceTier.light8())
        DeviceTier.light8Override = true; assertTrue(DeviceTier.light8())
        DeviceTier.light8Override = false; setTotalMb(7_191L); assertFalse(DeviceTier.light8())
        // no Settings switch in this build (Q1): nothing in the app sets the override
        val all = src.listFiles()!!.filter { it.name.endsWith(".kt") && it.name != "DeviceTier.kt" }.map { it.readText() }
        assertTrue(all.none { it.contains("light8Override") })
        assertFalse(text("Settings.kt").contains("light8"))
    }

    // ------------------------------------------------------------------ the caps by Thor (7191 / 11265 / 15255 / 0 / Lite)

    @Test fun everyCapByThor() {
        fun l8(mb: Long, lite: Boolean = false) = DeviceTier.light8(mb, lite)
        val rows = listOf(Triple(7_191L, false, true), Triple(11_265L, false, false), Triple(15_255L, false, false), Triple(0L, false, false),
            Triple(7_100L, true, true))
        for ((mb, lite, on) in rows) {
            val g = l8(mb, lite)
            assertEquals("$mb $lite", on, g)
            assertEquals(if (on) 3 else 4, LowMemCuts.sceneMaxHeld(g))
            assertEquals(on, LowMemCuts.sceneForce565(g))
            assertEquals(if (on) 8 else 0, LowMemCuts.zoneBound(g, 3))
            assertEquals(if (on) 10 else 0, LowMemCuts.zoneBound(g, 9))      // never below the keep set + 1
            assertEquals(if (on) 1 else -1, LowMemCuts.climbOthers(g))
            assertEquals(3, LowMemCuts.CLIMB_MAX)
        }
        assertEquals(4, LowMemCuts.SCENE_MAX_HELD)
        assertEquals(listOf(10, 11, 12), LowMemCuts.zoneKeep(10, listOf(11, 0, 12, 10)))
        assertEquals(emptyList<Int>(), LowMemCuts.zoneKeep(0, listOf(11)))
        assertFalse(LowMemCuts.zoneTrims(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(LowMemCuts.zoneTrims(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(LowMemCuts.zoneTrims(MemWatch.LOW_MEMORY))
    }

    @Test fun aZoneCacheBuiltBeforeInitReadsTheBoundLate() {
        var keep = listOf(1, 2, 3)
        setTotalMb(0L)
        val f = LowMemCuts.zoneFiles<String>({ it.length.toLong() }, keepNow = { keep }) { "z$it" }
        assertEquals("RAM unknown: no bound", 0, f.bound())
        setTotalMb(7_191L)
        assertEquals("the same cache, now on Light 8", 8, f.bound())
        keep = (1..9).toList(); assertEquals(10, f.bound())
        keep = listOf(1, 2, 3)
        for (m in 1..12) f.store(m, "z$m")
        assertEquals(8, f.kept())
        assertTrue("the keep set stays", f.keys().containsAll(listOf(1, 2, 3)))
        assertEquals(listOf(1, 2, 3, 8, 9, 10, 11, 12), f.keys().sorted())
        // the relief (a memory warning): every zone but the keep set goes
        val gone = f.trimTo(keep)
        assertEquals(2L * 2 + 3L * 3, gone)                                 // "z8", "z9" 2 bytes each, "z10" .. "z12" 3 bytes each
        assertEquals(listOf(1, 2, 3), f.keys().sorted())
        setTotalMb(11_265L)
        assertEquals("the 12 GB Pro: no bound again", 0, f.bound())
    }

    @Test fun aZoneTheBoundLetGoAndReadAgainIsNoNewData() {
        // MapView's cross-zone plan and SafePath's stamps key off the files' versions: 12 zones read in order twice through a Light 8
        // cache of 8 (keep 1..3) reload the ones the bound let go, but the second pass bumps no version, so the plan settles
        val keep = listOf(1, 2, 3)
        val f = LowMemCuts.zoneFiles<String>({ 1L }, LoadHold.SAFEPATH, light = { true }, keepNow = { keep }) { "z$it" }
        // (a load's result is store: fill's, on the route thread, for a plan that reads the zone synchronously)
        var reloads = 0
        fun pass() { for (m in 1..12) if (f.peek(m) == null) { f.store(m, "z$m"); reloads++ } }
        pass()
        val v1 = f.version
        assertEquals("each first load bumps", 12, v1)
        reloads = 0; pass()
        assertTrue("the bound let zones go: read again", reloads > 0)
        assertEquals("the reloads of the zones the bound let go: no bump", v1, f.version)
        pass(); assertEquals(v1, f.version)
        // a rebuilt file (drop) is new data: its next load bumps
        f.drop(12); f.store(12, "z12b")
        assertEquals(v1 + 1, f.version)
        // the relief (trimTo) and the reload after it: no bump either
        f.trimTo(keep); for (m in 4..8) f.store(m, "z$m")
        assertEquals(v1 + 1, f.version)
        // a zone a caller got null for (the load-time hold here; the UI thread's miss alike) bumps when it arrives, even a reload
        var holding = true
        LoadHold.lightNow = { true }; LoadHold.windowNow = { holding }; LoadHold.playerNow = { 1 }
        val gone = (4..12).first { f.peek(it) == null }
        assertNull(f.get(gone))
        holding = false
        LoadHold.tick()
        val t0 = System.currentTimeMillis()
        while (f.peek(gone) == null && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(5)
        assertEquals(v1 + 2, f.version)
        // off Light 8 (the climb maps' plain bound on the 12 / 16 GB Thors): test build 1516's bump on every load
        val plain = RouteWork.Files<String>(max = 3) { "z$it" }
        for (r in 1..2) for (m in 1..5) if (plain.peek(m) == null) plain.store(m, "z$m")
        assertEquals(10, plain.version)
        // the plan reads each zone's road grid once (held by the plan as its terrain is), never per cell through the bounded cache
        val map = text("MapView.kt")
        // (Light 8 only, and lazily - the first cell that asks, as 1516's; every other Thor keeps 1516's per-cell read)
        assertTrue(map.contains("val l8 = DeviceTier.light8()\n                val zones = ids.mapNotNull { id ->"))
        assertTrue(map.contains("val rg by lazy(LazyThreadSafetyMode.NONE) { if (l8) Roads.of(id) else null }\n" +
            "                    CrossZone.Zone(id, Terrain.of(id), Terrain.buildingsOf(id)) { wx, wy ->\n" +
            "                        if (l8) rg.let { g -> g != null && Roads.isRoad(g, wx, wy) } else Roads.isRoad(id, wx, wy) }"))
        assertFalse("no up-front read of every box zone", map.contains("val rg = Roads.of(id)"))
        assertTrue(text("LowMemCuts.kt").contains("quietReload = { light() }"))
        assertTrue(text("Climb.kt").contains("quietReload = { DeviceTier.light8() }"))
    }

    @Test fun aFailedReloadOffTheUiThreadStillBumpsWhenTheZoneArrives() {
        // a zone the bound let go, read again by an off-UI caller (SafePath's draft, the cross-zone plan) whose load fails (an
        // IOException, an OutOfMemoryError): the caller got null, so the zone's arrival after MISS_RETRY_MS must bump the version
        // (SafePath set tried[map] = stamp and does not retry until a version moves)
        var clock = 50_000L
        RouteWork.mainNow = { false }; RouteWork.elapsed = { clock }
        var fail = false
        val f = LowMemCuts.zoneFiles<String>({ 1L }, light = { true }, keepNow = { listOf(1, 2, 3) }) { m ->
            if (fail) throw OutOfMemoryError("test") else "z$m" }
        for (m in 1..12) assertEquals("z$m", f.get(m))
        val v1 = f.version
        assertEquals(12, v1)
        val gone = (4..12).first { f.peek(it) == null }
        // a plain quiet reload: no bump (the reload-loop fix stays)
        assertEquals("z$gone", f.get(gone)); assertEquals(v1, f.version)
        val gone2 = (4..12).first { f.peek(it) == null }
        fail = true
        assertNull("the load failed", f.get(gone2))
        assertEquals(v1, f.version)
        fail = false
        clock += 5_000
        assertNull("inside the miss window: null again", f.get(gone2))
        clock += RouteWork.MISS_RETRY_MS
        assertEquals("z$gone2", f.get(gone2))
        assertEquals("the caller that got null sees the bump", v1 + 1, f.version)
        assertEquals("z$gone2", f.get(gone2)); assertEquals("in memory: nothing new", v1 + 1, f.version)
        // the next zone the bound let go, failed once and then read: one bump for its arrival
        val gone3 = (4..12).first { f.peek(it) == null }
        fail = true; assertNull(f.get(gone3)); fail = false
        clock += RouteWork.MISS_RETRY_MS
        assertEquals("z$gone3", f.get(gone3)); assertEquals(v1 + 2, f.version)
    }

    @Test fun aRoutePlanOverMoreZonesThanTheBoundSettles() {
        // the cross-zone loop: a plan keyed by the version before it runs, reading 12 zones through a cache of 8 on the route thread
        // (synchronous loads: the plan has the data). Run until the key holds: twice (the first loads), as test build 1516's
        val f = LowMemCuts.zoneFiles<String>({ 1L }, light = { true }, keepNow = { listOf(1, 2, 3) }) { "z$it" }
        fun plan() { for (m in 1..12) if (f.peek(m) == null) f.store(m, "z$m") }
        var runs = 0; var key = -1
        while (key != f.version && runs < 10) { key = f.version; plan(); runs++ }
        assertEquals(2, runs)
    }

    @Test fun theBoundNeverEvictsThePlayersZoneEvenBelowTheKeepSet() {
        // a bound below the keep set and a chain of 3+ zones: with the player first, the player's zone survives
        val keep = listOf(10, 11, 12, 13)
        val c = RouteWork.KeepLru<String>({ 2 }, { keep }, { -1 }, { true })
        for (m in keep) c.put(m, "z$m")
        assertEquals(2, c.size)
        assertTrue("the player's zone", c.peek(10) != null)
        assertTrue("the one just put", c.peek(13) != null)
        // test build 1516's rule (no priority): the least recently used goes, the player's zone first
        val old = RouteWork.KeepLru<String>(2) { keep }
        for (m in keep) old.put(m, "z$m")
        assertNull(old.peek(10))
        // ShippedNets' cache has the priority on Light 8 only
        assertTrue(text("ShippedNets.kt").contains("playerFirst = { DeviceTier.light8() }"))
    }

    @Test fun theClimbMapsKeepTheKeepSetAndOneRecentZone() {
        val keep = listOf(10, 11)
        val c = RouteWork.KeepLru<String>({ 3 }, { keep }, { LowMemCuts.climbOthers(true) }, { true })
        c.put(10, "a"); c.put(20, "b"); c.put(21, "c")
        assertEquals("the keep set and the newest other", listOf(10, 21), c.keys().sorted())
        c.put(11, "d"); c.put(22, "e")
        assertEquals(listOf(10, 11, 22), c.keys().sorted())
        c.put(23, "f")
        assertEquals("never past 3 (test build 1516's bound)", listOf(10, 11, 23), c.keys().sorted())
        val climb = text("Climb.kt")
        assertTrue(climb.contains("max = LowMemCuts.CLIMB_MAX, keep = { if (DeviceTier.light8()) LowMemCuts.zoneKeepNow() else emptyList() }"))
        assertTrue(climb.contains("others = { LowMemCuts.climbOthers(DeviceTier.light8()) }"))
        assertTrue("on the memory line and in the reliefs", climb.contains("MemWatch.source8(\"climb\")") && climb.contains("MemWatch.onTrim8(\"climb\")"))
    }

    @Test fun theLegsScratchIsCountedAndLetGoAtTheReliefs() {
        Climb.Scratch.dropHere()
        val base = Climb.Scratch.heldBytes()
        Climb.Scratch.of(1_000)
        assertEquals("25 B a vertex", base + 25_000L, Climb.Scratch.heldBytes())
        Climb.Scratch.of(500)
        assertEquals("reused, not grown", base + 25_000L, Climb.Scratch.heldBytes())
        assertEquals(base + 25_000L, Climb.letScratchGo())                      // (no danger grids in these tests)
        Climb.Scratch.of(10)                                                    // this thread's next leg starts small
        assertEquals(base + 250L, Climb.Scratch.heldBytes())
        Climb.Scratch.dropHere()
        assertEquals(base, Climb.Scratch.heldBytes())
        val climb = text("Climb.kt")
        assertTrue(climb.contains("MemWatch.source8(\"climbscratch\") { Scratch.heldBytes() + SafePathV2.dangerBytes() }"))
        assertTrue(climb.contains("MemWatch.onTrim8(\"climbscratch\") { level -> if (!LowMemCuts.zoneTrims(level)) 0L else letScratchGo() }"))
        assertTrue(climb.contains("RouteWork.later { Scratch.dropHere() }") && climb.contains("SafePathV2.dropDangers()"))
    }

    @Test fun thePerZoneCachesAreLight8sAndCountedAndTrimmed() {
        val t = text("Terrain.kt"); val r = text("Roads.kt"); val sp = text("SafePath.kt")
        for (k in listOf("val files = LowMemCuts.zoneFiles<Grid>", "val buildings = LowMemCuts.zoneFiles<Grid>", "val heights = LowMemCuts.zoneFiles<Heights>"))
            assertTrue(k, t.contains(k))
        assertTrue(r.contains("val files = LowMemCuts.zoneFiles<Grid>"))
        for (k in listOf("val zones = LowMemCuts.zoneFiles<Zones>", "val waters = LowMemCuts.zoneFiles<Water>", "val drafts = LowMemCuts.zoneFiles<Net>",
                "val nets = LowMemCuts.zoneFiles<Net>")) assertTrue(k, sp.contains(k))
        assertEquals("every SafePath zone file waits for the hold", 4, Regex("zoneFiles<\\w+>\\(\\{[^}]*\\}, LoadHold.SAFEPATH\\)").findAll(sp).count())
        assertFalse("the terrain and roads never wait (not SafePath)", t.contains("LoadHold") || r.contains("LoadHold"))
        for ((f, n) in listOf(t to "terrain", r to "roads", sp to "spzones")) {
            assertTrue(n, f.contains("MemWatch.source8(\"$n\")") && f.contains("MemWatch.onTrim8(\"$n\")"))
        }
        assertTrue(text("LowMemCuts.kt").contains("RouteWork.Files(keep = { if (light()) keepNow() else emptyList() }"))
    }

    // ------------------------------------------------------------------ the counting

    @Test fun theMemoryLinesNewFields() {
        assertEquals(", stk 1, oth 40, sys 12; swp 128", MemWatch.light8Parts(1, 40, 12, 128))
        // the parts add up to the PSS (stack in, swap after the ";": its PSS share is already inside sys, never added twice)
        val parts = "java 31, native 210, gfx 40, code 19" + MemWatch.light8Parts(1, 40, 12, 128)
        assertEquals(31L + 210 + 40 + 19 + 1 + 40 + 12, Regex("(?:java|native|gfx|code|stk|oth|sys) (\\d+)").findAll(parts.substringBefore(";"))
            .sumOf { it.groupValues[1].toLong() })
        assertEquals(", game age 12 s", MemWatch.gameAge(1_000L, 13_500L))
        assertEquals("", MemWatch.gameAge(null, 13_500L))
        // the marker once per launch, and never off Light 8
        MemWatch.light8MarkReset()
        assertNull(MemWatch.light8Mark(false))
        assertEquals("light8 on", MemWatch.light8Mark(true))
        assertNull(MemWatch.light8Mark(true))
        // the short form and the exit stamp carry DS Forever's own swap on Light 8's lines
        val l8 = "11-21 10:00:00 pss 390 MB (java 31, native 210, gfx 40, code 19, stk 1, oth 40, sys 12; swp 128) heap 180/200 MB | game 4120 MB (13), " +
            "free 216 MB, swap 3204/4096 MB, game age 12 s | scenes 8, climb 9, terrain 4 | room home, game up, light8 on | low 180 MB, jheap 31/512 MB"
        assertEquals("10:00:00 pss 390 (j 31 n 210 gx 40 sw 128) game 4120 free 216 low 180 | climb 9 scenes 8 terrain 4", MemWatch.shortLine(l8))
        assertEquals("dsf 1 pss 300 j 20 n 100 gx 10 sw 55", MemWatch.stateSummary(1L, 300, 20, 100, 10, -1, -1, -1, -1, emptyList(), swp = 55))
        val mem = text("MemWatch.kt")
        assertTrue(mem.contains("light8Parts(mb(stat(\"summary.stack\")), mb(stat(\"summary.private-other\")),\n                mb(stat(\"summary.system\")), swp)"))
        // #56 (11-22): the counting is on every Thor now (MemTimedReliefTest), the caches and the marker still Light 8's
        assertTrue(mem.contains("val swp = mb(stat(\"summary.total-swap\"))"))
        assertTrue(mem.contains("sources.toList() + if (l8) sources8.toList() else emptyList()"))
        assertTrue(mem.contains("light8Mark(l8)?.let { add(it) }"))
        assertTrue(mem.contains("val dev = device() + if (l8) gameAge(gameSample?.second, SystemClock.uptimeMillis()) else \"\""))
        // every warning Android sends is logged (test build 1516's trimmed(), at every level)
        val trimmed = mem.substring(mem.indexOf("fun trimmed(level: Int)"), mem.indexOf("fun pressure(level: Int)"))
        assertTrue(trimmed.contains("trimLine(\"trim from Android: \${trimName(level)}\""))
    }

    @Test fun theNewCachesAreRegisteredForTheLineOnly() {
        // (held weakly: MemWatch's sources live as long as the process, so a destroyed view and its icons are never kept, any Thor)
        val iv = text("InteractView.kt")
        assertTrue(iv.contains("val icons = java.lang.ref.WeakReference(iconReady)\n        MemWatch.source8(\"loot\") { icons.get()?.values?.sumOf { it.byteCount.toLong() } ?: 0L }"))
        assertFalse("no strong capture of the view", iv.contains("MemWatch.source8(\"loot\") { iconReady"))
        assertTrue("no new bound on the loot icons", text("InteractView.kt").contains("if (iconReady.size > 300) { iconReady.clear(); iconWarmed.clear() }"))
        assertTrue(text("BottomPictures.kt").contains("MemWatch.source8(\"bottompics\")"))
        assertTrue(text("GeneralPictures.kt").contains("MemWatch.source8(\"generalpics\") { bytes() }"))
        assertTrue(text("TilePictures.kt").contains("MemWatch.source8(\"mediaart\") { bytes() }"))
        assertTrue(text("ThemeGallery.kt").contains("MemWatch.source8(\"themegallery\")"))
    }

    private fun letGo(level: Int): List<Pair<String, Long>> {
        @Suppress("UNCHECKED_CAST")
        return MemWatch::class.java.getDeclaredMethod("letGo", Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(MemWatch, level) as List<Pair<String, Long>>
    }

    @Test fun light8sTrimsRunOnlyOnLight8() {
        var asked = 0
        MemWatch.onTrim8("light8test") { asked++; 5L }
        MemWatch.light8Now = { true }
        assertTrue(letGo(MemWatch.RELIEF_LEVEL).any { it == "light8test" to 5L })
        assertEquals(1, asked)
        MemWatch.light8Now = { false }
        assertFalse(letGo(MemWatch.RELIEF_LEVEL).any { it.first == "light8test" })
        assertEquals(1, asked)
    }

    @Test fun theReportHeaderSaysItIsOn() {
        assertEquals("; 8 GB light: on", StartupDiag.light8Header(true))
        assertEquals("", StartupDiag.light8Header(false))
        val d = text("StartupDiag.kt")
        assertTrue(d.contains("light8Header(runCatching { DeviceTier.light8() }.getOrDefault(false)),"))
        // "RAM: X MB free of T MB" not changed by a byte
        assertTrue(d.contains("line { \"RAM: \${mb(mi.availMem)} free of \${mb(mi.totalMem)}\${if (mi.lowMemory) \" (LOW MEMORY now)\" else \"\"}, low at \${mb(mi.threshold)}\" },"))
    }

    // ------------------------------------------------------------------ the loading pictures

    @Test fun theLoadingPicturesAreThreeAtLowerDepthOnLight8() {
        assertEquals(listOf(1), LowMemCuts.overCap(listOf(1, 2, 3, 4), LowMemCuts.sceneMaxHeld(true)))
        assertEquals(emptyList<Int>(), LowMemCuts.overCap(listOf(1, 2, 3, 4), LowMemCuts.sceneMaxHeld(false)))
        val scene = text("Scene.kt")
        assertTrue(scene.contains("LowMemCuts.overCap(pics.keys.toList(), LowMemCuts.sceneMaxHeld(DeviceTier.light8()))"))
        assertTrue(scene.contains("if (!LowMemCuts.sceneForce565(DeviceTier.light8()) || b.config == Bitmap.Config.RGB_565) return b"))
        assertTrue(scene.contains("b.copy(Bitmap.Config.RGB_565, false)"))
        assertTrue("a guard only: the shipped pictures already decode as RGB_565", scene.contains("A GUARD ONLY"))
    }

    // ------------------------------------------------------------------ the load-time holds

    @Test fun theWindowsLoginZoneLostOffAndTheNinetySecondLimit() {
        val s = 1_000_000L
        fun d(start: Long, world: Long, load: Long, on: Boolean, end: Long, at: Long) = LoadHold.deferNow(start, world, load, on, end, at)
        // the login load keys off the game's start (Play): held before the world, and until 30 s after it
        assertTrue(d(s, 0, 0, false, 0, s))
        assertTrue(d(s, 0, 0, false, 0, s + 40_000))
        assertTrue(d(s, s + 40_000, 0, false, 0, s + 69_000))
        assertFalse(d(s, s + 40_000, 0, false, 0, s + 70_000))
        // the hard limit: a world that never comes
        assertTrue(d(s, 0, 0, false, 0, s + 89_999)); assertFalse(d(s, 0, 0, false, 0, s + 90_000))
        // a slow login: the world entry has its own window, 30 s from the world (the kills 7 - 36 s after it), whatever Play's 90 s
        assertTrue("a slow login: held in the world's first 30 s", d(s, s + 80_000, 0, false, 0, s + 95_000))
        assertTrue("the world at 120 s: held at 130 s", d(s, s + 120_000, 0, false, 0, s + 130_000))
        assertFalse(d(s, s + 120_000, 0, false, 0, s + 150_000))
        assertFalse("before a late world: Play's own 90 s only", d(s, s + 120_000, 0, false, 0, s + 100_000))
        assertTrue("still before the world, inside Play's 90 s", d(s, s + 120_000, 0, false, 0, s + 60_000))
        // a zone's loading screen: from its start until 5 s after its end
        val z = s + 600_000
        assertTrue(d(s, s + 40_000, z, true, 0, z + 10_000))
        assertTrue(d(s, s + 40_000, z, false, z + 12_000, z + 16_999))
        assertFalse(d(s, s + 40_000, z, false, z + 12_000, z + 17_000))
        // a lost "off": at most 90 s
        assertTrue(d(s, s + 40_000, z, true, 0, z + 89_999)); assertFalse(d(s, s + 40_000, z, true, 0, z + 90_000))
        // no game (or it ended): nothing - a loading screen up when it died included (GameLife passes 0 for both)
        assertFalse(d(0, 0, 0, false, 0, s))
        assertFalse("the world window needs a game", d(0, s + 40_000, 0, false, 0, s + 50_000))
        assertFalse(d(0, s + 40_000, 0, true, 0, z + 10_000))
        val life = text("GameLife.kt")
        assertTrue(life.contains("LoadHold.deferNow(if (ended) 0L else startedAt, if (worldSeen) worldAt else 0L, if (ended) 0L else lastLoadAt, loadingAt != 0L,\n            loadEndAt, now)"))
        assertTrue(life.contains("loadingAt = now; lastLoadAt = now; loads++\n            runCatching { LoadHold.windowStarted() }"))
        for (k in listOf("leftAt = 0L; worldAt = now; runCatching { LoadHold.windowStarted() }", "worldSeen = true\n        runCatching { LoadHold.windowStarted() }",
                "leftAt = 0L\n        runCatching { LoadHold.windowStarted() }")) assertTrue(k, life.contains(k))
    }

    @Test fun theGameEndingMidLoadEndsTheHoldsAtOnce() {
        // the real GameLife window: Play, the world, a loading screen, then the game dies with it up (its "over" never comes)
        val t0 = 5_000_000L
        GameLife.started(t0)
        GameLife.inWorld(t0 + 40_000)
        assertTrue(GameLife.holdWindow(t0 + 60_000))
        assertFalse(GameLife.holdWindow(t0 + 75_000))
        GameLife.loadingScreen(true, t0 + 600_000)
        assertTrue(GameLife.holdWindow(t0 + 610_000))
        GameLife.ended(9, t0 + 620_000)
        assertFalse("the game gone: nothing held", GameLife.holdWindow(t0 + 620_001))
        assertFalse(GameLife.holdWindow(t0 + 650_000))
        GameLife.started(t0 + 700_000)
        assertTrue("a new game: its own window", GameLife.holdWindow(t0 + 700_001))
        GameLife.ended(0, t0 + 701_000)
    }

    @Test fun aHeldJobRunsWhenTheWindowEndsAndIsLogged() {
        var holding = true
        LoadHold.lightNow = { true }; LoadHold.windowNow = { holding }
        var ran = 0
        assertTrue(LoadHold.hold(LoadHold.DANGER) { ran++ })
        assertTrue("asked again: still held, one entry", LoadHold.hold(LoadHold.DANGER) { ran += 10 })
        assertEquals(listOf("light8: held danger re-read (the game is loading)"), said)
        now += 20_000
        assertEquals(emptyList<String>(), LoadHold.tick())
        holding = false
        now += 14_000
        assertEquals(listOf(LoadHold.DANGER), LoadHold.tick())
        assertEquals("the newest of the key ran, once", 10, ran)
        assertEquals("light8: ran danger re-read after 34 s held", said.last())
        assertFalse("the window over: runs at once", LoadHold.hold(LoadHold.DANGER) { ran++ })
    }

    @Test fun aJobNeverWaitsPastNinetySeconds() {
        LoadHold.lightNow = { true }; LoadHold.windowNow = { true }        // a window that never ends (a lost event)
        var ran = 0
        assertTrue(LoadHold.hold(LoadHold.SAFEPATH, "a") { ran++ })
        assertTrue(LoadHold.hold(LoadHold.SAFEPATH, "b") { ran++ })
        now += 89_000; assertEquals(emptyList<String>(), LoadHold.tick())
        now += 1_000
        assertEquals(listOf(LoadHold.SAFEPATH), LoadHold.tick())
        assertEquals("both parts", 2, ran)
        assertEquals("light8: ran other-zone SafePath after 90 s held (90 s limit)", said.last())
        assertFalse("not held again in the same window", LoadHold.hold(LoadHold.SAFEPATH, "a") { ran++ })
    }

    @Test fun aJobHeldSincePlayKeepsItsNinetySecondsThroughTheWorldWindow() {
        // the plan's cap ("Each wait has a hard limit of 90 s"): Play at 0, held at 5 s, the world at 80 s (its own window to 110 s).
        // The job's 90 s count from its first hold and the world entry does not re-base them: it runs at 95 s, inside the world's
        // window, and is not held again in it; a job first asked after the world entry waits for the window's end
        val play = now
        val world = play + 80_000
        LoadHold.lightNow = { true }
        LoadHold.windowNow = { t -> LoadHold.deferNow(play, if (t >= world) world else 0L, 0L, false, 0L, t) }
        now = play + 5_000
        var ran = 0
        assertTrue(LoadHold.hold(LoadHold.DANGER) { ran++ })
        now = world; LoadHold.windowStarted(); assertEquals(emptyList<String>(), LoadHold.tick())
        now = play + 94_999; assertEquals(emptyList<String>(), LoadHold.tick())
        now = play + 95_000
        assertEquals(listOf(LoadHold.DANGER), LoadHold.tick())
        assertEquals(1, ran)
        assertEquals("light8: ran danger re-read after 90 s held (90 s limit)", said.last())
        assertFalse("not held again in the world's window", LoadHold.hold(LoadHold.DANGER) {})
        // a job first asked after the world: held until the window's end (world + 30 s)
        assertTrue(LoadHold.hold(LoadHold.SAFEPATH) {})
        now = world + 29_999; assertEquals(emptyList<String>(), LoadHold.tick())
        now = world + 30_000; assertEquals(listOf(LoadHold.SAFEPATH), LoadHold.tick())
        assertTrue(text("LoadHold.kt").contains("That cap counts from\n * the job's FIRST hold and a new window never re-bases it"))
    }

    @Test fun aJobItsLimitLetGoIsHeldAgainInTheNextWindow() {
        // Play at 0, held at 5 s, the world at 70 s, a loading screen at 85 s that keeps a window on past 95 s: the job's own 90 s
        // let it go while a window still ran; nothing else pending (the ticker would stop)
        var holding = true
        LoadHold.lightNow = { true }; LoadHold.windowNow = { holding }
        assertTrue(LoadHold.hold(LoadHold.DANGER) {})
        now += 90_000
        assertEquals(listOf(LoadHold.DANGER), LoadHold.tick())
        assertEquals(listOf(LoadHold.DANGER), LoadHold.overLimitNow())
        assertTrue(LoadHold.heldJobs().isEmpty())
        // the window ends; the next hold is asked in a later window with no tick in between: held again
        holding = false
        assertFalse(LoadHold.hold(LoadHold.DANGER) {})
        assertTrue("a hold asked outside a window clears it", LoadHold.overLimitNow().isEmpty())
        holding = true
        assertTrue("the next loading screen holds it again", LoadHold.hold(LoadHold.DANGER) {})
        // the same with no ask between the windows: the next window's start clears it
        LoadHold.reset()
        assertTrue(LoadHold.hold(LoadHold.SAFEPATH) {})
        now += 90_000; LoadHold.tick()
        assertFalse(LoadHold.hold(LoadHold.SAFEPATH) {})
        LoadHold.windowStarted()
        assertTrue("a new window (GameLife: Play, the world, a loading screen)", LoadHold.hold(LoadHold.SAFEPATH) {})
        // and the ticker keeps going while one waits for the window's end (its tick then clears it)
        val hold = text("LoadHold.kt")
        assertTrue(hold.contains("(pending.isNotEmpty() || overLimit.isNotEmpty()).also { if (!it) armed = false }"))
        assertTrue(hold.contains("if (!holdingNow(now)) { synchronized(this) { overLimit.clear() }; return false }"))
    }

    @Test fun nothingIsHeldOffLight8() {
        LoadHold.windowNow = { true }
        LoadHold.lightNow = { false }
        assertFalse(LoadHold.hold(LoadHold.DANGER) {})
        assertTrue(said.isEmpty())
    }

    @Test fun anotherZonesFileWaitsAndLoadsWhenTheHoldEnds() {
        var holding = true
        LoadHold.lightNow = { true }; LoadHold.windowNow = { holding }; LoadHold.playerNow = { 10 }
        assertTrue(LoadHold.otherZone(11)); assertFalse(LoadHold.otherZone(10))
        LoadHold.playerNow = { 0 }; assertFalse("the player's zone not known: none held", LoadHold.otherZone(11))
        LoadHold.playerNow = { 10 }
        val f = LowMemCuts.zoneFiles<String>({ 1L }, LoadHold.SAFEPATH, light = { true }, keepNow = { listOf(10) }) { "z$it" }
        assertNull(f.get(11))
        assertEquals(listOf(11), f.heldNow())
        assertEquals(listOf(LoadHold.SAFEPATH), LoadHold.heldJobs())
        holding = false
        assertEquals(listOf(LoadHold.SAFEPATH), LoadHold.tick())
        val t0 = System.currentTimeMillis()
        while (f.peek(11) == null && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10)
        assertEquals("loaded on the route thread when the hold ended", "z11", f.peek(11))
        assertTrue(f.heldNow().isEmpty())
    }

    @Test fun aDraftWaitsForTheHoldAndReadsItsNeighboursPastIt() {
        // SafePath's draft: held while the game loads (not tried, so drafted after); when it runs its neighbours' drafts are read
        // past the hold (a saved draft is never made without them)
        val sp = text("SafePath.kt")
        val refresh = sp.substring(sp.indexOf("fun refresh(map: Int, cont: Int"), sp.indexOf("fun districtFor("))
        // (another zone's draft only: the player's own zone is never held - LIGHT8_PLAN.md change 4)
        assertTrue(refresh.indexOf("if (LoadHold.otherZone(map) && LoadHold.hold(LoadHold.SAFEPATH, \"draft\") {}) return") in 0 until refresh.indexOf("tried[map] = stamp"))
        assertFalse(refresh.contains("        if (LoadHold.hold(LoadHold.SAFEPATH, \"draft\")"))
        assertTrue(refresh.contains("mapNotNull { m -> drafts.get(m, hold = false) }"))
        assertTrue(refresh.contains("val old = drafts.get(map, hold = false)"))
        // the cache's unheld read: a held zone is loaded at once with hold = false
        LoadHold.lightNow = { true }; LoadHold.windowNow = { true }; LoadHold.playerNow = { 10 }
        val f = LowMemCuts.zoneFiles<String>({ 1L }, LoadHold.SAFEPATH, light = { true }, keepNow = { listOf(10) }) { "z$it" }
        assertNull(f.get(11)); assertEquals(listOf(11), f.heldNow())
        runCatching { f.get(12, hold = false) }                                // (past the hold it loads: no main looper off-device)
        assertFalse("never waits", f.heldNow().contains(12))
        assertEquals(listOf(11), f.heldNow())
    }

    @Test fun theHoldsCallSites() {
        val danger = text("DangerStore.kt")
        val check = danger.substring(danger.indexOf("fun check(ctx: Context"), danger.indexOf("checkedAt = now"))
        assertTrue(check.contains("if (LoadHold.hold(LoadHold.DANGER) { check(ctx, onReady) }) return"))
        assertTrue("REREAD_AFTER_MS kept", danger.contains("const val REREAD_AFTER_MS = 60_000L") && danger.contains("const val KEEP_MAPS = 8"))
        val nets = text("ShippedNets.kt")
        assertTrue(nets.contains("holdJob = LoadHold.SAFEPATH, holdIf = { m -> LoadHold.otherZone(m).also { if (it) ShippedCrossStore.holdBump() } }"))
        val cross = text("ShippedCrossStore.kt")
        assertTrue(cross.contains("if (holdBump()) return null"))
        assertTrue(cross.contains("fun holdBump(): Boolean = LoadHold.hold(LoadHold.SAFEPATH, \"cross\") { version++ }"))
        assertFalse("the minimap is never held", text("MinimapTiles.kt").contains("LoadHold"))
    }

    // ------------------------------------------------------------------ the tip before Play

    private val ready = SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true)

    @Test fun theOneLineTipBeforePlayOnLight8() {
        assertEquals(SetupFlow.PLAY_TIP_8GB, SetupFlow.screen(ready.copy(light8Tip = true)).line)
        assertEquals("", SetupFlow.screen(ready).line)
        assertEquals("a pop-up up: its own words only", "", SetupFlow.screen(ready.copy(light8Tip = true, polestarAsk = true)).line)
        assertEquals("Play not ready: the step's line", "Checking the game art",
            SetupFlow.screen(ready.copy(light8Tip = true, art = SetupFlow.Art.IDLE)).line)
        assertTrue(text("SetupBridge.kt").contains("light8Tip = runCatching { DeviceTier.light8() }.getOrDefault(false)"))
        assertFalse("one line", SetupFlow.PLAY_TIP_8GB.contains('\n'))
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n").map { File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val b = File(dir, "$l.json").readBytes()
            val lf = b.count { it == '\n'.code.toByte() }
            assertEquals("$l.json stays CRLF", lf, Regex("\r\n").findAll(String(b, Charsets.UTF_8)).count())
            Tr.load(l, String(b, Charsets.UTF_8))
        }
        assertEquals("Listo. Cierra otras apps para jugar con más fluidez", Tr.t(SetupFlow.PLAY_TIP_8GB, "es"))
        assertEquals("Prêt. Fermez les autres applis pour un jeu plus fluide", Tr.t(SetupFlow.PLAY_TIP_8GB, "fr"))
    }

    // ------------------------------------------------------------------ the rules kept

    @Test fun noSystemSettingsNoGameSettingsNoPermissions() {
        val mine = listOf("LoadHold.kt", "LowMemCuts.kt", "DeviceTier.kt").joinToString("\n") { text(it) }
        for (bad in listOf("swappiness", "zram", "lmkd", "oom_score", "SetCVar", "SetBinding", "Config.wtf", "killBackgroundProcesses"))
            assertFalse(bad, mine.contains(bad))
        assertFalse(File(repo, "app/src/main/AndroidManifest.xml").readText().contains("KILL_BACKGROUND_PROCESSES"))
    }
}
