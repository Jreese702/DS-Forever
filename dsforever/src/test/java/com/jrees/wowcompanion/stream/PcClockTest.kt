package com.jrees.wowcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** issue #414: the PC's clock onto the Thor's ([PcClock]) */
class PcClockTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val year = 365L * 24 * hour

    @Test fun timeZonesSnapToQuarterHoursAndNothingElseDoes() {
        assertEquals(2 * hour, PcClock.snap(2 * hour + 3_000))
        assertEquals(-2 * hour, PcClock.snap(-2 * hour + 40_000))
        assertEquals(5 * hour + 30 * min, PcClock.snap(5 * hour + 30 * min - 1_000))   // India
        assertEquals(5 * hour + 45 * min, PcClock.snap(5 * hour + 45 * min))            // Nepal
        assertEquals(0L, PcClock.snap(4 * min))                                          // a drifted clock: left alone
        assertEquals(0L, PcClock.snap(2 * hour + 7 * min))                               // not near a quarter hour
        assertEquals(0L, PcClock.snap(30 * hour))                                        // no two zones are that far apart
    }

    @Test fun newYearIsTakenTheShortWayRound() {
        assertEquals(hour, PcClock.year(hour - year))
        assertEquals(-hour, PcClock.year(year - hour))
        assertEquals(3 * hour, PcClock.year(3 * hour))
    }

    @Test fun desktopsWordWinsAndIsExact() {
        var now = 1_000_000_000L
        val c = PcClock { now }
        c.welcome(now - 2 * hour - 37_000)          // a PC 2 h behind and 37 s slow
        assertEquals(2 * hour + 37_000, c.shiftMs)
        c.backlogEnd()
        assertEquals(now, c.map(now - 2 * hour - 37_000))
        assertEquals(-1L, c.map(-1L))
        // a new connection to a PC in step: no shift (a little network delay is not one)
        c.welcome(now - 800)
        assertEquals(0L, c.shiftMs)
        now += 5_000
        c.backlogEnd()
        assertEquals(now - 2 * hour, c.map(now - 2 * hour))   // learnt from the welcome: not re-learnt from a document
    }

    @Test fun anOlderDesktopLearnsFromLiveDocumentsThatKeepTime() {
        var now = 2_000_000_000L
        val c = PcClock { now }
        c.welcome(-1L)
        assertEquals(now - 3 * hour, c.map(now - 3 * hour))     // backlog: nothing learnt
        c.backlogEnd()
        assertEquals(now - hour + 2_000, c.map(now - hour + 2_000))    // live, an hour behind: not yet (one document proves nothing)
        assertEquals(0L, c.shiftMs)
        now += 3_000
        assertEquals(now - hour - 1_000, c.map(now - hour - 1_000))    // 3 s later: still too soon
        now += 3_000
        assertEquals(now + 4_000, c.map(now - hour + 4_000))           // 6 s after the first, the same gap: an hour
        assertEquals(hour, c.shiftMs)
        now += 10_000
        assertEquals(now + 10_000, c.map(now - hour + 10_000))         // later documents keep the shift
    }

    @Test fun oldDocumentsReadAllAtOnceTeachNothing() {
        // Desktop 0.2.29 reading the old log after backlog_end (a same-zone PC): 3 h old, a second apart, arriving within a moment
        var now = 2_000_000_000L
        val c = PcClock { now }
        c.welcome(-1L)
        c.backlogEnd()
        for (i in 0 until 200) { assertEquals(now - 3 * hour + i * 1_000L, c.map(now - 3 * hour + i * 1_000L)); now += 20 }
        assertEquals(0L, c.shiftMs)
        // read slowly (a weak link): game time runs far faster than the Thor's clock, so the gap never holds still for 5 s
        for (i in 0 until 60) { c.map(now - 3 * hour + 200_000L + i * 60_000L); now += 6_000 }
        assertEquals(0L, c.shiftMs)
        // then the game's own new documents: in step, nothing to move
        for (i in 0 until 3) { assertEquals(now - 400, c.map(now - 400)); now += 6_000 }
        assertEquals(0L, c.shiftMs)
    }

    @Test fun aWrongShiftHealsOnTheNextDocumentsThatKeepTime() {
        var now = 2_000_000_000L
        val c = PcClock { now }
        c.welcome(-1L)
        c.backlogEnd()
        // two documents 6 s apart that happen to sit 2 h back and keep time (the worst an old log can do)
        c.map(now - 2 * hour); now += 6_000; c.map(now - 2 * hour)
        assertEquals(2 * hour, c.shiftMs)
        // the game's new documents (same zone) put it right
        c.map(now - 300); now += 6_000; c.map(now - 300)
        assertEquals(0L, c.shiftMs)
        assertEquals(now - 300, c.map(now - 300))
    }

    @Test fun aShiftedStampStaysOnTheYearsScale() {
        val newYear = com.jrees.wowcompanion.InputGate.yearMs(1, 1, 0, 30, 0, 0)
        val oldYear = com.jrees.wowcompanion.InputGate.yearMs(12, 31, 23, 30, 0, 0)
        assertEquals(oldYear, PcClock.wrap(newYear - hour))
        assertEquals(newYear, PcClock.wrap(oldYear + hour))
        assertEquals(oldYear, PcClock.wrap(oldYear))
        // the Thor (UTC+1) already in the new year, the PC (UTC) still in the old one: its last document of the year is now
        val c = PcClock { newYear }
        c.welcome(oldYear)
        assertEquals(hour, c.shiftMs)
        assertEquals(newYear, c.map(oldYear))
    }
}
