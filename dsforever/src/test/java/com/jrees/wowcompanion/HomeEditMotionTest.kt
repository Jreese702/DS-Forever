package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * THE iPHONE-STYLE EDIT MODE'S MOTION (HomeEditMotion; the user, 2026-11-21: "resemble more of like an iPhone app edit mode with apps
 * kind of animating zoomed smaller a bit and being more animated generally"): the closed-form springs against a numeric integration,
 * each spring's overshoot, the glides' pace, retargeting without a jump, the wiggle's phases and its corners on every Home layout, the
 * lift / drop, the system's animator scale (0 = still, 2 = half speed), a fight's still(), the map's crop composed with a pose and the
 * open's ripple. Pure.
 */
class HomeEditMotionTest {

    /** RK4 of x'' = -2 zeta w x' - w^2 (x - to) */
    private fun rk4(from: Float, to: Float, v0: Float, z: Float, w: Float, t: Float, steps: Int = 4000): Float {
        var x = from.toDouble(); var v = v0.toDouble()
        val h = t.toDouble() / steps
        fun a(x: Double, v: Double) = -2.0 * z * w * v - w.toDouble() * w * (x - to)
        repeat(steps) {
            val k1x = v; val k1v = a(x, v)
            val k2x = v + h / 2 * k1v; val k2v = a(x + h / 2 * k1x, v + h / 2 * k1v)
            val k3x = v + h / 2 * k2v; val k3v = a(x + h / 2 * k2x, v + h / 2 * k2v)
            val k4x = v + h * k3v; val k4v = a(x + h * k3x, v + h * k3v)
            x += h / 6 * (k1x + 2 * k2x + 2 * k3x + k4x); v += h / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
        }
        return x.toFloat()
    }

    @Test fun springsMatchANumericIntegration() {
        val cases = listOf(Motion.ENTER_ZETA to Motion.ENTER_W, Motion.LIFT_ZETA to Motion.LIFT_W, Motion.DROP_ZETA to Motion.DROP_W,
            Motion.CURSOR_ZETA to Motion.CURSOR_W, Motion.GLIDE_ZETA to 20f, 1f to 18f)
        for ((z, w) in cases) for (t in listOf(0.016f, 0.05f, 0.12f, 0.3f)) for (v0 in listOf(0f, 900f, -400f)) {
            val x = HomeEditMotion.springAt(0f, 100f, v0, z, w, t)
            assertEquals("zeta $z w $w t $t v0 $v0", rk4(0f, 100f, v0, z, w, t), x, 0.1f)
        }
        val out = FloatArray(2)
        HomeEditMotion.springAt(10f, 50f, 120f, 0.7f, 20f, 0f, out)
        assertEquals(10f, out[0], 1e-4f); assertEquals("its speed at 0 is v0", 120f, out[1], 1e-3f)
    }

    /** a spring's extreme over its run, from [from] to [to] */
    private fun extreme(from: Float, to: Float, z: Float, w: Float, min: Boolean): Float {
        var e = from
        var t = 0f
        while (t < 1f) { val x = HomeEditMotion.springAt(from, to, 0f, z, w, t); e = if (min) minOf(e, x) else maxOf(e, x); t += 0.001f }
        return e
    }

    @Test fun eachSpringOvershootsAsDesigned() {
        val ratio = Motion.LIFT / Motion.REST_SCALE
        // the shrink: a hair past REST (~0.2 %), never a visible bounce
        val enter = extreme(1f, Motion.REST_SCALE, Motion.ENTER_ZETA, Motion.ENTER_W, min = true)
        assertTrue("enter dips just under 0.94: $enter", enter < Motion.REST_SCALE && enter > Motion.REST_SCALE - 0.004f)
        // the pick-up peaks ~1.048
        val lift = extreme(1f, ratio, Motion.LIFT_ZETA, Motion.LIFT_W, min = false) * Motion.REST_SCALE
        assertTrue("lift peaks ~1.048: $lift", lift in 1.044f..1.052f)
        // the drop's plop: ~0.93, then 0.94
        val drop = extreme(ratio, 1f, Motion.DROP_ZETA, Motion.DROP_W, min = true) * Motion.REST_SCALE
        assertTrue("drop plops ~0.93: $drop", drop in 0.925f..0.936f)
        // a glide's centre: at most 1.2 % past
        val glide = extreme(0f, 100f, Motion.GLIDE_ZETA, HomeEditMotion.omegaFor(Motion.MODULE_OPEN), min = false)
        assertTrue("glide overshoot <= 1.2 %: $glide", glide > 100f && glide <= 101.2f)
    }

    @Test fun glidesKeepTheOldPaceAndLandExactly() {
        // 95 % there at the old Relay's ms (critically damped, the omega the glides take)
        for (ms in listOf(Motion.MODULE_OPEN, Motion.MODULE_CLOSE, HomeEditModel.EDIT_STEP)) {
            val x = HomeEditMotion.springAt(0f, 1f, 0f, 1f, HomeEditMotion.omegaFor(ms), ms / 1000f)
            assertEquals("95 % at $ms", 0.95f, x, 0.002f)
            // the animator's length: past it the glide is within 1.5 px of a 600 px move (its last frame lands exactly)
            val end = HomeEditMotion.glideMs(ms) / 1000f
            assertTrue("glideMs($ms) = ${HomeEditMotion.glideMs(ms)}", HomeEditMotion.glideMs(ms) in (ms + 60)..(ms * 2))
            val out = FloatArray(2)
            HomeEditMotion.glide(0f, 600f, 0f, ms, end, false, out)
            assertEquals(600f, out[0], 1.5f)
        }
        // the size never passes its target (nor its start)
        val out = FloatArray(2)
        var t = 0f
        while (t < 0.5f) {
            HomeEditMotion.glide(400f, 800f, 3000f, Motion.MODULE_OPEN, t, true, out)
            assertTrue("size in [400, 800] at $t: ${out[0]}", out[0] in 400f..800f)
            t += 0.004f
        }
        // a spring lands exactly at its target once settled
        val m = HomeEditMotion()
        m.enter(0L)
        val p = HomeEditMotion.Pose()
        assertEquals(Motion.REST_SCALE, m.pose(EditStop.CHAT.ordinal, 2000L, 400f, p).scale, 0f)
    }

    @Test fun retargetKeepsPositionAndSpeed() {
        val m = HomeEditMotion()
        val s = m.Spring(0f, 0.25f)
        s.jump(0f, 600f, 0f, 0L, Motion.GLIDE_ZETA, 20f)
        val x = s.value(80L); val v = s.velocity(80L)
        s.set(0f, 80L, Motion.GLIDE_ZETA, 20f)                            // the D-pad pressed back mid-way
        assertEquals("no jump", x, s.value(80L), 1e-3f)
        assertEquals("its speed carried", v, s.velocity(80L), 1e-2f)
        assertTrue("still going out before it turns", s.value(96L) > x || v <= 0f)
        assertTrue(s.done(5000L)); assertEquals(0f, s.value(5000L), 0f)
    }

    // ---------------------------------------------------------------- the wiggle

    @Test fun eachWidgetWigglesOnItsOwnPhase() {
        val keys = (0 until 11) + (0 until 12).map { HomeEditMotion.roomKey(it) }
        for (a in keys) for (b in keys) if (a < b) {
            val d = abs(((HomeEditMotion.phase(a) - HomeEditMotion.phase(b)) % (2f * PI.toFloat()) + 2f * PI.toFloat()) % (2f * PI.toFloat()))
            val gap = minOf(d, 2f * PI.toFloat() - d)
            assertTrue("keys $a and $b in phase ($gap)", gap > 0.05f)
        }
        for (k in keys) assertTrue("${HomeEditMotion.hz(k)} Hz", HomeEditMotion.hz(k) in Motion.WIGGLE_HZ * 0.94f..Motion.WIGGLE_HZ * 1.06f)
    }

    /** 1518 feel pass (the user, 2026-11-22: "a bit too fast and jarring especially on the bigger things"): a small picture wiggles at
     *  about 4.5 Hz, a big module at about 2.2 Hz and turns under half a degree - its corners within 4.5 px */
    @Test fun theWiggleIsCalmerOnTheBiggerThings() {
        val keys = (0 until 11) + (0 until 12).map { HomeEditMotion.roomKey(it) }
        for (k in keys) {
            val small = HomeEditMotion.hzFor(k, 50f); val big = HomeEditMotion.hzFor(k, 460f)
            assertTrue("$small Hz", small in Motion.WIGGLE_HZ * 0.94f..Motion.WIGGLE_HZ * 1.06f)
            assertTrue("$big Hz", big in Motion.WIGGLE_HZ_BIG * 0.94f..Motion.WIGGLE_HZ_BIG * 1.06f)
            assertTrue("a big module is the slower: $big < $small", big < small * 0.6f)
            assertTrue("no faster than before the feel pass", small <= 5f)
        }
        assertTrue(Motion.WIGGLE_DEG_BIG < 0.5f)
        // a half-width chat column ~608 x 700: its corners move under 4 px
        val hd = 0.5f * hypot(608f, 700f)
        assertTrue(hd * HomeEditMotion.amp(hd) * PI.toFloat() / 180f < 4f)
    }

    /**
     * the user's pick 2026-11-21, "Noticeable": a big module (the chat, the map, the party module, the card) turns about 0.8 deg, its
     * corners ~6-8 px (the chat's and the map's columns); a room's picture stays at 1.2 deg; a size between turns between; no corner
     * past WIGGLE_PX (8 px, was 3) on any layout, any part
     */
    @Test fun theWiggleIsNoticeableOnEveryLayout() {
        val layouts = HomeArrangements.on(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)).map { it.second } +
            HomeArrangements.on(HomeLayout(cast = HomeLayout.CastDock.ABOVE_MAP)).map { it.second } +
            HomeLayout(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.THIRD) +
            HomeLayout(chatShare = HomeLayout.Share.TWO_THIRDS, cast = HomeLayout.CastDock.UNDER_MAP)
        val big = setOf(HomePart.CHAT, HomePart.MAP, HomePart.PARTY, HomePart.QUESTS)
        var columns = 0
        for (l in layouts) for (party in listOf(false, true)) {
            val st = HomeLayoutSolver.State(card = true, cardH = 118f, partyOn = party,
                party = if (party) HomeLayoutSolver.Party(PartyLayout.Mode.REST, 3, false) else null)
            val boxes = HomeLayoutSolver.places(HomeLayoutRules.shown(l), st).boxes
            for ((p, b) in boxes) {
                val hd = 0.5f * hypot(b.r - b.l, b.b - b.t)
                val deg = HomeEditMotion.amp(hd)
                val corner = hd * deg * PI.toFloat() / 180f
                assertTrue("$p on ${l.name}: $corner px", corner <= Motion.WIGGLE_PX + 1e-3f)
                assertTrue("$p on ${l.name}: $deg deg", deg in (Motion.WIGGLE_DEG_BIG - 0.06f)..Motion.WIGGLE_DEG)
                if (p in big) assertEquals("$p on ${l.name} (half-diagonal $hd) turns ~${Motion.WIGGLE_DEG_BIG} deg", Motion.WIGGLE_DEG_BIG, deg, 0.06f)
                // the chat's and the map's columns (a module's own column, not the full-width chat): corners ~5.5-8 px at a half, a
                // third's narrower column a little less at the same 0.8 deg
                if ((p == HomePart.CHAT || p == HomePart.MAP) && b.r - b.l < 900f && b.b - b.t > 500f) {
                    columns++
                    val half = kotlin.math.abs((b.r - b.l) - 608f) < 20f
                    assertTrue("$p on ${l.name}: its corners $corner px", corner in (if (half) 3.2f else 2.6f)..Motion.WIGGLE_PX + 1e-3f)
                }
            }
        }
        assertTrue("the columns were checked: $columns", columns > 20)
        assertEquals("a room's small picture stays at 1.2 deg", Motion.WIGGLE_DEG, HomeEditMotion.amp(40f), 0f)
        assertEquals(Motion.WIGGLE_DEG, HomeEditMotion.amp(12f), 0f)
        // a chat column ~600 x 700: 0.8 deg, ~6.4 px at its corners
        assertEquals(Motion.WIGGLE_DEG_BIG, HomeEditMotion.amp(0.5f * hypot(600f, 700f)), 1e-4f)
        // between: a smaller widget turns between, the smaller the more
        var was = HomeEditMotion.amp(40f)
        for (hd in 41..Motion.WIGGLE_BIG.toInt()) {
            val a = HomeEditMotion.amp(hd.toFloat())
            assertTrue("$hd px: $a after $was", a < was && a > Motion.WIGGLE_DEG_BIG - 1e-4f)
            was = a
        }
        assertEquals((Motion.WIGGLE_DEG + Motion.WIGGLE_DEG_BIG) / 2f, HomeEditMotion.amp(100f), 1e-4f)
        // past the corners' budget (the band across the page): a hair under 0.8, its corner at 8 px
        val band = 0.5f * hypot(1240f, 226f)
        assertEquals(Motion.WIGGLE_PX, band * HomeEditMotion.amp(band) * PI.toFloat() / 180f, 1e-3f)
        assertTrue(HomeEditMotion.amp(band) in 0.35f..Motion.WIGGLE_DEG_BIG)
    }

    @Test fun theWiggleFadesInAfterTheOpenAndOutOnTheLift() {
        val m = HomeEditMotion()
        val p = HomeEditMotion.Pose()
        val chat = EditStop.CHAT.ordinal; val map = EditStop.MAP.ordinal
        m.enter(1000L)
        assertEquals("still at the open's first frame", 0f, m.pose(chat, 1000L, 40f, p).rot, 0f)
        var most = 0f
        for (t in 1400L..1600L) most = maxOf(most, abs(m.pose(chat, t, 40f, p).rot))
        assertTrue("wiggling fully after it: $most", most > Motion.WIGGLE_DEG * 0.9f && most <= Motion.WIGGLE_DEG + 1e-4f)
        m.lift(map, 2000L)
        for (t in 2100L..2300L step 7) assertEquals("the lifted widget is still", 0f, m.pose(map, t, 40f, p).rot, 0f)
        assertTrue("the others go on", (2100L..2300L).any { abs(m.pose(chat, it, 40f, p).rot) > 0.5f })
        assertTrue(m.liftK(map, 2300L) > 0.99f)
        // a menu calms the rest to 40 %
        m.modal(true, 3000L)
        var calm = 0f
        for (t in 3200L..3400L) calm = maxOf(calm, abs(m.pose(chat, t, 40f, p).rot))
        assertTrue("calm while reading: $calm", calm <= Motion.WIGGLE_DEG * Motion.WIGGLE_CALM + 1e-3f && calm > 0.3f)
        // the menu closed: back in full
        m.modal(false, 3500L)
        var full = 0f
        for (t in 3700L..3900L) full = maxOf(full, abs(m.pose(chat, t, 40f, p).rot))
        assertTrue("back in full: $full", full > Motion.WIGGLE_DEG * 0.9f)
    }

    /**
     * the user's pick 2026-11-21, "Never rest": no idle rest - with no input at all the wiggle goes on at its full turn the whole time
     * edit mode is open (15 s, a minute, ten minutes) and the ticker keeps running; it still stops in a fight (still), with the animations
     * off (scale 0) and once the leave has landed; the calm under a menu stays
     */
    @Test fun theWiggleNeverRestsWhileEditModeIsOpen() {
        val m = HomeEditMotion()
        val p = HomeEditMotion.Pose()
        val chat = EditStop.CHAT.ordinal; val room = HomeEditMotion.roomKey(3)
        m.enter(0L)
        for (at in listOf(14_000L, 15_500L, 16_000L, 30_000L, 60_000L, 600_000L)) {
            var most = 0f; var room1 = 0f
            for (t in at..at + 500L) {   // 1518 feel pass: a big module's calmer pace (2.2 Hz) needs a longer look for its full turn
                most = maxOf(most, abs(m.pose(chat, t, 460f, p).rot))
                room1 = maxOf(room1, abs(m.pose(room, t, 40f, p).rot))
            }
            assertEquals("the chat at full turn at $at ms with no input", Motion.WIGGLE_DEG_BIG, most, 0.03f)
            assertEquals("a room at full turn at $at ms", Motion.WIGGLE_DEG, room1, 0.03f)
            assertTrue("the ticker runs at $at ms", m.busy(at) && m.wiggling(at))
        }
        // the calm under a menu stays (40 %), and it never rests under it either
        m.modal(true, 700_000L)
        var calm = 0f
        for (t in 760_000L..760_500L) calm = maxOf(calm, abs(m.pose(chat, t, 460f, p).rot))
        assertEquals(Motion.WIGGLE_DEG_BIG * Motion.WIGGLE_CALM, calm, 0.02f)
        assertTrue(m.busy(760_200L))
        // a fight: still at once, nothing to tick
        m.still()
        assertFalse(m.busy(760_300L)); assertFalse(m.wiggling(760_300L))
        assertTrue(m.pose(chat, 760_300L, 460f, p).isIdentity)
        // the leave: the ticker stops once it has landed
        val n = HomeEditMotion()
        n.enter(0L)
        assertTrue(n.busy(40_000L))
        n.leave(40_000L)
        assertFalse("landed", n.busy(42_000L)); assertFalse(n.on)
        // the animations off: the static shrink, no ticker, however long
        val o = HomeEditMotion().apply { scale = 0f }
        o.enter(0L)
        for (t in listOf(0L, 20_000L, 120_000L)) {
            assertFalse(o.busy(t)); assertEquals(0f, o.pose(chat, t, 460f, p).rot, 0f)
            assertEquals(Motion.REST_SCALE, p.scale, 0f)
        }
    }

    /**
     * the user's pick 2026-11-21 (the shot pass: the lifted full-height map at 1.04 grew past Home's foot, its bottom edge under the room
     * bar and the ribbon): keepIn shifts a grown / turning box back inside the frame, steady over the wiggle's cycle, never pulling a box
     * laid past the frame further in, centring one too big for it
     */
    @Test fun keepInHoldsTheGrownBoxInsideTheFrame() {
        val fr = 1240f; val fb = 935f
        val p = HomeEditMotion.Pose()
        // Classic's map column (band's foot 226 to the frame's foot), lifted at 1.04: 28 px past the foot before, inside after
        val l = 624f; val t = 234f; val r = 1232f; val b = 935f
        p.identity(); p.scale = Motion.LIFT
        HomeEditMotion.keepIn(l, t, r, b, 0f, 0f, fr, fb, p)
        val hw = (r - l) / 2f * p.scale; val hh = (b - t) / 2f * p.scale
        assertTrue("its foot inside: ${(t + b) / 2f + p.dy + hh}", (t + b) / 2f + p.dy + hh <= fb + 1e-3f)
        assertTrue("its top inside", (t + b) / 2f + p.dy - hh >= 0f)
        assertTrue("its right edge inside", (l + r) / 2f + p.dx + hw <= fr + 1e-3f)
        assertEquals("up by its growth", -(b - t) * (Motion.LIFT - 1f) / 2f, p.dy, 0.01f)
        // the lift's overshoot (~1.048) too
        p.identity(); p.scale = 1.05f
        HomeEditMotion.keepIn(l, t, r, b, 0f, 0f, fr, fb, p)
        assertTrue((t + b) / 2f + p.dy + (b - t) / 2f * 1.05f <= fb + 1e-3f)
        // at rest (0.94) no shift; a wiggle's turn at the foot: the same shift over the whole cycle (its reach, not its turn now)
        p.identity(); p.scale = Motion.REST_SCALE
        HomeEditMotion.keepIn(l, t, r, b, 0f, 0f, fr, fb, p)
        assertEquals(0f, p.dx, 0f); assertEquals(0f, p.dy, 0f)
        val m = HomeEditMotion()
        m.enter(0L)
        val bar = floatArrayOf(8f, 845f, 616f, 935f)                     // a bar along the frame's foot, 608 x 90
        val hd = 0.5f * hypot(bar[2] - bar[0], bar[3] - bar[1])
        val shifts = HashSet<Int>()
        for (tt in 2000L..2400L step 8L) {
            m.pose(EditStop.CAST.ordinal, tt, hd, p)
            val dy0 = p.dy
            HomeEditMotion.keepIn(bar[0], bar[1], bar[2], bar[3], 0f, 0f, fr, fb, p)
            shifts += Math.round((p.dy - dy0) * 100f)
            // every corner of the turned box inside the frame
            val a = Math.toRadians(p.rot.toDouble()); val co = cos(a).toFloat(); val si = sin(a).toFloat()
            val cx = (bar[0] + bar[2]) / 2f + p.dx; val cy = (bar[1] + bar[3]) / 2f + p.dy
            for ((x, y) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val px = x * (bar[2] - bar[0]) / 2f * p.scale; val py = y * (bar[3] - bar[1]) / 2f * p.scale
                val yy = cy + px * si + py * co
                assertTrue("a corner at $tt: $yy", yy <= fb + 1e-2f)
                assertTrue(cx + px * co - py * si in -1e-2f..fr + 1e-2f)
            }
        }
        assertEquals("one steady shift over the cycle: $shifts", 1, shifts.size)
        // a bar laid past the frame (slid below it) is left where it lies
        p.identity(); p.scale = Motion.LIFT
        HomeEditMotion.keepIn(8f, 940f, 616f, 1030f, 0f, 0f, fr, fb, p)
        assertEquals(0f, p.dy, 0.01f)
        // one too big for the frame: drawn no bigger than the frame, centred in it
        p.identity(); p.scale = 1.2f
        HomeEditMotion.keepIn(0f, 0f, fr, fb, 0f, 0f, fr, fb, p)
        assertEquals(0f, p.dx, 1e-3f); assertEquals(0f, p.dy, 1e-3f); assertEquals(1f, p.scale, 1e-4f)
        // the pose's own offsets (the drop's slide) are not clamped: added on top
        p.identity(); p.scale = Motion.LIFT; p.dy = 50f
        HomeEditMotion.keepIn(l, t, r, b, 0f, 0f, fr, fb, p)
        assertEquals(50f - (b - t) * (Motion.LIFT - 1f) / 2f, p.dy, 0.01f)
    }

    /**
     * review round 3 (pick 4 on the full-width bar): the tap-to-cast bar across the page (ACROSS / SPLIT: 8 .. 1232) lifted at 1.04 was
     * 1273 px wide, centred, its ends 16.5 px past both page edges. Now its drawn scale is capped to what the frame holds (steady: its
     * box and its swing only), so every corner is inside; a box with room to grow keeps its 1.04
     */
    @Test fun keepInCapsABoxAsWideAsTheFrame() {
        val fr = 1240f; val fb = 935f
        val p = HomeEditMotion.Pose()
        val l = 8f; val t = 845f; val r = 1232f; val b = 935f
        for (s in listOf(Motion.LIFT, 1.05f, 1.02f)) {
            p.identity(); p.scale = s
            HomeEditMotion.keepIn(l, t, r, b, 0f, 0f, fr, fb, p)
            val hw = (r - l) / 2f * p.scale
            assertTrue("capped at $s: ${p.scale}", p.scale < s && p.scale <= fr / (r - l) + 1e-4f)
            assertTrue("its ends inside at $s", (l + r) / 2f + p.dx - hw >= -1e-2f && (l + r) / 2f + p.dx + hw <= fr + 1e-2f)
            assertTrue("its foot inside at $s", (t + b) / 2f + p.dy + (b - t) / 2f * p.scale <= fb + 1e-2f)
        }
        // the cap is steady while it wiggles in (its reach, not its turn now): one scale over a cycle, every corner inside
        val m = HomeEditMotion()
        m.enter(0L)
        val hd = 0.5f * hypot(r - l, b - t)
        val scales = HashSet<Int>()
        for (tt in 2000L..2400L step 8L) {
            m.pose(EditStop.CAST.ordinal, tt, hd, p)
            p.scale = Motion.LIFT                                           // as lifted, the wiggle still at full reach (the worst case)
            HomeEditMotion.keepIn(l, t, r, b, 0f, 0f, fr, fb, p)
            scales += Math.round(p.scale * 1e5f)
            val a = Math.toRadians(p.rot.toDouble()); val co = cos(a).toFloat(); val si = sin(a).toFloat()
            val cx = (l + r) / 2f + p.dx; val cy = (t + b) / 2f + p.dy
            for ((x, y) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val px = x * (r - l) / 2f * p.scale; val py = y * (b - t) / 2f * p.scale
                assertTrue("a corner at $tt", cx + px * co - py * si in -1e-2f..fr + 1e-2f)
                assertTrue("a corner at $tt", cy + px * si + py * co in -1e-2f..fb + 1e-2f)
            }
        }
        assertEquals("one steady cap over the cycle: $scales", 1, scales.size)
        // at rest (0.94) the bar is not capped; a half-width box lifted keeps its 1.04
        p.identity(); p.scale = Motion.REST_SCALE
        HomeEditMotion.keepIn(l, t, r, b, 0f, 0f, fr, fb, p)
        assertEquals(Motion.REST_SCALE, p.scale, 0f)
        p.identity(); p.scale = Motion.LIFT
        HomeEditMotion.keepIn(624f, 234f, 1232f, 935f, 0f, 0f, fr, fb, p)
        assertEquals(Motion.LIFT, p.scale, 0f)
        // a bar slid off the page (past the frame on x) is left alone on that axis
        p.identity(); p.scale = Motion.LIFT
        HomeEditMotion.keepIn(-1300f, t, -76f, b, 0f, 0f, fr, fb, p)
        assertEquals(Motion.LIFT, p.scale, 0f); assertEquals(0f, p.dx, 0f)
    }

    // ---------------------------------------------------------------- the lift and the drop

    @Test fun theLiftGrowsAndTheDropPlopsIntoItsDock() {
        val m = HomeEditMotion()
        val p = HomeEditMotion.Pose()
        val map = EditStop.MAP.ordinal
        m.enter(0L)
        m.lift(map, 1000L)
        var peak = 0f
        for (t in 1000L..1400L) peak = maxOf(peak, m.pose(map, t, 400f, p).scale)
        assertTrue("lift peak $peak", peak in 1.044f..1.052f)
        assertEquals(Motion.LIFT, m.pose(map, 2000L, 400f, p).scale, 1e-4f)
        // a finger's drop 120 px left of the dock: it slides in a little past the dock, and plops below its rest
        m.drop(map, -120f, 30f, 3000L)
        var low = 2f; var past = -1f
        for (t in 3000L..3600L) { m.pose(map, t, 400f, p); low = minOf(low, p.scale); past = maxOf(past, p.dx) }
        assertTrue("the plop $low", low in 0.925f..0.936f)
        assertTrue("about 2 % past the dock: $past", past > 0f && past < 120f * 0.05f)
        m.pose(map, 5000L, 400f, p)
        assertEquals(Motion.REST_SCALE, p.scale, 1e-4f); assertEquals(0f, p.dx, 0f)
        assertFalse(m.settling(map, 5000L))
        assertEquals(0f, m.liftK(map, 5000L), 0f)
    }

    // ---------------------------------------------------------------- the system's animator scale, a fight

    @Test fun scaleZeroIsStillAndScaleTwoIsHalfSpeed() {
        val m = HomeEditMotion().apply { scale = 0f }
        val p = HomeEditMotion.Pose()
        m.enter(0L)
        for (t in listOf(0L, 16L, 120L, 400L, 1077L, 9000L)) for (k in 0 until 11) {
            m.pose(k, t, 300f, p)
            assertEquals("the static shrink at $t", Motion.REST_SCALE, p.scale, 0f)
            assertEquals("no turn", 0f, p.rot, 0f); assertEquals(0f, p.dy, 0f)
        }
        assertFalse("nothing to tick", m.busy(0L))
        m.lift(EditStop.CHAT.ordinal, 50L)
        assertEquals(Motion.LIFT, m.pose(EditStop.CHAT.ordinal, 50L, 300f, p).scale, 1e-5f)
        assertEquals(1f, m.popK(HomeEditMotion.MENU, 50L).also { m.pop(HomeEditMotion.MENU, 50L) }, 0f)
        assertEquals("a pop lands at once", 1f, m.popK(HomeEditMotion.MENU, 50L), 0f)
        // scale 2: every value at 2t is scale 1's at t
        val a = HomeEditMotion(); val b = HomeEditMotion().apply { scale = 2f }
        a.enter(0L); b.enter(0L); a.lift(2, 500L); b.lift(2, 1000L)
        val pa = HomeEditMotion.Pose(); val pb = HomeEditMotion.Pose()
        for (t in listOf(0L, 40L, 120L, 260L, 700L, 900L, 1300L)) for (k in listOf(0, 2, 3, HomeEditMotion.roomKey(4))) {
            a.pose(k, t, 300f, pa); b.pose(k, 2 * t, 300f, pb)
            assertEquals("scale @ $t key $k", pa.scale, pb.scale, 1e-4f)
            assertEquals("turn @ $t key $k", pa.rot, pb.rot, 1e-3f)
        }
    }

    @Test fun stillIsIdentityAtOnce() {
        val m = HomeEditMotion()
        val p = HomeEditMotion.Pose()
        m.enter(0L); m.lift(EditStop.MAP.ordinal, 100L); m.pop(HomeEditMotion.MENU, 120L); m.cursorFrom(40f, 0f, 40f, 0f, 120L)
        m.still()
        for (k in 0 until HomeEditMotion.KEYS) assertTrue("key $k", m.pose(k, 130L, 300f, p).isIdentity)
        assertFalse(m.on); assertFalse(m.busy(130L))
        val c = FloatArray(4) { 9f }; m.cursorOffset(130L, c); assertTrue(c.all { it == 0f })
        assertEquals(0f, m.liftK(EditStop.MAP.ordinal, 130L), 0f)
    }

    @Test fun theLeaveGrowsBackAndEnds() {
        val m = HomeEditMotion()
        val p = HomeEditMotion.Pose()
        m.enter(0L)
        assertEquals(0f, m.popK(HomeEditMotion.RIBBON, 0L), 1e-4f)
        assertTrue("the ribbon slides up", m.popK(HomeEditMotion.RIBBON, 400L) > 0.99f)
        m.leave(1000L)
        assertTrue(m.on)
        assertTrue(m.busy(1100L))
        assertFalse("landed", m.busy(3000L))
        assertFalse("then off: identity", m.on)
        assertTrue(m.pose(EditStop.CHAT.ordinal, 3000L, 300f, p).isIdentity)
    }

    @Test fun theCursorFliesFromItsLastBox() {
        val m = HomeEditMotion()
        m.enter(0L)
        val c = FloatArray(4)
        m.cursorFrom(-600f, 0f, -600f, 0f, 100L)
        m.cursorOffset(100L, c); assertEquals(-600f, c[0], 1e-3f)
        m.cursorOffset(160L, c); assertTrue("on its way: ${c[0]}", c[0] > -600f && c[0] < 0f)
        m.cursorOffset(400L, c); assertEquals(0f, c[0], 0.5f)
        // a second press mid-flight goes on from where the box is drawn, at its speed
        m.cursorFrom(-600f, 0f, -600f, 0f, 500L)
        m.cursorOffset(540L, c); val x = c[0]
        m.cursorFrom(x + 300f, 0f, x + 300f, 0f, 540L)
        m.cursorOffset(540L, c); assertEquals(x + 300f, c[0], 1e-3f)
        // review round 3: its turn (the old widget's drawn angle over the new one's) flies on the same spring: the old angle at its first
        // frame, the new widget's once landed
        m.cursorFrom(0f, 0f, 0f, 0f, 1000L, 1.4f)
        assertEquals(1.4f, m.cursorTurn(1000L), 1e-4f)
        val mid = m.cursorTurn(1060L)
        assertTrue("turning on its way: $mid", mid > 0f && mid < 1.4f)
        assertEquals(0f, m.cursorTurn(1400L), 0.01f)
        assertEquals("landed exactly", 0f, m.cursorTurn(3000L), 0f)
        // a second press mid-turn goes on from the turn drawn
        m.cursorFrom(0f, 0f, 0f, 0f, 4000L, -1.2f)
        val w = m.cursorTurn(4030L)
        m.cursorFrom(0f, 0f, 0f, 0f, 4030L, w + 0.5f)
        assertEquals(w + 0.5f, m.cursorTurn(4030L), 1e-4f)
        assertTrue("its flight keeps the ticker on", m.busy(4040L))
        m.still()
        assertEquals("still: no turn", 0f, m.cursorTurn(4040L), 0f)
    }

    // ---------------------------------------------------------------- the map's crop, the cascade, the model

    @Test fun theMapsPoseComposesWithItsGlideCrop() {
        // laid at 820 x 700 at (412, 230), scaled 0.7 about 0,0 and moved by (30, 12): the drawn box's centre
        val left = 412f; val top = 230f; val tx = 30f; val ty = 12f; val s = 0.7f
        val cx = left + tx + s * 410f; val cy = top + ty + s * 350f
        val out = FloatArray(4)
        HomeEditMotion.composeMapGlide(left, top, tx, ty, s, cx, cy, out)
        val e = 0.94f; val rot = 0.8f
        fun view(x: Float, y: Float): Pair<Float, Float> {              // View: pivot, scale s*e, turn, translation
            val sx = (x - out[0]) * s * e; val sy = (y - out[1]) * s * e
            val r = rot * PI.toFloat() / 180f
            return (left + out[0] + sx * cos(r) - sy * sin(r) + out[2]) to (top + out[1] + sx * sin(r) + sy * cos(r) + out[3])
        }
        fun expected(x: Float, y: Float): Pair<Float, Float> {          // the crop's own transform, then the pose about the drawn centre
            val px = left + tx + s * x - cx; val py = top + ty + s * y - cy
            val r = rot * PI.toFloat() / 180f
            return (cx + e * (px * cos(r) - py * sin(r))) to (cy + e * (px * sin(r) + py * cos(r)))
        }
        val c = view(out[0], out[1])
        assertEquals(cx, c.first, 1e-3f); assertEquals(cy, c.second, 1e-3f)
        for ((x, y) in listOf(0f to 0f, 820f to 0f, 300f to 650f)) {
            val a = view(x, y); val b = expected(x, y)
            assertEquals(b.first, a.first, 1e-2f); assertEquals(b.second, a.second, 1e-2f)
        }
    }

    @Test fun theOpenRippleIsDeterministic() {
        assertEquals(0L, HomeEditMotion.ripple(0f, 900f)); assertEquals(Motion.ENTER_RIPPLE, HomeEditMotion.ripple(900f, 900f))
        // the ripple's delays hold each key still until its turn
        val m = HomeEditMotion()
        val d = LongArray(HomeEditMotion.KEYS).also { it[EditStop.CHAT.ordinal] = 60L }
        m.enter(0L, d)
        val p = HomeEditMotion.Pose()
        assertEquals("the chat waits", 1f, m.pose(EditStop.CHAT.ordinal, 50L, 300f, p).scale, 0f)
        assertTrue("the map has started", m.pose(EditStop.MAP.ordinal, 50L, 300f, p).scale < 1f)
    }
}
