package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up / login-screen cluster (issues #41 #132 #42 #57, 2026-11-12): a CDN game table resumes where it stopped (CdnResume), the
 * sign-in log says when the client is downloading before its login screen (PreLoginTraffic), its traffic no longer reads as "past the
 * login screen" before the window or after an unread file's cap, and the report names the keys a copied Config.wtf brings.
 */
class StartupIssuesTest {
    private val mb = 1L shl 20

    // ---- #41: CdnResume ----

    @Test fun aKeptPartResumesFromItsEnd() {
        assertEquals(0L, CdnResume.rangeFor(0L, -1L))
        assertEquals(5000L, CdnResume.rangeFor(5000L, -1L))          // size not known yet (a part from an earlier open)
        assertEquals(5000L, CdnResume.rangeFor(5000L, 9000L))
        assertEquals(0L, CdnResume.rangeFor(9000L, 9000L))           // as long as the file (or longer): from the start
        assertEquals(0L, CdnResume.rangeFor(9500L, 9000L))
    }

    @Test fun onlyAnExact206IsAppended() {
        assertEquals(CdnResume.Use.Append(141_000_000L),
            CdnResume.use(206, "bytes 65011712-140999999/141000000", 75_988_288L, 65_011_712L, 141_000_000L))
        // the size learnt from the Content-Range when the part came from an earlier open
        assertEquals(CdnResume.Use.Append(141_000_000L),
            CdnResume.use(206, "bytes 100-140999999/141000000", 140_999_900L, 100L, -1L))
        // not from the part's end, not to the file's end, another size, no total, no header: refused
        for (cr in listOf("bytes 99-140999999/141000000", "bytes 100-1000/141000000", "bytes 100-140999999/*", null, "garbage"))
            assertTrue(cr.toString(), CdnResume.use(206, cr, 1L, 100L, -1L) is CdnResume.Use.Refuse)
        assertTrue(CdnResume.use(206, "bytes 100-199/200", 100L, 100L, 141_000_000L) is CdnResume.Use.Refuse)
        // a 206 to a request without a Range is never trusted
        assertTrue(CdnResume.use(206, "bytes 0-99/100", 100L, 0L, -1L) is CdnResume.Use.Refuse)
    }

    @Test fun a200StartsOverAndOtherCodesKeepTheirPlainReason() {
        assertEquals(CdnResume.Use.Fresh(141_000_000L), CdnResume.use(200, null, 141_000_000L, 65_011_712L, 141_000_000L))
        assertEquals(CdnResume.Use.Fresh(-1L), CdnResume.use(200, null, -1L, 0L, -1L))
        // GameFiles.looseRefusal reads exactly "HTTP 404" / "HTTP 403": the archive route must still follow
        assertEquals(CdnResume.Use.Refuse("HTTP 404"), CdnResume.use(404, null, 0L, 0L, -1L))
        assertEquals(CdnResume.Use.Refuse("HTTP 403"), CdnResume.use(403, null, 0L, 5L, -1L))
        assertTrue(GameFiles.looseRefusal((CdnResume.use(404, null, 0L, 0L, -1L) as CdnResume.Use.Refuse).why))
        assertEquals(CdnResume.Use.Refuse("HTTP 416"), CdnResume.use(416, null, 0L, 5L, 5L))
    }

    @Test fun roundsGoOnWhileTheyAddBytes() {
        assertTrue(CdnResume.keepGoing(1L, 1))
        assertFalse(CdnResume.keepGoing(0L, 1))                       // nothing more arrived: the failure stands
        assertFalse(CdnResume.keepGoing(-10L, 1))
        assertTrue(CdnResume.keepGoing(1L, CdnResume.MAX_ROUNDS - 1))
        assertFalse(CdnResume.keepGoing(1L, CdnResume.MAX_ROUNDS))
    }

    /** #41's 135 MB table on a link that drops every ~35 s (about 15 MB): with the resume it finishes; from 0 each time it never did */
    @Test fun issue41TheTableFinishesOnADroppingLink() {
        val size = 135L * mb; val perTry = 15L * mb
        var have = 0L; var tries = 0; var round = 0
        while (have < size) {
            val before = have
            repeat(CdnRetry.PASSES * 1) {                              // one host (level3), twice a round
                if (have >= size) return@repeat
                val from = CdnResume.rangeFor(have, if (tries == 0) -1L else size)
                val u = CdnResume.use(if (from > 0) 206 else 200, if (from > 0) "bytes $from-${size - 1}/$size" else null, size - from, from, size)
                assertTrue(u is CdnResume.Use.Append || u is CdnResume.Use.Fresh)
                have = minOf(size, from + perTry); tries++
            }
            if (have >= size) break
            assertTrue(CdnResume.keepGoing(have - before, ++round))
        }
        assertEquals(size, have)
        assertTrue(tries in 9..10)
    }

    /** review of 5f61774f: a link that drops before the headers, or one host's 503, must keep the bytes already in hand */
    @Test fun onlyAnAnswerThatProvesThePartWrongDropsIt() {
        assertFalse(CdnResume.dropsPart(null))                        // connect timeout / host not found / end of stream
        assertFalse(CdnResume.dropsPart("HTTP 503"))
        assertFalse(CdnResume.dropsPart("HTTP 404"))
        assertFalse(CdnResume.dropsPart("HTTP 403"))
        assertTrue(CdnResume.dropsPart("HTTP 416"))
        val wrong = (CdnResume.use(206, "bytes 0-99/100", 100L, 50L, 100L) as CdnResume.Use.Refuse).why
        assertTrue(CdnResume.dropsPart(wrong))
        assertTrue(CdnResume.dropsPart(null, damaged = true))
        // the refusal the wiring hands it is exactly use()'s
        assertFalse(CdnResume.dropsPart((CdnResume.use(503, null, -1L, 50L, 100L) as CdnResume.Use.Refuse).why))
    }

    /** #41 again: try 1 drops at 50 MB, try 2's reconnect fails before the headers, try 3 gets a 503: the part survives to try 4 */
    @Test fun issue41APartSurvivesFailuresBeforeTheBody() {
        val size = 135L * mb
        var part = 50L * mb
        for (fail in listOf<String?>(null, "HTTP 503")) if (CdnResume.dropsPart(fail)) part = 0L
        assertEquals(50L * mb, part)
        val from = CdnResume.rangeFor(part, size)
        assertEquals(50L * mb, from)
        assertTrue(CdnResume.use(206, "bytes $from-${size - 1}/$size", size - from, from, size) is CdnResume.Use.Append)
    }

    // ---- #132 / #42: PreLoginTraffic ----

    /** review of 5f61774f: once past the login screen (the no-file grace path) or in the world, the minute lines stop */
    @Test fun theCounterStopsPastTheLoginScreen() {
        assertTrue(PreLoginTraffic.counts(read = false, pastLogin = false, reachedWorld = false))
        assertFalse(PreLoginTraffic.counts(read = false, pastLogin = true, reachedWorld = false))
        assertFalse(PreLoginTraffic.counts(read = true, pastLogin = false, reachedWorld = false))
        assertFalse(PreLoginTraffic.counts(read = false, pastLogin = false, reachedWorld = true))
    }

    @Test fun aDownloadingClientIsSaidOnceAMinute() {
        val t = PreLoginTraffic()
        var now = 1_000_000L
        val lines = ArrayList<String>()
        repeat(181) { t.add(3_185_058L, now)?.let { lines += it }; now += 1000L }   // #132's 3 MB/s, three minutes
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("before the login screen: the game received 182 MB in the last minute"))
        assertTrue(lines[0], lines[0].endsWith("it is downloading"))
        assertEquals(182L, PreLoginTraffic.mb(t.lastMinute))
        assertEquals(549L, PreLoginTraffic.mb(t.total))
        t.reset(); assertEquals(0L, t.total); assertEquals(0L, t.lastMinute)
    }

    @Test fun anIdleLoginScreenSaysNothing() {
        // #57: a few small bursts (11 KB) with the window up, otherwise 0 bytes
        val t = PreLoginTraffic()
        var now = 0L
        repeat(240) { i -> assertNull(t.add(if (i % 30 == 0) 11_872L else 0L, now)); now += 1000L }
        assertEquals(0L, PreLoginTraffic.mb(t.total))
    }

    @Test fun trafficIsASignInOnlyWhenItCanBe() {
        val g = 60L
        // after the client's read: yes; a written file still waiting: no
        assertTrue(PreLoginTraffic.trafficIsSignIn(consumed = true, active = false, windowSeen = false, expiredUnread = true, sinceStartS = 0L, graceS = g))
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, active = true, windowSeen = true, expiredUnread = false, sinceStartS = 999L, graceS = g))
        // #132 run 1: 6 MB/s at 60 s, the window 27 s later: not past the login screen
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, false, windowSeen = false, expiredUnread = false, sinceStartS = 61L, graceS = g))
        // #132 run 2: right after the unread file's cap
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, false, windowSeen = true, expiredUnread = true, sinceStartS = 314L, graceS = g))
        // no saved login, the window up and the grace over: as before
        assertTrue(PreLoginTraffic.trafficIsSignIn(false, false, windowSeen = true, expiredUnread = false, sinceStartS = 60L, graceS = g))
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, false, windowSeen = true, expiredUnread = false, sinceStartS = 59L, graceS = g))
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, false, windowSeen = true, expiredUnread = false, sinceStartS = -1L, graceS = g))
    }

    // ---- #57: the report's Config.wtf line ----

    @Test fun theConfigLineNamesOnlyTheKeysWeNeverSet() {
        val text = "SET portal \"us\"\nSET agentUID \"wowf\"\nSET gxApi \"D3D11\"\nSET accountName \"someone@example.com\"\n" +
            "SET launcherlogin \"1\"\n# a comment\nSET textLocale \"enUS\"\n"
        val line = GameConfigWtf.reportLine(text)
        assertTrue(line, line.startsWith("6 SET lines; portal us; not set by DS Forever: "))
        assertTrue(line, line.contains("accountName") && line.contains("launcherlogin"))
        assertFalse(line, line.contains("example.com"))               // names only, never a value
        assertFalse(line, line.contains("gxApi"))
        assertEquals("missing", GameConfigWtf.reportLine(null))
        assertEquals("0 SET lines; portal (none); not set by DS Forever: none", GameConfigWtf.reportLine(""))
    }
}
