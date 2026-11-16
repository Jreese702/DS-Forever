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

    @Test fun anOlderDesktopLearnsOnceFromTheFirstLiveDocument() {
        val now = 2_000_000_000L
        val c = PcClock { now }
        c.welcome(-1L)
        assertEquals(now - 3 * hour, c.map(now - 3 * hour))     // backlog: nothing learnt
        c.backlogEnd()
        assertEquals(now + 2_000, c.map(now - hour + 2_000))    // live: an hour behind
        assertEquals(hour, c.shiftMs)
        assertEquals(now + hour + 10_000, c.map(now + 10_000))  // later documents keep the same shift
    }
}
