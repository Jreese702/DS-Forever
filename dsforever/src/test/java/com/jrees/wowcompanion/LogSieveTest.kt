package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #9 (1.0.8 "crashing on launch"): a report full of expected misses and nothing about the crash. Off-device. */
class LogSieveTest {
    private fun l(t: String, pid: Int, lvl: Char, tag: String, msg: String) = "10-16 $t  $pid  $pid $lvl $tag: $msg"

    private val raw = buildList {
        add(l("01:10:00.000", 1000, 'I', "WCSETUP", "launcher: Play pressed"))
        add(l("01:10:01.000", 1000, 'E', "AndroidRuntime", "FATAL EXCEPTION: main"))                  // a previous DS Forever process
        add(l("01:10:02.000", 2000, 'E', "", "E: Guest program terminated with status: 3"))             // the launcher's ReleaseTree line
        add(l("01:10:02.500", 2000, 'I', "", "I: Env Vars (Container Base): x"))                       // ordinary launcher chatter
        for (i in 0 until 500) add(l("01:11:%02d.%03d".format(i / 10 % 60, i), 2000, 'W', "WCCASC", "open failed: /storage/x/data/$i (errno 2)"))
        for (i in 0 until 200) add(l("01:12:%02d.%03d".format(i / 10 % 60, i), 2000, 'I', "WCHARVEST", "cdn archives: fetched k$i (3323 bytes)"))
        add(l("01:13:00.000", 2000, 'I', "WCINTERIORS", "baked b1 in 900 ms"))
        add(l("01:13:01.000", 2000, 'I', "ActivityManager", "unrelated"))
        add(l("01:13:02.000", 2000, 'I', "WCCHAT", "Jaina: hi"))
    }

    @Test fun theNoiseIsSummarisedAndTheCrashSurvivesLast() {
        val s = LogSieve.sieve(raw)
        assertTrue(s.contains("500 x CascLib's expected misses"))
        assertTrue(s.contains("200 x game files fetched from the CDN"))
        assertEquals(LogSieve.SAMPLES * 2 * 2, s.lines().count { it.startsWith("  10-16") })    // 3 first + 3 last of each kind
        assertTrue(s.contains("… 494 more"))
        val crashAt = s.indexOf("FATAL EXCEPTION")
        val statusAt = s.indexOf("Guest program terminated with status: 3")
        assertTrue(crashAt > s.indexOf("===== errors, crashes, exits, setup and sign-in ====="))
        assertTrue(statusAt > crashAt)
        assertTrue(s.indexOf("baked b1") in 0 until s.indexOf("===== repeated lines"))                // ordinary lines first
        assertTrue(s.contains("Env Vars (Container Base)"))                                             // the launcher's own lines kept
        assertFalse(s.contains("unrelated")); assertFalse(s.contains("Jaina: hi"))                            // foreign tags, chat
        assertTrue(s.indexOf("launcher: Play pressed") > s.indexOf("===== errors"))                    // setup is important
    }

    @Test fun parsesAnEmptyTag() {
        val p = LogSieve.parse(l("01:10:02.000", 2000, 'E', "", "E: Guest program terminated with status: 3"))!!
        assertEquals("", p.tag); assertTrue(LogSieve.releaseTree(p)); assertTrue(LogSieve.important(p))
    }

    @Test fun theGameCrashHeadOnly() {
        val text = (1..200).joinToString("\n") { "line $it" }
        val h = LogSieve.gameCrashHead(text, maxLines = 5)
        assertTrue(h.startsWith("line 1\n")); assertTrue(h.contains("line 5")); assertFalse(h.contains("line 6"))
        assertTrue(h.endsWith("[the rest of the game's report is left out]"))
    }
    /** #153 (1.0.37): the freeze reports' 12 key lines (the report worker: Description, "main", the first frames) held only the lock's
     *  own frames; in short the callers in our code come first */
    @Test fun anAnrsMainThreadInShortNamesOurCallers() {
        val main = listOf(
            "\"main\" prio=5 tid=1 Waiting",
            "  | group=\"main\" sCount=1 ucsCount=0 flags=1 obj=0x72a0 self=0xb4",
            "  at jdk.internal.misc.Unsafe.park(Native method)",
            "  - waiting on an unknown object",
            "  at java.util.concurrent.locks.LockSupport.park(LockSupport.java:194)",
            "  at java.util.concurrent.locks.AbstractQueuedSynchronizer.parkAndCheckInterrupt(AbstractQueuedSynchronizer.java:885)",
            "  at java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireQueued(AbstractQueuedSynchronizer.java:917)",
            "  at java.util.concurrent.locks.AbstractQueuedSynchronizer.acquire(AbstractQueuedSynchronizer.java:1240)",
            "  at java.util.concurrent.locks.ReentrantLock.lock(ReentrantLock.java:267)",
            "  at com.winlator.xserver.XServer\$MultiXLock.<init>(SourceFile:153)",
            "  at com.winlator.xserver.XServer\$MultiXLock.<init>(SourceFile:0)",
            "  at com.winlator.xserver.XServer.lock(SourceFile:169)",
            "  at com.winlator.xserver.XServer.injectPointerMove(SourceFile:185)",
            "  at com.winlator.widget.TouchpadView.handleTouchscreenEvent(SourceFile:403)",
            "  at com.winlator.widget.TouchpadView.onTouchEventTimed(SourceFile:380)",
            "  at com.winlator.widget.TouchpadView.onTouchEvent(SourceFile:361)",
            "  at android.view.View.dispatchTouchEvent(View.java:15024)",
            "  at android.view.ViewGroup.dispatchTransformedTouchEvent(ViewGroup.java:3115)",
            "  at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2788)",
            "  at com.jrees.wowcompanion.CompanionHost\$\$ExternalSyntheticLambda467.run(SourceFile:0)",
            "  at android.os.Looper.loop(Looper.java:288)",
            "  at android.app.ActivityThread.main(ActivityThread.java:7918)")
        val short = LogSieve.compactFrames(main)
        // the report worker's key lines: the "main" header and the "at " / "- " / "native:" lines, 11 after the Description
        val keys = short.map { it.trim() }.filter { Regex("""^"main"|^native: #\d+|^at |^- (waiting|locked|sleeping)""").containsMatchIn(it) }.take(11)
        assertTrue(keys.joinToString(" / "), keys.any { it.contains("TouchpadView.handleTouchscreenEvent") })
        assertTrue(keys.any { it.contains("XServer.injectPointerMove") })
        assertEquals("at jdk.internal.misc.Unsafe.park(Native method)", keys[1])          // what it is blocked in stays first
        assertTrue(short.contains("  at ... (5 runtime frames)"))
        assertEquals(1, short.count { it.contains("XServer\$MultiXLock.<init>") })          // R8's repeat goes
        assertFalse(short.any { it.contains("ExternalSyntheticLambda") })
        assertTrue(short.contains("  | group=\"main\" sCount=1 ucsCount=0 flags=1 obj=0x72a0 self=0xb4"))
        // in the record: the short main thread first, the full one at the end, one "main" header
        val rec = "DS Forever: how the last run ended\n---------- Trace ----------\n" + main.joinToString("\n") +
            "\n\n\"Binder:1_1\" prio=5 tid=9 Native\n  at a.b(C.kt:1)\n"
        val t = LogSieve.traceFirst(rec)
        assertEquals(1, Regex("\"main\" prio=5").findAll(t).count())
        assertTrue(t.indexOf("at ... (5 runtime frames)") < t.indexOf("[the rest of the trace]"))
        assertTrue(t.indexOf("[the main thread in full]") > t.indexOf("\"Binder:1_1\""))
        assertTrue(t.substring(t.indexOf("[the main thread in full]")).contains("AbstractQueuedSynchronizer.acquireQueued"))
        // native frames: the first two, then one line
        val nat = listOf("\"main\" prio=5 tid=1 Native") + (0..9).map { "  native: #%02d pc 000a6c2c  /system/lib64/libc.so (f$it+12)".format(it) } +
            listOf("  at com.winlator.renderer.VulkanRenderer.nativeSetFrameGenerationShaders(Native method)")
        val ns = LogSieve.compactFrames(nat)
        assertEquals(listOf(nat[0], nat[1], nat[2], "  native: #02-#09 (8 more native frames)", nat.last()), ns)
    }
}
