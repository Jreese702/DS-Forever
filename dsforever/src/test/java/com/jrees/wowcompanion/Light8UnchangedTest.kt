package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LIGHT 8 LEAVES THE 12 GB AND 16 GB THORS AS TEST BUILD 1516 (the user, 2026-11-21: "12 GB and 16 GB Thors must behave exactly as
 * 1516"). With the RAM Android counts on the Pro (11265 MB) and the Max (15255 MB) - and an unknown RAM - every Light 8 rule gives back
 * test build 1516's value: the caches unbounded or at their old bound, the old eviction order, no hold, the old DXVK environment, no
 * header part, no tip, no new memory-line part and no new trim.
 */
class Light8UnchangedTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private fun setTotalMb(mb: Long) =
        DeviceTier::class.java.getDeclaredField("totalMb").apply { isAccessible = true }.setLong(null, mb)
    private val window0 = LoadHold.windowNow; private val say0 = LoadHold.say; private val mem0 = MemWatch.light8Now

    @After fun reset() {
        setTotalMb(0L); LoadHold.windowNow = window0; LoadHold.say = say0; LoadHold.reset(); MemWatch.light8Now = mem0
        MemWatch.onTrim8("unchanged8") { 0L }
    }

    private val tiers = listOf(11_265L, 15_255L, 0L)

    @Test fun theGateIsOffAndEveryCapIs1516s() {
        for (mb in tiers) {
            setTotalMb(mb)
            assertFalse("$mb", DeviceTier.light8())
            val g = DeviceTier.light8()
            assertEquals(LowMemCuts.SCENE_MAX_HELD, LowMemCuts.sceneMaxHeld(g))
            assertFalse(LowMemCuts.sceneForce565(g))
            assertEquals(0, LowMemCuts.zoneBound(g, 5))
            assertEquals(-1, LowMemCuts.climbOthers(g))
            assertEquals("", StartupDiag.light8Header(g))
        }
    }

    @Test fun thePerZoneCachesStayUnboundedWithNoKeepSet() {
        for (mb in tiers) {
            setTotalMb(mb)
            val f = LowMemCuts.zoneFiles<String>({ 1L }, LoadHold.SAFEPATH, keepNow = { listOf(1, 2) }) { "z$it" }
            for (m in 1..40) f.store(m, "z$m")
            assertEquals("$mb: test build 1516's unbounded cache", 40, f.kept())
            assertEquals(0, f.bound())
        }
    }

    @Test fun theEvictionOrderIs1516sWithTheDefaults() {
        // the same puts through the old constructor (test build 1516's) and through the cache's Light 8 parameters off: the same keys
        val keep = listOf(10, 11, 12)
        val old = RouteWork.KeepLru<String>(2) { keep }
        val off = RouteWork.KeepLru<String>({ 2 }, { keep }, { LowMemCuts.climbOthers(false) }, { false })
        for (m in listOf(10, 20, 11, 21, 12, 22, 10)) { old.put(m, "z$m"); off.put(m, "z$m") }
        assertEquals(old.keys(), off.keys())
        // the climb maps: 3 least recently used, as before
        setTotalMb(11_265L)
        val climb = RouteWork.Files<String>(max = LowMemCuts.CLIMB_MAX, keep = { if (DeviceTier.light8()) listOf(1) else emptyList() },
            others = { LowMemCuts.climbOthers(DeviceTier.light8()) }, playerFirst = { DeviceTier.light8() }) { "z$it" }
        for (m in 1..6) climb.store(m, "z$m")
        assertEquals(listOf(4, 5, 6), climb.keys())
    }

    @Test fun nothingIsHeldWhileTheGameLoads() {
        val said = ArrayList<String>()
        LoadHold.windowNow = { true }; LoadHold.say = { said.add(it) }
        for (mb in tiers) {
            setTotalMb(mb)
            assertFalse(LoadHold.holdingNow())
            assertFalse(LoadHold.hold(LoadHold.DANGER) {})
            assertFalse(LoadHold.hold(LoadHold.SAFEPATH, "cross") {})
        }
        assertTrue(said.isEmpty())
        assertTrue(LoadHold.heldJobs().isEmpty())
    }

    @Test fun theDxvkEnvironmentIs1516s() {
        for (mb in tiers) {
            val cap = GameVideoMemory.capMb(mb)
            for (on in listOf(true, false)) assertEquals("$mb $on", GameVideoMemory.env(cap, on), GameVideoMemory.env(cap, on, light8 = false))
        }
        assertEquals("DXVK_CONFIG=dxgi.maxDeviceMemory=3072;dxgi.maxSharedMemory=3072", GameVideoMemory.env(3072, true, false))
        assertEquals("DXVK_CONFIG=dxgi.maxDeviceMemory=4096;dxgi.maxSharedMemory=4096", GameVideoMemory.env(4096, true, false))
        assertEquals("", GameVideoMemory.env(3072, false, false))
        val screen = listOf("../app/src/main/java/app/gamenative/ui/screen/wow", "app/src/main/java/app/gamenative/ui/screen/wow")
            .map { File(it) }.first { it.isDirectory }.let { File(it, "WoWForeverScreen.kt").readText() }
        val choice = screen.substring(screen.indexOf("private fun videoMemoryChoice("), screen.indexOf("private fun containerConfig("))
        assertTrue(choice.contains("val env = G.env(cap, on, light8)"))
        assertTrue("the extra line only on Light 8", choice.contains("if (light8) {\n        val l8 = G.dxcfgLine(env)"))
    }

    @Test fun noTipNoNewLineNoNewTrim() {
        val ready = SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.READY, loginSaved = true)
        assertEquals("", SetupFlow.screen(ready.copy(light8Tip = false)).line)
        // 1516's memory line in short and its exit stamp: unchanged (no swp)
        val sample = "11-18 18:50:13 pss 390 MB (java 31, native 210, gfx 40, code 19) heap 180/200 MB | game 4120 MB (13), free 216 MB, " +
            "swap 3204/4096 MB | kit 6, scenes 41 | room home, game up | low 180 MB, jheap 31/512 MB, gpu 880 MB"
        assertEquals("18:50:13 pss 390 (j 31 n 210 gx 40) game 4120 free 216 low 180 gpu 880 | scenes 41 kit 6", MemWatch.shortLine(sample))
        assertEquals("dsf 1 pss 300 j 20 n 100 gx 10", MemWatch.stateSummary(1L, 300, 20, 100, 10, -1, -1, -1, -1, emptyList()))
        assertNull(MemWatch.light8Mark(false))
        // Light 8's trims never run here
        var asked = 0
        MemWatch.onTrim8("unchanged8") { asked++; 1L }
        for (mb in tiers) {
            setTotalMb(mb); MemWatch.light8Now = mem0
            @Suppress("UNCHECKED_CAST")
            val freed = MemWatch::class.java.getDeclaredMethod("letGo", Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(MemWatch, MemWatch.RELIEF_LEVEL) as List<Pair<String, Long>>
            assertFalse(freed.any { it.first == "unchanged8" })
        }
        assertEquals(0, asked)
        // the line's new caches only behind Light 8; its counting fields (stk / oth / sys; swp) on every Thor since #56 11-22
        // (counting only: MemTimedReliefTest)
        val mem = File(src, "MemWatch.kt").readText()
        assertTrue(mem.contains("sources.toList() + if (l8) sources8.toList() else emptyList()"))
        assertTrue(mem.contains("\"code \${mb(stat(\"summary.code\"))}\" + light8Parts("))
    }

    @Test fun theZoneCachesBumpAsBeforeAndTheWindowsNeverHold() {
        for (mb in tiers) {
            setTotalMb(mb)
            // the per-zone caches: every load bumps (no bound, so no reload but after a trim or a drop; both bump as 1516)
            val f = LowMemCuts.zoneFiles<String>({ 1L }, keepNow = { listOf(1, 2) }) { "z$it" }
            for (m in 1..20) f.store(m, "z$m")
            f.trim(); for (m in 1..20) f.store(m, "z$m")
            assertEquals("$mb", 40, f.version)
            // the climb maps (3 on every Thor): a reload bumps, as before
            val climb = RouteWork.Files<String>(max = LowMemCuts.CLIMB_MAX, quietReload = { DeviceTier.light8() }) { "z$it" }
            for (r in 1..2) for (m in 1..5) if (climb.peek(m) == null) climb.store(m, "z$m")
            assertEquals("$mb", 10, climb.version)
            // a new window, a hold asked: nothing held, nothing logged
            LoadHold.windowStarted()
            assertFalse(LoadHold.hold(LoadHold.SAFEPATH, "draft") {})
        }
        assertTrue(LoadHold.heldJobs().isEmpty())
    }
}
