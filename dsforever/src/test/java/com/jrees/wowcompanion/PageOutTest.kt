package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compress idle memory (PageOut, the user's picks 2026-11-23: the Wine helpers' and DS Forever's own idle memory into zram; the game
 * client's own cold-only mode is GamePageOutTest's), off-device: which process may be asked (the same cases as tools/pageout/test_pageout.c's for the preload's own check), when
 * memory is short, the rate limit and its backoff, the answers and the mem.log / report lines, the default by model, the launch.
 * The native half: wsl -e bash tools/pageout/test.sh.
 */
class PageOutTest {
    private val games = PageOut.gameNames()
    private fun who(comm: String?, vararg a: String) = PageOut.who(a.toList(), comm, games)

    @Test fun theGameIsNeverAHelper() {
        assertEquals(listOf("Wow-arm64.exe", "WowB-ARM64.exe"), games)
        val W = PageOut.Who.GAME
        assertEquals(W, who("Wow-arm64.exe", "G:\\_forever_\\Wow-arm64.exe", "-launcherlogin", "-uid", "wowf"))
        assertEquals(W, who("WowB-ARM64.exe", "G:\\_classic_beta_\\WowB-ARM64.exe"))
        assertEquals(W, who("WowClassic.exe", "C:\\Program Files\\World of Warcraft\\_classic_\\WowClassic.exe"))
        assertEquals(W, who("wine", "/x/wine", "G:\\_forever_\\Wow-arm64.exe"))
        assertEquals("the comm wins", W, who("Wow-arm64.exe", "C:\\windows\\system32\\services.exe"))
        assertEquals("explorer starting the game", W, who("explorer.exe", "/x/wine", "explorer", "/desktop=shell,1920x1080", "G:\\_forever_\\Wow-arm64.exe"))
        assertEquals(W, who("explorer.exe", "C:\\windows\\explorer.exe", "/desktop=shell,1920x1080", "\"G:\\_forever_\\Wow-arm64.exe\" -d3d11"))
        assertEquals("our sign-in helper", W, who("explorer.exe", "C:\\windows\\explorer.exe", "/desktop=shell,1920x1080", "wow-session-bridge.exe", "--port", "1"))
        assertEquals(W, who("start.exe", "C:\\windows\\system32\\start.exe", "/unix", "/x/World of Warcraft/game.exe"))
        assertEquals(W, who("Battle.net.exe", "C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"))
        assertEquals(W, who("Agent.exe", "C:\\ProgramData\\Battle.net\\Agent\\Agent.exe"))
        assertEquals(W, who("BlizzardError.e", "C:\\x\\BlizzardError.exe"))
        assertEquals(W, PageOut.who(listOf("C:\\games\\game.exe"), "game.exe", listOf("game.exe")))
    }

    @Test fun onlyWinesOwnHelpers() {
        val H = PageOut.Who.HELPER; val N = PageOut.Who.NOT_HELPER
        assertEquals(H, who("services.exe\n", "C:\\windows\\system32\\services.exe"))
        assertEquals(H, who("winedevice.exe", "C:\\windows\\system32\\winedevice.exe"))
        assertEquals(H, who("plugplay.exe", "C:\\windows\\system32\\plugplay.exe"))
        assertEquals(H, who("svchost.exe", "C:\\windows\\system32\\svchost.exe", "-k", "LocalServiceNetworkRestricted"))
        assertEquals(H, who("rpcss.exe", "C:\\windows\\system32\\rpcss.exe"))
        assertEquals(H, who("conhost.exe", "C:\\WINDOWS\\SYSTEM32\\CONHOST.EXE", "--unix"))
        assertEquals(H, who("wine", "/data/user/0/app/files/imagefs/opt/wine/bin/wine", "C:\\windows\\system32\\services.exe"))
        assertEquals(H, who(null, "wine64-preloader", "wine64", "plugplay.exe"))
        assertEquals(H, who("explorer.exe", "C:\\windows\\explorer.exe", "/desktop"))
        // unsure = nothing
        assertEquals(N, who("wine", "/x/wine"))
        assertEquals("not wineserver (not idle)", N, who("wineserver", "/x/bin/wineserver"))
        assertEquals(N, who("lsass.exe", "C:\\windows\\system32\\lsass.exe"))
        assertEquals(N, who("rundll32.exe", "C:\\windows\\system32\\rundll32.exe", "x.dll,Entry"))
        assertEquals(N, who("game.exe", "C:\\games\\game.exe"))
        assertEquals(N, who(null, "services.exe.bak"))
        assertEquals(N, PageOut.who(emptyList(), "", games))
        assertEquals(listOf("C:\\windows\\system32\\svchost.exe", "-k", "x"), PageOut.args("C:\\windows\\system32\\svchost.exe\u0000-k\u0000x\u0000"))
    }

    /** the preload's own check (dsf_pageout.h) holds the same lists: the C and Kotlin rules cannot drift apart unnoticed */
    @Test fun theNativeCheckHasTheSameLists() {
        val h = listOf(File("../dsforever/src/main/jni/dsf/dsf_pageout.h"), File("dsforever/src/main/jni/dsf/dsf_pageout.h"), File("src/main/jni/dsf/dsf_pageout.h"))
            .first { it.isFile }.readText()
        val list = Regex("PO_HELPERS\\[\\] = \\{([^}]*)\\}").find(h)!!.groupValues[1]
        assertEquals(PageOut.HELPERS, Regex("\"([a-z]+)\"").findAll(list).map { it.groupValues[1] }.toSet())
        for (w in listOf("\"wow\"", "\"blizzard\"", "\"battle.net\"", "\"world of warcraft\"", "\"\\\\wow\"", "\"/wow\"")) assertTrue(w, w in h)
        assertTrue("never more than 32 MiB a request", "#define PO_CAP_MAX ((uint64_t) 32 << 20)" in h)
        assertEquals(32 * 1024, PageOut.CAP_KIB)
    }

    @Test fun onlyNearTheEdge() {
        assertEquals(719L, PageOut.floorMb(7191))       // the 8 GB Thors
        assertEquals(1126L, PageOut.floorMb(11265))     // 12 GB
        assertEquals(1525L, PageOut.floorMb(15255))     // 16 GB
        assertEquals(600L, PageOut.floorMb(3000)); assertEquals(1600L, PageOut.floorMb(24000))
        val gb8 = 7191L; val swap = 2_000_000L
        assertEquals("free 600 MB < 719", PageOut.pressure(600 * 1024, 2_000_000, swap, gb8, -1))
        assertNull("plenty free", PageOut.pressure(1809 * 1024, 2_000_000, swap, gb8, -1))
        assertNull("no room in swap", PageOut.pressure(300 * 1024, 2_000_000, 100 * 1024, gb8, -1))
        assertNull("swap unknown", PageOut.pressure(300 * 1024, 2_000_000, -1, gb8, -1))
        assertNull("RAM unknown", PageOut.pressure(300 * 1024, 2_000_000, swap, 0, -1))
        assertEquals("file cache 200 MB, free 900 MB", PageOut.pressure(900 * 1024, 200 * 1024, swap, gb8, -1))
        assertNull("file cache thin but plenty free", PageOut.pressure(1200 * 1024, 200 * 1024, swap, gb8, -1))
        assertEquals("Android's warning, free 1200 MB", PageOut.pressure(1200 * 1024, 2_000_000, swap, gb8, 3_000))
        assertNull("an old warning", PageOut.pressure(1200 * 1024, 2_000_000, swap, gb8, 30_000))
        assertNull("16 GB with the reports' lowest (3904 MB)", PageOut.pressure(3904 * 1024, 2_000_000, swap, 15255, -1))
        assertNull("12 GB with the reports' lowest (1638 MB)", PageOut.pressure(1638 * 1024, 2_000_000, swap, 11265, -1))
    }

    @Test fun onceIn30sAndBackingOff() {
        val l = PageOut.Limiter()
        assertTrue(l.due(1_000))
        l.done(1_000, 30_000, 0)
        assertFalse(l.due(20_000)); assertTrue(l.due(31_000))
        // the memory came straight back: the wait doubles
        l.done(31_000, 30_000, 20_000)
        assertEquals(60_000L, l.every); assertFalse(l.due(80_000)); assertTrue(l.due(91_000))
        // nothing to page: doubles again, up to 4 min
        l.done(91_000, 0, 0); assertEquals(120_000L, l.every)
        l.done(211_000, 0, 0); assertEquals(240_000L, l.every)
        l.done(451_000, 0, 0); assertEquals(240_000L, l.every)
        // a useful one: back to 30 s
        l.done(700_000, 20_000, 0); assertEquals(30_000L, l.every)
        l.done(730_000, 20_000, 5_000); assertEquals("a little came back: still useful", 30_000L, l.every)
    }

    @Test fun theAnswers() {
        val r = PageOut.parseRes("DSFPO1 seq=12 rc=0 regions=14 eligible_kb=20480 resident_kb=9000 paged_kb=8192 back_kb=1024 hot=2 ms=7\n", 12)!!
        assertEquals(PageOut.Res(0, 14, 20480, 9000, 8192, 1024, 2, 7), r)
        assertNull("another request's", PageOut.parseRes("DSFPO1 seq=11 rc=0 paged_kb=1", 12))
        assertNull(PageOut.parseRes("garbage", 12))
        assertNull(PageOut.parseRes("DSFPO1 seq=12", 12))
        assertEquals(PageOut.Res(3, 0, 0, 0, 0, 0, 0, 0), PageOut.Res.of(longArrayOf(3, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(PageOut.Res.of(null)); assertNull(PageOut.Res.of(longArrayOf(1)))
        val st = listOf("Name:\tservices.exe", "Uid:\t10234\t10234\t10234\t10234", "RssAnon:\t   21504 kB", "VmSwap:\t  100 kB",
            "SigCgt:\t0000000000401a00")
        assertEquals(21504L, PageOut.anonKb(st)); assertEquals(10234, PageOut.uidOf(st))
        assertTrue("SIGURG (23) = bit 22", PageOut.catches(st))
        assertFalse(PageOut.catches(listOf("SigCgt:\t0000000000001a00")))
        assertFalse(PageOut.catches(listOf("Name: x")))
        assertEquals(1024L * 1024, PageOut.swapUsedKb(listOf("SwapTotal:  4194304 kB", "SwapFree:   3145728 kB")))
        assertEquals(-1L, PageOut.swapUsedKb(listOf("MemFree: 1 kB")))
    }

    @Test fun theLines() {
        val ok = PageOut.Res(0, 14, 20480, 9000, 12 * 1024, 2 * 1024, 0, 7)
        val hs = listOf(PageOut.Helper(10, "services.exe", 21 * 1024, 9 * 1024, ok), PageOut.Helper(11, "plugplay.exe", 9 * 1024, -1, null),
            PageOut.Helper(12, "rpcss.exe", 9 * 1024, 9 * 1024, ok.copy(rc = 1)))
        val l = PageOut.helpersLine(3, "free 612 MB < 719", hs, 2100 * 1024, 2112 * 1024, loading = true, psi = "psi some 4.10")
        assertEquals("pageout (helpers, 3, free 612 MB < 719): services.exe 21 -> 9 MB, plugplay.exe no answer, rpcss.exe rc 1; paged 12 MB, " +
            "came back 2 MB; swap used 2100 -> 2112 MB; 7 ms; loading; psi some 4.10", l)
        assertTrue(MemWatch.isEvent(l))
        assertTrue("a key line of the report", MemWatch.shortLine("11-23 12:00:00 $l")!!.startsWith("12:00:00 pageout (helpers"))
        assertEquals("pageout (ours, 2, free 612 MB < 719): rss anon 612 -> 590 MB; paged 12 MB of 14 regions, came back 2 MB; 7 ms",
            PageOut.selfLine(2, "free 612 MB < 719", ok, 612 * 1024, 590 * 1024))
        assertEquals("pageout (ours, 1, x): rc 3", PageOut.selfLine(1, "x", ok.copy(rc = 3), 1, 1))
        assertEquals("Compress idle memory: on (this Thor's default); Wine helpers: 3 requests, 54 MB paged; ours: 2 requests, 30 MB paged; " +
            "WoW: not paged (off in this build)", PageOut.reportLine(true, true, true, 3, 54 * 1024, 2, 30 * 1024, false))   // 1529
        assertEquals("Compress idle memory: off", PageOut.reportLine(false, false, false, 0, 0, 0, 0, false))
        assertEquals("Compress idle memory: on; Wine helpers: not in this game run; ours: 0 requests, 0 MB paged",
            PageOut.reportLine(true, false, false, 0, 0, 0, 0, false))
        assertEquals("Compress idle memory: on (this Thor's default); not supported by this kernel",
            PageOut.reportLine(true, true, true, 0, 0, 0, 0, true))
    }

    @Test fun onByDefaultOnThe8GbThorsOnly() {
        assertTrue(DeviceTier.pageOutDefault(7191)); assertTrue(DeviceTier.pageOutDefault(7700))
        assertFalse(DeviceTier.pageOutDefault(11265)); assertFalse(DeviceTier.pageOutDefault(15255)); assertFalse(DeviceTier.pageOutDefault(0))
        assertTrue("the Lite once its RAM is known", DeviceTier.pageOutDefault(11000, lite = true))
        assertFalse(DeviceTier.pageOutDefault(0, lite = true))
    }

    @Test fun theSwitchIsInMemoryAndResetWithSettings() {
        val M = PerformanceTileModel
        val lines = M.lines(M.MEM, PerformanceTileModel.State(pageOut = true))
        assertEquals(M.L_PAGEOUT, lines.last().key)
        assertEquals("Compress idle memory", lines.last().label); assertEquals(0, lines.last().chosen)
        assertEquals(1, M.lines(M.MEM, PerformanceTileModel.State(pageOut = false)).last().chosen)
        // 1529: the game's own memory is not paged in this build (GamePageOut.on false), so the line says only ours and the helpers'
        assertEquals("Compress idle memory: from the next game start", M.status(M.MEM, PerformanceTileModel.State(pageOutChanged = true)))
        assertEquals(setOf("pageOut"), ResetPlan.prefKeys("settings", setOf("pageOut"), setOf(ResetPlan.Kind.SETTINGS), "me", false))
    }

    /** the launcher's container start adds the preload only through PageOut.launchEnv, after the DNS shim and before libredirect */
    @Test fun theLaunchAddsItOnlyWhenOn() {
        val f = listOf(File("../app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"),
            File("app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java")).first { it.isFile }.readText()
        val dns = f.indexOf("libgamenative_dns_v4mapped.so"); val po = f.indexOf("PageOut.launchEnv(context)"); val redirect = f.indexOf("if (replacePath != null) ld_preload")
        assertTrue(dns in 0 until po && po < redirect)
        assertTrue("if (pageOut != null)" in f)
        val src = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion/PageOut.kt"), File("companion/app/src/main/java/com/jrees/wowcompanion/PageOut.kt"))
            .first { it.isFile }.readText()
        assertTrue("off = nothing added", "if (!on || unsupported) return null" in src)
    }
}
