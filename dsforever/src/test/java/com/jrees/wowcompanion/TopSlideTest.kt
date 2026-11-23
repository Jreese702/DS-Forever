package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN FIGHTS = TOP SCREEN, THE SLIDE ([TopSlide]; the user, 2026-11-22: "the bottom [band] does not transition out during combat like it
 * should so it shows in both places. It should be a slick animation looking like it slides up to the top screen"): the curves (up the
 * ease-in Motion.curveOut, down the ease-out Motion.curveEnter, one curve for every part so the columns ride the band's foot), the
 * durations, monotonic motion, a reversal at the midpoint continuing from where the parts are, and the solver's away box (the band above
 * the page, the columns grown up).
 */
class TopSlideTest {
    @Test fun theCurves() {
        assertTrue("up: an ease-in (behind the line at the middle)", TopSlide.partK(0.5f, up = true) < 0.5f)
        assertTrue("down: ahead of the line at the middle (it lands soft)", TopSlide.partK(0.5f, up = false) > 0.5f)
        assertTrue(TopSlide.UP.contentEquals(floatArrayOf(0.4f, 0f, 1f, 1f)))                 // Motion.curveOut
        // ruling D: the way down starts slow (Motion.curve's), not at full speed (the old curveEnter (0, 0, 0.2, 1))
        assertTrue(TopSlide.DOWN.contentEquals(floatArrayOf(0.4f, 0f, 0.2f, 1f)))
        for (up in listOf(true, false)) for (band in listOf(true, false)) {
            assertEquals(0f, TopSlide.partK(0f, up, band), 0f); assertEquals(1f, TopSlide.partK(1f, up, band), 0f)
        }
        // a known point of Motion.curve (0.4, 0, 0.2, 1): x = 0.5 -> y ~ 0.7756 (the Lua NB.ease's value, test_combat_bar_spot.py)
        assertEquals(0.7756f, TopSlide.cubic(0.5f, floatArrayOf(0.4f, 0f, 0.2f, 1f)), 0.001f)
    }

    @Test fun theDurations() {
        assertEquals(240L, Motion.TOP_SLIDE_UP)
        assertEquals(240L, Motion.TOP_SLIDE_DOWN)
        assertEquals(Motion.TOP_SLIDE_UP, TopSlide.durationMs(away = true))
        assertEquals(Motion.TOP_SLIDE_DOWN, TopSlide.durationMs(away = false))
        // the whole motion: up the columns' braked arrival (~311 ms; the band off at 240), down 240
        assertEquals(TopSlide.COLS_UP_MS, TopSlide.totalMs(up = true))
        assertTrue("about 311 ms (${TopSlide.COLS_UP_MS})", TopSlide.COLS_UP_MS in 305L..318L)
        assertEquals(Motion.TOP_SLIDE_DOWN, TopSlide.totalMs(up = false))
        assertEquals("the band is off at its own 240 ms", 1f, TopSlide.kAt(Motion.TOP_SLIDE_UP.toFloat(), up = true, band = true), 0f)
        assertTrue(TopSlide.isSlide(false, true)); assertTrue(TopSlide.isSlide(true, false))
        assertFalse(TopSlide.isSlide(false, false)); assertFalse(TopSlide.isSlide(true, true))
    }

    @Test fun everyPartMovesOneWay() {
        for (up in listOf(true, false)) for (band in listOf(true, false)) {
            var last = -1f
            for (i in 0..600) {
                val k = TopSlide.partK(i / 600f, up, band)
                assertTrue("monotonic ($up, band $band) at $i", k >= last - 1e-6f)
                last = k
            }
        }
    }

    private fun box(r: HomeLayoutSolver.Result, p: HomePart) = r.boxes.getValue(p).let { floatArrayOf(it.l, it.t, it.r, it.b) }

    /** every part but the band on the columns' curve, the band on its own; the way down from the band's start at the page's edge
     *  (HomeView: TopSlide.edgeShift): at every 0.5 ms a column's (the card's, the party module's, a bar above a column's) top stays at
     *  least the page's 8 px gap under the band's foot - every rest, bar dock and card, and the Party-mode Top fight's lays */
    @Test fun theColumnsRideTheBandsFootEveryFrame() {
        val party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_TOP, 3, false)
        for (rest in HomeRest.values()) for (cast in HomeLayout.CastDock.values()) for (card in listOf(false, true))
            for (shape in listOf<Pair<Int, Boolean>?>(null, 1 to false, 2 to true, 3 to false, 5 to true, 6 to true)) {
                val l = HomeLayout(rest = rest, fight = HomeLayout.FightBand.TOP, cast = cast)
                val calm: HomeLayoutSolver.Result; val fight: HomeLayoutSolver.Result
                if (shape == null) {
                    calm = HomeLayoutSolver.places(l, HomeLayoutSolver.State(card = card, cardH = 118f))
                    fight = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true, card = card, cardH = 118f))
                } else {
                    val p = party.copy(n = shape.first, pet = shape.second)
                    calm = HomeLayoutSolver.places(l, HomeLayoutSolver.State(card = card, cardH = 118f, partyOn = true, party = p.copy(mode = PartyLayout.Mode.REST)))
                    fight = HomeLayoutSolver.places(l, HomeLayoutSolver.State(card = card, cardH = 118f, partyOn = true, partyFight = true, party = p))
                    assertTrue(fight.away)
                }
                for (up in listOf(true, false)) {
                    val a = if (up) calm else fight; val b = if (up) fight else calm
                    val bandFrom = box(a, HomePart.BARS).also { if (!up) { val d = TopSlide.edgeShift(it[3]); it[1] += d; it[3] += d } }
                    val total = TopSlide.totalMs(up)
                    var ms = 0f
                    while (ms <= total) {
                        val f = ms / total
                        val band = TopSlide.frame(bandFrom, box(b, HomePart.BARS), f, up, band = true)
                        for (p in listOf(HomePart.CHAT, HomePart.MAP, HomePart.QUESTS, HomePart.PARTY, HomePart.STRIP)) {
                            if (p !in a.boxes || p !in b.boxes) continue
                            if (p == HomePart.STRIP && !cast.top) continue                       // (a bar under a column stays put)
                            val c = TopSlide.frame(box(a, p), box(b, p), f, up, band = false)
                            assertTrue("$l $shape $p up=$up ms=$ms: under the band's foot (${c[1]} vs ${band[3]})", c[1] >= band[3] + 8f - 0.01f)
                        }
                        ms += 0.5f
                    }
                }
            }
    }

    /**
     * THE COLUMNS EASE INTO THE TOP GAP (the user's ruling D, 11-22: "the columns hit the top gap at full speed and stop dead - ease the
     * arrival"): on the band's curve until the brake, then an even brake - the speed continuous there, the last frame small, ~311 ms
     * (b1e28506: the columns on the band's ease-in, their last frame ~25 px, then nothing)
     */
    @Test fun theColumnsEaseIntoTheTopGap() {
        val dist = 226f                                                  // the Diamonds' columns: 234 -> 8
        val frame = 1000f / 60f
        val end = TopSlide.COLS_UP_MS.toFloat()
        val last = dist * (TopSlide.kAt(end, true, false) - TopSlide.kAt(end - frame, true, false))
        assertTrue("the last frame under 2 px ($last)", last < 2f)
        // the speed at the brake: the same either side (no kink), the band's there
        fun v(ms: Float, band: Boolean) = (TopSlide.kAt(ms + 0.05f, true, band) - TopSlide.kAt(ms - 0.05f, true, band)) / 0.1f
        val at = TopSlide.BRAKE_AT_MS.toFloat()
        val before = v(at - 0.2f, false); val after = v(at + 0.2f, false)
        assertEquals("continuous at the brake ($before / $after)", before, after, before * 0.02f)
        assertEquals("on the band's curve before it", v(at - 20f, true), v(at - 20f, false), 1e-6f)
        assertEquals("about 21 px a frame at the brake", 21f, before * dist * frame, 1.5f)
        // braking evenly: the speed falls linearly to ~0 at the end
        assertTrue("slowing all the way", v(at + 40f, false) < after && v(at + 90f, false) < v(at + 40f, false))
        assertTrue("~0 at the end", v(end - 1f, false) * dist * frame < 0.5f)
        // b1e28506's: the columns on the band's curve - their last frame was the biggest
        val old = dist * (TopSlide.cubic(1f, TopSlide.UP) - TopSlide.cubic(1f - frame / 240f, TopSlide.UP))
        assertTrue("(b1e28506's last frame: $old px)", old > 20f)
    }

    @Test fun theSolverLaysTheBandAwayAndTheColumnsGrowUp() {
        for (rest in HomeRest.values()) {
            val l = HomeLayout(rest = rest, fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.UNDER_CHAT)
            val calm = HomeLayoutSolver.places(l, HomeLayoutSolver.State())
            val fight = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true))
            val a = calm.boxes.getValue(HomePart.BARS); val b = fight.boxes.getValue(HomePart.BARS)
            assertFalse(calm.away); assertTrue("$rest: away in the fight", fight.away)
            assertEquals("$rest: the band's bottom 16 over the frame's top", -16f, b.b, 0f)
            assertEquals("$rest: the same size (no re-measure)", a.h, b.h, 0.001f); assertEquals(a.w, b.w, 0f)
            assertEquals("$rest: the chat's column from the top gap", 8f, fight.boxes.getValue(HomePart.CHAT).t, 0f)
            assertEquals("$rest: the map's", 8f, fight.boxes.getValue(HomePart.MAP).t, 0f)
            assertEquals(0f, fight.barsBottom, 0f)
            assertTrue(HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), fight).isEmpty())
        }
        // Party mode: no takeover in the solver's sense (R0) - the band stays out of a fight; its FIGHT lays it away (ruling C);
        // Grow / Same size unchanged
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP)
        assertFalse(HomeLayoutSolver.places(top, HomeLayoutSolver.State(takeover = true, partyOn = true)).away)
        assertFalse(HomeLayoutSolver.places(top, HomeLayoutSolver.State(partyOn = true)).away)
        assertTrue(HomeLayoutSolver.places(top, HomeLayoutSolver.State(partyOn = true, partyFight = true)).away)
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME))
            assertFalse(HomeLayoutSolver.places(HomeLayout(fight = f), HomeLayoutSolver.State(takeover = true)).away)
    }

    /** a reversal at the midpoint: the new motion starts from the parts' boxes NOW (HomeView.apply: from = now), so nothing jumps */
    @Test fun aReversalAtTheMidpointIsContinuous() {
        val l = HomeLayout(fight = HomeLayout.FightBand.TOP)
        val rest = HomeLayoutSolver.places(l, HomeLayoutSolver.State())
        val away = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true))
        for (p in listOf(HomePart.BARS, HomePart.CHAT, HomePart.MAP)) {
            val mid = TopSlide.frame(box(rest, p), box(away, p), 0.5f, up = true)
            val back0 = TopSlide.frame(mid, box(rest, p), 0f, up = false)
            val back1 = TopSlide.frame(mid, box(rest, p), 1f / 15f, up = false)
            for (i in 0..3) assertEquals("$p: no jump at the reversal", mid[i], back0[i], 0f)
            assertTrue("$p: heading back", kotlin.math.abs(back1[1] - box(rest, p)[1]) <= kotlin.math.abs(mid[1] - box(rest, p)[1]) + 1e-4f)
        }
    }

    /** the 11-22 review: a re-lay inside the slide goes ON with it - the time left, the same path - and only then (not a new slide, not a
     *  module, not the other way, not after it landed) */
    @Test fun aReLayMidSlideGoesOnWithIt() {
        assertEquals(-1f, TopSlide.resumeAt(slide = true, wasAway = false, isAway = true, moduleInvolved = false, cutAt = 0.3f, cutUp = true), 0f)
        assertEquals(-1f, TopSlide.resumeAt(false, true, true, moduleInvolved = true, cutAt = 0.3f, cutUp = true), 0f)
        assertEquals(-1f, TopSlide.resumeAt(false, true, true, false, cutAt = -1f, cutUp = true), 0f)
        assertEquals("the cut one went the other way", -1f, TopSlide.resumeAt(false, true, true, false, cutAt = 0.3f, cutUp = false), 0f)
        assertEquals("landed", -1f, TopSlide.resumeAt(false, true, true, false, cutAt = 1f, cutUp = true), 0f)
        assertEquals("cut in its first frame: a whole slide still", 0f, TopSlide.resumeAt(false, true, true, false, cutAt = 0f, cutUp = true), 0f)
        assertEquals(0.4f, TopSlide.resumeAt(false, true, true, false, cutAt = 0.4f, cutUp = true), 0f)
        assertEquals(0.25f, TopSlide.resumeAt(false, false, false, false, cutAt = 0.25f, cutUp = false), 0f)
        // the time left
        assertEquals(TopSlide.COLS_UP_MS, TopSlide.restMs(true, 0f))
        assertEquals(Math.round(TopSlide.COLS_UP_MS * 0.6), TopSlide.restMs(true, 0.4f))
        assertEquals(Motion.TOP_SLIDE_DOWN, TopSlide.restMs(false, 0f))
        assertEquals(1L, TopSlide.restMs(false, 1f))
        // the same path: a part whose box did not change, re-started from where it stood at the cut, follows the uncut slide exactly
        for (up in listOf(true, false)) for (band in listOf(true, false)) for (cut in listOf(0f, 0.2f, 0.5f, 0.8f)) {
            val from = floatArrayOf(0f, 200f, 1240f, 416f); val to = floatArrayOf(0f, -232f, 1240f, -16f)
            val at = TopSlide.frame(from, to, cut, up, band)
            for (j in 0..20) {
                val u = j / 20f
                val q = TopSlide.resumeK(u, cut, up, band)
                val whole = TopSlide.frame(from, to, TopSlide.overall(u, cut), up, band)
                for (i in 0..3) assertEquals("up=$up band=$band cut=$cut u=$u", whole[i], at[i] + (to[i] - at[i]) * q, 0.05f)
            }
            // (the band on the way up is off at 240 of the motion's ~311 ms: cut past that, it stays where it landed)
            val landed = TopSlide.partK(cut, up, band) >= 1f
            assertEquals(if (landed) 1f else 0f, TopSlide.resumeK(0f, cut, up, band), 1e-5f)
            assertEquals(1f, TopSlide.resumeK(1f, cut, up, band), 1e-5f)
        }
    }

    /**
     * THE HAND-BACK IS CONTINUOUS (the user's ruling D, 11-22, from the shots: "~2 frames where neither the Wings nor the band shows,
     * then the band's first moving frame drops ~49 px"). The Wings start down at 0 on the add-on's clock and are gone at WINGS_OUT_MS; the
     * app hears the end [lat] 40-90 ms later, holds HAND_BACK_MS, starts the animator (its 0-frame, then the first moving frame on the
     * screen START_FRAMES_MS after the start, then one a frame), the band from the page's top edge on DOWN. For every channel delay the
     * band's first pixels come at most one frame before the Wings' last leave (the band then 3 px or less while the Wings still show more
     * than a sliver) and at most 2 frames after (b1e28506: a 199 ms hold - 73 ms of nothing at 40 ms, 123 at 90 - then 55 px at once).
     */
    @Test fun theWayDownHandsBackContinuously() {
        assertEquals(200L, TopSlide.WINGS_OUT_MS)                                  // ActionBar NB.TOP.OUT_S
        assertTrue(TopSlide.WINGS_OUT.contentEquals(floatArrayOf(0.4f, 0f, 1f, 1f))) // NB.easeIn
        assertEquals(0f, TopSlide.wingsOff(0L), 0f); assertEquals(1f, TopSlide.wingsOff(TopSlide.WINGS_OUT_MS), 0f)
        assertEquals(109L, TopSlide.HAND_BACK_MS)
        assertEquals(TopSlide.WINGS_OUT_MS - TopSlide.CHANNEL_MIN_MS - TopSlide.START_FRAMES_MS - TopSlide.OVERLAP_MS, TopSlide.HAND_BACK_MS)
        val frame = 1000f / 60f
        for (rest in HomeRest.values()) {
            val travel = HomeLayoutSolver.restBottom(HomeLayout(rest = rest))             // from the edge (edgeShift) to its rest
            for (lat in 40L..90L) {
                val start = lat + TopSlide.HAND_BACK_MS
                val first = start + TopSlide.START_FRAMES_MS.toFloat()
                assertTrue("lat=$lat: overlap ${TopSlide.WINGS_OUT_MS - first} ms", TopSlide.WINGS_OUT_MS - first <= TopSlide.OVERLAP_MS + 0.01f)
                assertTrue("lat=$lat: gap ${first - TopSlide.WINGS_OUT_MS} ms", first - TopSlide.WINGS_OUT_MS <= 2 * frame + 0.5f)
                for (n in 1..20) {
                    val shownAt = first + (n - 1) * frame
                    val px = travel * TopSlide.kAt(n * frame, up = false, band = true)
                    val wings = 1f - TopSlide.wingsOff(shownAt)
                    if (wings > 0.02f) assertTrue("$rest lat=$lat frame $n: the band $px px while the Wings show $wings", px <= 3f)
                }
            }
        }
        // who holds
        assertEquals("a fresh way down from the band landed away", TopSlide.HAND_BACK_MS,
            TopSlide.holdMs(slide = true, isAway = false, bandOff = true, cutUp = false, cutHold = 0L, resumeAt = -1f))
        assertEquals("a reversal of an up-slide of no known age turns back at once", 0L,
            TopSlide.holdMs(slide = true, isAway = false, bandOff = false, cutUp = true, cutHold = 0L, resumeAt = -1f))
        assertEquals("a reversal: an up-slide cut on its way waits as long as the Wings need", TopSlide.reversalHoldMs(230L),
            TopSlide.holdMs(slide = true, isAway = false, bandOff = false, cutUp = true, cutHold = 0L, resumeAt = -1f, upMs = 230L))
        assertEquals("a down-slide reversed (a glide's cut, not the way up): none", 0L,
            TopSlide.holdMs(slide = true, isAway = false, bandOff = false, cutUp = false, cutHold = 0L, resumeAt = -1f, upMs = 230L))
        assertEquals("the way up never holds", 0L, TopSlide.holdMs(true, isAway = true, bandOff = false, cutUp = false, cutHold = 0L, resumeAt = -1f))
        assertEquals("a fight back inside the hold: up at once (nothing moved)", 0L,
            TopSlide.holdMs(true, isAway = true, bandOff = true, cutUp = false, cutHold = 80L, resumeAt = -1f))
        assertEquals("a re-lay inside the hold waits what was left", 80L,
            TopSlide.holdMs(false, isAway = false, bandOff = true, cutUp = false, cutHold = 80L, resumeAt = 0f))
        assertEquals("never more than the hand-back", TopSlide.HAND_BACK_MS,
            TopSlide.holdMs(false, isAway = false, bandOff = true, cutUp = false, cutHold = 500L, resumeAt = 0f))
        assertEquals("a re-lay past the hold: none", 0L, TopSlide.holdMs(false, false, bandOff = false, cutUp = false, cutHold = 0L, resumeAt = 0.3f))
        assertEquals("a re-lay of an up-slide: none", 0L, TopSlide.holdMs(false, true, bandOff = false, cutUp = true, cutHold = 50L, resumeAt = 0.3f))
        assertEquals("not a slide's continuation: none", 0L, TopSlide.holdMs(false, false, bandOff = true, cutUp = false, cutHold = 50L, resumeAt = -1f))
        assertTrue(TopSlide.bandOff(-16f)); assertTrue(TopSlide.bandOff(-15.6f)); assertFalse(TopSlide.bandOff(-10f)); assertFalse(TopSlide.bandOff(null))
        // the band starts from the edge: moved down unseen only when wholly above the page
        assertEquals(16f, TopSlide.edgeShift(-16f), 0f); assertEquals(0f, TopSlide.edgeShift(-10f), 0f); assertEquals(0f, TopSlide.edgeShift(null), 0f)
        // the slide's own time after the hold
        assertEquals(0f, TopSlide.afterHold(100f, 109L, 240L), 0f)
        assertEquals(0.5f, TopSlide.afterHold(229f, 109L, 240L), 1e-5f)
        assertEquals(1f, TopSlide.afterHold(500f, 109L, 240L), 0f)
        assertEquals(0.5f, TopSlide.afterHold(120f, 0L, 240L), 1e-5f)
    }

    /** ruling D: the band's first frames on the way down are small (it starts at the page's edge on a slow-start curve) - b1e28506's
     *  first frame moved it 55 px (its first SEEN frame showed ~39 px) */
    @Test fun theBandsFirstFramesAreSmall() {
        val frame = 1000f / 60f
        for (rest in HomeRest.values()) {
            val travel = HomeLayoutSolver.restBottom(HomeLayout(rest = rest))
            val f1 = travel * TopSlide.kAt(frame, up = false, band = true)
            val f2 = travel * TopSlide.kAt(2 * frame, up = false, band = true)
            assertTrue("$rest: the first frame under 4 px ($f1)", f1 < 4f)
            assertTrue("$rest: the second under 15 px ($f2)", f2 < 15f)
            // and every step after grows smoothly (no frame jumps more than ~50 % of the one before it, past the first two)
            var last = f2 - f1
            for (n in 3..6) {
                val step = travel * (TopSlide.kAt(n * frame, false, true) - TopSlide.kAt((n - 1) * frame, false, true))
                assertTrue("$rest frame $n: $step after $last", step <= last * 3f + 1f)
                last = step
            }
        }
        val old = 242f * TopSlide.cubic(frame / 240f, floatArrayOf(0f, 0f, 0.2f, 1f))
        assertTrue("(b1e28506's first frame: $old px)", old > 50f)
    }

    /**
     * A REVERSAL WAITS AS LONG AS THE WINGS NEED (the 11-22 review: the fight's end 200-230 ms into the up-slide - the band ~93 % off the
     * page, the Wings ~93 % in - turned the band back down at once; at +48 ms it was half back while the Wings still showed 85 %). The
     * Wings' way up is the add-on's (HOLD_IN 60 ms, IN_S 0.26 s on NB.easeOut); reversed, they leave from where they are on the way
     * down's curve. The band holds where it stands until they are mostly off, whatever the channel's delay on the fight's end.
     */
    @Test fun aLateReversalWaitsForTheWings() {
        assertEquals(60L, TopSlide.WINGS_HOLD_IN_MS); assertEquals(260L, TopSlide.WINGS_IN_MS)       // ActionBar NB.TOP.HOLD_IN / IN_S
        assertTrue(TopSlide.WINGS_IN.contentEquals(floatArrayOf(0f, 0f, 0.2f, 1f)))                  // NB.easeOut
        assertEquals(0f, TopSlide.wingsIn(60L), 0f); assertEquals(1f, TopSlide.wingsIn(320L), 0f)
        assertEquals("the review's figure: 93 % in at 230 ms", 0.93f, TopSlide.wingsIn(230L), 0.01f)
        assertEquals(0L, TopSlide.wingsOutAt(1f)); assertEquals(TopSlide.WINGS_OUT_MS, TopSlide.wingsOutAt(0f))
        // the hold: none before the Wings move or for no age; the whole one when they were all in; never more; never a step
        assertEquals(0L, TopSlide.reversalHoldMs(-1L))
        assertEquals("the Wings not started: at once", 0L, TopSlide.reversalHoldMs(50L))
        assertEquals("all in: the whole hand-back", TopSlide.HAND_BACK_MS, TopSlide.reversalHoldMs(400L))
        var was = 0L
        for (up in 0L..400L) {
            val h = TopSlide.reversalHoldMs(up)
            assertTrue("up=$up: never less than an earlier cut's ($h < $was)", h >= was)
            assertTrue("up=$up: no step ($was -> $h)", h - was <= 8L)
            assertTrue(h in 0L..TopSlide.HAND_BACK_MS)
            was = h
        }
        assertTrue("late (230 ms): a real hold (${TopSlide.reversalHoldMs(230L)})", TopSlide.reversalHoldMs(230L) >= 40L)
        // the property: cut at [up] on the add-on's clock (the Wings reverse at 0 from wingsIn(up), gone WINGS_OUT_MS - e0 later); the app
        // hears it [lat] 40-90 ms later, holds, then the animator's first moving frame: never more than one frame before they are gone
        for (up in 0L..239L step 3) for (lat in 40L..90L step 5) {
            val e0 = TopSlide.wingsOutAt(TopSlide.wingsIn(up))
            val gone = TopSlide.WINGS_OUT_MS - e0
            val first = lat + TopSlide.reversalHoldMs(up) + TopSlide.START_FRAMES_MS
            assertTrue("up=$up lat=$lat: the band moved at $first, the Wings gone at $gone", first >= gone - TopSlide.OVERLAP_MS)
        }
    }

    /**
     * THE TAP-TO-CAST BAR STAYS IN A TOP-SCREEN FIGHT (the 11-22 shots: Show in combat Off, the default, slid it away under the room
     * bar while the band left too - the bottom screen had no fight control at all): Top screen keeps it (STAY) - Party mode's fight too
     * (the 1519 audit: ruling C sends the band up there as well) - Grow / Same size keep the player's setting. The solver then lays it on the page in the fight, in its dock.
     */
    @Test fun theTapToCastBarStaysInATopFight() {
        val top = HomeLayout(fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.UNDER_CHAT)
        assertTrue(TopCombatBar.stripStays(top))
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)) assertFalse(TopCombatBar.stripStays(top.copy(fight = f)))
        assertEquals(CastStripModel.Combat.STAY, TopCombatBar.stripCombat(top, CastStripModel.Combat.HIDE))
        assertEquals(CastStripModel.Combat.HIDE, TopCombatBar.stripCombat(top.copy(fight = HomeLayout.FightBand.GROW), CastStripModel.Combat.HIDE))
        assertEquals(CastStripModel.Combat.STAY, TopCombatBar.stripCombat(top.copy(fight = HomeLayout.FightBand.GROW), CastStripModel.Combat.STAY))
        // Party mode's Top fight (ruling C: the band away there too): Show in combat Off and Bar in Party mode Off keep it all the same
        assertFalse("Party mode's Top fight: kept", CastStripModel.hiddenIn(TopCombatBar.stripCombat(top, CastStripModel.Combat.HIDE),
            fighting = true, partyOn = true, partyShow = false))
        // the fight's page with the bar kept (castHidden false): every dock on the page, under its column(s), nothing below the frame
        val st = HomeLayoutSolver.State()
        for (cast in HomeLayout.CastDock.values()) {
            if (cast == HomeLayout.CastDock.OFF) continue
            val l = top.copy(cast = cast)
            val hidden = CastStripModel.hiddenIn(TopCombatBar.stripCombat(l, CastStripModel.Combat.HIDE), fighting = true)
            assertFalse("$cast: not hidden in the Top fight", hidden)
            val r = HomeLayoutSolver.places(l, HomeLayoutSolver.State(takeover = true, castHidden = hidden))
            assertTrue(r.away); assertFalse(r.castHidden)
            val b = r.boxes.getValue(HomePart.STRIP)
            assertTrue("$cast: on the page (${b.t}..${b.b} of ${st.h})", b.t >= 0f && b.b <= st.h + 0.01f)
            assertTrue("$cast: ${HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), r)}",
                HomeLayoutRules.problems(l, HomeLayoutSolver.State(takeover = true), r).isEmpty())
            if (cast.top) assertEquals("$cast: at the top of its grown column (the top gap)", 8f, b.t, 0.01f)
        }
    }
}
