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
}
