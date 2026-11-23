package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The game client's cold-only page-out (GamePageOut, the user's pick 2026-11-23 "Yes, carefully"), off-device: which process is the
 * client (the same cases as tools/pageout/test_game.c), the caps and the chunking of a pass, the character select trigger (once,
 * before the world, steady memory, below the burst's threshold), the world's (critically low only, never during a loading screen),
 * the thread a request goes to, the answers and the mem.log / report lines, and that the helpers' rules are unchanged.
 * The native half: wsl -e bash tools/pageout/test.sh.
 */
class GamePageOutTest {
    private val G = GamePageOut
    private val games = PageOut.gameNames()
    private fun client(comm: String?, vararg a: String) = G.client(a.toList(), comm, games)

    private fun src(vararg paths: String): String = paths.map { File(it) }.first { it.isFile }.readText()
    private val header by lazy { src("../dsforever/src/main/jni/dsf/dsf_pageout.h", "dsforever/src/main/jni/dsf/dsf_pageout.h", "src/main/jni/dsf/dsf_pageout.h") }

    @Test fun onlyTheGameClientItself() {
        assertTrue(client("Wow-arm64.exe\n", "G:\\_forever_\\Wow-arm64.exe", "-launcherlogin"))
        assertTrue(client("WowB-ARM64.exe", "G:\\_classic_beta_\\WowB-ARM64.exe"))
        assertTrue("before Wine renames it", client("wine", "/x/wine", "G:\\_forever_\\Wow-arm64.exe"))
        assertTrue(client("wine64-preloade", "wine64-preloader", "wine64", "G:\\_forever_\\Wow-arm64.exe"))
        assertTrue(client("WowClassic.exe", "C:\\Program Files\\World of Warcraft\\_classic_\\WowClassic.exe"))
        assertTrue("the comm cut at 15", client("WowClassic-arm", "C:\\x\\WowClassic-arm64.exe"))
        assertTrue(client(null, "C:\\x\\Wow-arm64.exe"))
        // never: the rest of what names the game, and anything unsure
        assertFalse("explorer starting the game", client("explorer.exe", "C:\\windows\\explorer.exe", "/desktop=shell,1920x1080", "G:\\_forever_\\Wow-arm64.exe"))
        assertFalse(client("start.exe", "C:\\windows\\system32\\start.exe", "/exec", "G:\\_forever_\\Wow-arm64.exe"))
        assertFalse("our sign-in bridge", client("wow-session-bri", "G:\\_forever_\\wow-session-bridge.exe", "--port", "1"))
        assertFalse(client("Battle.net.exe", "C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"))
        assertFalse(client("Agent.exe", "C:\\ProgramData\\Battle.net\\Agent\\Agent.exe"))
        assertFalse(client("BlizzardError.e", "C:\\x\\BlizzardError.exe"))
        assertFalse(client("WowError.exe", "C:\\x\\WowError.exe"))
        assertFalse("the comm says otherwise", client("Battle.net.exe", "C:\\x\\Wow-arm64.exe"))
        assertFalse(client("services.exe", "C:\\windows\\system32\\services.exe"))
        assertFalse(client("wine", "/x/wine"))
        assertFalse(client("Wow-arm64.exe.b", "C:\\x\\Wow-arm64.exe.bak"))
        assertFalse(G.client(emptyList(), "", games))
        assertTrue("the launcher's names", G.client(listOf("C:\\games\\game.exe"), "game.exe", listOf("game.exe")))
    }

    /** the helpers' rules are untouched: the client is still "the game" for them, and the helpers are still helpers */
    @Test fun theHelpersAreUnchanged() {
        assertEquals(PageOut.Who.GAME, PageOut.who(listOf("G:\\_forever_\\Wow-arm64.exe"), "Wow-arm64.exe", games))
        assertEquals(PageOut.Who.HELPER, PageOut.who(listOf("C:\\windows\\system32\\services.exe"), "services.exe", games))
        assertFalse(client("services.exe", "C:\\windows\\system32\\services.exe"))
        assertEquals(32 * 1024, PageOut.CAP_KIB)
        assertEquals(30_000L, PageOut.EVERY_MS); assertEquals(240_000L, PageOut.MAX_EVERY_MS)
        assertEquals("free 600 MB < 719", PageOut.pressure(600 * 1024, 2_000_000, 2_000_000, 7191, -1))
        assertTrue("#define PO_CAP_MAX ((uint64_t) 32 << 20)" in header)
    }

    /** the preload holds the same names and caps */
    @Test fun theNativeModeHasTheSameListsAndCaps() {
        val list = Regex("PO_CLIENTS\\[\\] = \\{([^}]*)\\}").find(header)!!.groupValues[1]
        assertEquals(G.CLIENTS, Regex("\"([a-z0-9-]+)\"").findAll(list).map { it.groupValues[1] }.toSet())
        assertTrue("#define PO_G_REQ_MAX ((uint64_t) 32 << 20)" in header)
        assertTrue("#define PO_G_PASS_MAX ((uint64_t) 256 << 20)" in header)
        assertEquals(32 * 1024, G.REQ_KIB); assertEquals(256 * 1024, G.PASS_KIB)
        assertTrue("a pass's commits can reach the pass cap", G.MAX_COMMITS * G.REQ_KIB >= G.PASS_KIB)
        val hook = src("../dsforever/src/main/jni/dsf/dsf_pageout_hook.c", "dsforever/src/main/jni/dsf/dsf_pageout_hook.c")
        val heap = src("../dsforever/src/main/jni/dsf/dsf_heap.c", "dsforever/src/main/jni/dsf/dsf_heap.c")
        assertTrue("#define PO_MAGIC_GAME 0x44534647" in hook); assertTrue("0x44534647" in heap)
        assertTrue("the mode's switch", "getenv(\"${G.ENV_MODE}\")" in hook)
        // the ToS note, kept with the code
        assertTrue("never reads or writes the game's memory contents" in header)
        assertTrue("never reads or writes the game's memory contents" in hook)
    }

    @Test fun charSelectOncePastTheLoginBelowTheBurstsThreshold() {
        assertEquals(4303L, G.charselectBelowMb(7191))       // the 8 GB Thors: 3584 + 719
        assertEquals(4710L, G.charselectBelowMb(11265))      // 12 GB
        assertEquals(5109L, G.charselectBelowMb(15255))      // 16 GB
        val gb8 = 7191L; val swap = 2_000_000L; val t0 = 1_000_000L; val rss = 1900L * 1024
        fun why(avail: Long = 3600L * 1024, sw: Long = swap, up: Boolean = true, started: Long = t0, now: Long = t0 + 60_000, world: Boolean = false,
                signed: Boolean = true, r: Long = rss, steady: Boolean = true, done: Boolean = false) =
            G.charselectWhy(avail, sw, gb8, up, started, now, world, signed, r, steady, done)
        assertEquals("free 3600 MB < 4303", why())
        assertNull("once per game run", why(done = true))
        assertNull("in the world", why(world = true))
        assertNull("the game not up", why(up = false))
        assertNull("no Play", why(started = 0))
        assertNull("a world loading (memory climbing)", why(steady = false))
        assertNull("the world's memory already", why(r = 2600L * 1024))
        assertNull("still the boot loader", why(r = 600L * 1024))
        assertNull("plenty free: the world fits", why(avail = 4400L * 1024))
        assertNull("no room in swap for a pass", why(sw = 400L * 1024))
        assertNull("not past the login screen yet", why(signed = false))
        assertEquals("no past-login signal: after 2 min", "free 3600 MB < 4303", why(signed = false, now = t0 + 120_000))
        assertNotNull("16 GB below its threshold", G.charselectWhy(3904 * 1024, swap, 15255, true, t0, t0 + 60_000, false, true, rss, true, false))
    }

    @Test fun steadyMemoryOnly() {
        val t = GamePageOut.Trend()
        t.add(0, 1_900_000); assertFalse(t.steady(0))
        for (s in 1..9) t.add(s * 1000L, 1_900_000 + s * 1000L)
        assertFalse("not 10 s yet", t.steady(9_000))
        t.add(10_000, 1_905_000); assertTrue(t.steady(10_000))
        t.add(11_000, 2_100_000); assertFalse("climbing 200 MB: a world loading", t.steady(11_000))
        for (s in 12..21) t.add(s * 1000L, 2_100_000)
        assertTrue("flat again for 10 s", t.steady(21_000))
        t.reset(); assertFalse(t.steady(21_000))
    }

    @Test fun inTheWorldOnlyWhenCriticallyLowAndNeverLoading() {
        val gb8 = 7191L; val swap = 2_000_000L
        fun why(avail: Long, warn: Long = -1, world: Boolean = true, loading: Boolean = false, away: Boolean = false, up: Boolean = true, sw: Long = swap) =
            G.criticalWhy(avail, sw, gb8, warn, up, world, loading, away)
        assertEquals("free 340 MB < 359", why(340L * 1024))
        assertNull("low but not critical (the helpers' trigger only)", why(600L * 1024))
        assertEquals("Android's warning, free 600 MB", why(600L * 1024, warn = 3_000))
        assertNull("a warning with plenty free", why(1500L * 1024, warn = 3_000))
        assertNull("an old warning", why(600L * 1024, warn = 30_000))
        assertNull("never during a loading screen", why(300L * 1024, loading = true))
        assertNull("not out of the world (a logout)", why(300L * 1024, away = true))
        assertNull("not before the world", why(300L * 1024, world = false))
        assertNull("the game not up", why(300L * 1024, up = false))
        assertNull("no swap room", why(300L * 1024, sw = 300L * 1024))
        // at most once per 10 s, backing off when it comes straight back
        val l = PageOut.Limiter(G.WORLD_EVERY_MS, G.WORLD_MAX_EVERY_MS)
        assertTrue(l.due(1_000)); l.done(1_000, 100_000, 0)
        assertFalse(l.due(10_000)); assertTrue(l.due(11_000))
        l.done(11_000, 100_000, 80_000); assertEquals(20_000L, l.every)
        l.done(31_000, 0, 0); assertEquals(40_000L, l.every)
        repeat(5) { l.done(100_000L + it, 0, 0) }; assertEquals(160_000L, l.every)
        l.done(400_000, 100_000, 0); assertEquals(10_000L, l.every)
    }

    private fun r(op: Char, more: Boolean, probed: Long = 64, paged: Long = 0, sampled: Long = 0, cold: Long = 0, inuse: Long = 0, back: Long = 0,
                  rc: Int = 0, ms: Long = 5) = GamePageOut.GRes(rc, op, probed, sampled, cold, inuse, paged, paged, back, 0, more, ms)

    @Test fun aPassIsProbeThenCommitsOf32MiB() {
        val p = GamePageOut.Pass(GamePageOut.Phase.CHARSELECT)
        assertEquals(GamePageOut.Step.Send('P', 0, 0), p.first())
        assertEquals(GamePageOut.Step.Send('p', 0, G.STEP_GAP_MS), p.next(r('P', true, probed = 128, sampled = 4096, back = 0)))
        assertEquals(GamePageOut.Step.Send('p', 0, G.STEP_GAP_MS), p.next(r('p', true, probed = 256, sampled = 4096)))
        // the probe is over: the commit after the idle wait (5 s at character select)
        assertEquals(GamePageOut.Step.Send('c', 32 * 1024, 5_000), p.next(r('p', false, probed = 300, sampled = 1400)))
        var step = p.next(r('c', true, probed = 300, paged = 32_000, cold = 33, inuse = 2))
        var n = 1
        while (step is GamePageOut.Step.Send) {
            assertEquals('c', step.op); assertTrue(step.capKib <= 32 * 1024); assertEquals(G.STEP_GAP_MS, step.delayMs)
            step = p.next(r('c', n < 6, probed = 300, paged = 32_000, cold = 33)); n++
        }
        assertEquals(GamePageOut.Step.Done("done"), step)
        assertEquals(7, n); assertEquals(3, p.probes); assertEquals(7, p.commits); assertEquals(10, p.requests)
        assertEquals(7 * 32_000L, p.pagedKb); assertEquals(9_592L, p.sampledKb); assertEquals(7 * 32_000L + 9_592L, p.totalKb)
        assertEquals(231L, p.cold); assertEquals(2L, p.inuse)
        // the world's wait is shorter
        val w = GamePageOut.Pass(GamePageOut.Phase.WORLD); w.first()
        assertEquals(GamePageOut.Step.Send('c', 32 * 1024, 3_000), w.next(r('P', false)))
    }

    @Test fun aPassStopsAtTheFirstProblem() {
        val a = GamePageOut.Pass(GamePageOut.Phase.WORLD); a.first()
        assertEquals(GamePageOut.Step.Done("no answer"), a.next(null))
        val b = GamePageOut.Pass(GamePageOut.Phase.WORLD); b.first()
        assertEquals(GamePageOut.Step.Done("rc 6"), b.next(r('P', false, rc = 6)))
        val c = GamePageOut.Pass(GamePageOut.Phase.WORLD); c.first()
        assertEquals(GamePageOut.Step.Done("nothing cold to probe"), c.next(r('P', false, probed = 0)))
        // the probe never runs away, nor the commits
        val d = GamePageOut.Pass(GamePageOut.Phase.WORLD); var s = d.first(); var k = 0
        while (s is GamePageOut.Step.Send && k < 100) { s = d.next(r(s.op, true, paged = 1000)); k++ }
        assertEquals(G.MAX_PROBES, d.probes); assertEquals(G.MAX_COMMITS, d.commits); assertEquals(GamePageOut.Step.Done("done"), s)
    }

    @Test fun theThreadARequestGoesTo() {
        val ts = listOf(GamePageOut.Th(100, 'S', 9000, false), GamePageOut.Th(101, 'R', 5, false), GamePageOut.Th(102, 'S', 40, false), GamePageOut.Th(103, 'S', 2, true),
            GamePageOut.Th(104, 'S', 40, false), GamePageOut.Th(105, 'D', 1, false))
        assertEquals("asleep, SIGURG open, not the main thread, least CPU (the newer on a tie)", 104, G.pickThread(100, ts))
        assertEquals("none fit: to the process", 0, G.pickThread(100, listOf(GamePageOut.Th(100, 'S', 1, false), GamePageOut.Th(101, 'R', 1, false))))
        assertEquals('S' to 30L, G.parseStat("1234 (Wow-arm64.exe) S 1 2 3 4 5 6 7 8 9 10 20 10 0 0 20 0 50 0"))
        assertEquals('S' to 7L, G.parseStat("77 (a b) c)) S 1 2 3 4 5 6 7 8 9 10 3 4 0"))
        assertNull(G.parseStat("garbage"))
        assertTrue("SIGURG (23) = bit 22", G.blocks(listOf("SigBlk:\t0000000000400000")))
        assertFalse(G.blocks(listOf("SigBlk:\t0000000000000000"))); assertFalse(G.blocks(listOf("Name: x")))
    }

    @Test fun theAnswers() {
        val x = G.parseGRes("DSFPO2 seq=12 rc=0 op=c probed=412 sampled_kb=3264 cold=33 inuse=4 resident_kb=32768 paged_kb=32736 " +
            "back_kb=1024 pass_kb=40000 more=1 ms=41\n", 12)!!
        assertEquals(GamePageOut.GRes(0, 'c', 412, 3264, 33, 4, 32768, 32736, 1024, 40000, true, 41), x)
        assertNull("another request's", G.parseGRes("DSFPO2 seq=11 rc=0 op=c", 12))
        assertNull("a helper's answer", G.parseGRes("DSFPO1 seq=12 rc=0 regions=1", 12))
        assertNull(G.parseGRes("DSFPO2 seq=12 op=c", 12))
        assertEquals(false, G.parseGRes("DSFPO2 seq=3 rc=7 op=c more=0", 3)!!.more)
    }

    @Test fun theLines() {
        val p = GamePageOut.Pass(GamePageOut.Phase.CHARSELECT); p.first()
        p.next(r('P', false, probed = 512, sampled = 16 * 1024, inuse = 0, back = 0))
        p.next(r('c', false, probed = 512, paged = 224 * 1024, cold = 230, inuse = 140))
        val l = G.passLine(1, "free 3900 MB < 4303", p, 1890L * 1024, 1650L * 1024, 2100L * 1024, 2340L * 1024, "done", toThread = true)
        assertEquals("pageout (game, charselect, 1, free 3900 MB < 4303): rss anon 1890 -> 1650 MB; paged 240 MB cold-only (probed 512 chunks: " +
            "230 cold, 140 in use; samples 16 MB); came back 0 MB; swap used 2100 -> 2340 MB; 2 requests, longest 5 ms", l)
        assertTrue(MemWatch.isEvent(l))
        assertTrue("a key line of the report", MemWatch.shortLine("11-23 12:00:00 $l")!!.startsWith("12:00:00 pageout (game"))
        val w = GamePageOut.Pass(GamePageOut.Phase.WORLD); w.first(); w.next(r('P', false, probed = 0, back = 2048))
        assertEquals("pageout (game, world-critical, 2, free 340 MB < 359): rss anon ? -> ? MB; paged 0 MB cold-only (probed 0 chunks: 0 cold, " +
            "0 in use; samples 0 MB); came back 2 MB; swap used ? -> ? MB; 1 requests, longest 5 ms; to the process; stopped: loading screen",
            G.passLine(2, "free 340 MB < 359", w, -1, -1, -1, -1, "stopped: loading screen", toThread = false))
        assertEquals("pageout (game, check after charselect 1): came back 12 MB of 240 MB in 15 s",
            G.checkLine(GamePageOut.Phase.CHARSELECT, 1, 12 * 1024, 240 * 1024, 15_000))
        assertEquals("Compress idle memory: on (this Thor's default); Wine helpers: 3 requests, 54 MB paged; ours: 2 requests, 30 MB paged; " +
            "WoW (cold only): 2 passes, 400 MB paged", PageOut.reportLine(true, true, true, 3, 54 * 1024, 2, 30 * 1024, false, 2, 400 * 1024))
    }

    /** the launch turns the game mode on with the same switch, and the launcher passes it on */
    @Test fun theLaunchTurnsTheGameModeOn() {
        val po = src("../companion/app/src/main/java/com/jrees/wowcompanion/PageOut.kt", "companion/app/src/main/java/com/jrees/wowcompanion/PageOut.kt")
        assertTrue("GamePageOut.ENV_MODE to \"1\"" in po)
        assertTrue("if (!on || unsupported) return null" in po)
        val f = src("../app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java",
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java")
        val mode = f.indexOf("GamePageOut.ENV_MODE"); val guard = f.indexOf("if (pageOut != null)")
        assertTrue(guard in 0 until mode)
        assertEquals("DSF_PAGEOUT_GAME_MODE", G.ENV_MODE)
    }
}
