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
 * lift / drop, the system's animator scale (0 = still, 2 = half speed), a fight's still(), the map's crop composed with a pose, the flip's
 * cascade and the model's flip / sheet morphs. Pure.
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

    @Test fun noCornerTravelsPastThreePixelsOnAnyLayout() {
        val layouts = HomePresets.ALL.map { it.apply(HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)) } +
            HomeLayout(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.THIRD)
        for (l in layouts) {
            val boxes = HomeLayoutSolver.places(HomeLayoutRules.shown(l), HomeLayoutSolver.State(card = true, cardH = 118f)).boxes
            for ((p, b) in boxes) {
                val hd = 0.5f * hypot(b.r - b.l, b.b - b.t)
                val corner = hd * HomeEditMotion.amp(hd) * PI.toFloat() / 180f
                assertTrue("$p on ${l.name}: $corner px", corner <= Motion.WIGGLE_PX + 1e-3f)
            }
        }
        assertEquals("a room's small picture turns the most", Motion.WIGGLE_DEG, HomeEditMotion.amp(40f), 0f)
        // the big chat only a little (~0.4 deg)
        assertTrue(HomeEditMotion.amp(0.5f * hypot(600f, 700f)) in 0.3f..0.45f)
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
        // 15 s with no input: the wiggle rests, and the clock can stop
        assertTrue(m.busy(3400L))
        val late = 3000L + Motion.WIGGLE_IDLE + 1000L
        assertEquals(0f, m.pose(chat, late, 40f, p).rot, 0f)
        assertFalse("nothing moves: the ticker stops", m.busy(late))
        m.poke(late)
        assertTrue("a press wakes it", m.busy(late + 16L))
        // review round 2: it ramps back in from its rest (WIGGLE_IN), never a snap to full on the first frame
        var first = 0f
        for (t in late..late + 16L) first = maxOf(first, abs(m.pose(chat, t, 40f, p).rot))
        assertTrue("no snap on the wake's first frame: $first", first <= 0.05f)
        var back = 0f
        for (t in late + 300L..late + 500L) back = maxOf(back, abs(m.pose(chat, t, 40f, p).rot))
        assertTrue("back in full (the menu's calm) after it: $back", back > Motion.WIGGLE_DEG * Motion.WIGGLE_CALM * 0.9f)
        // a poke while it wiggles changes nothing
        val a = m.pose(chat, late + 600L, 40f, p).rot
        m.poke(late + 600L)
        assertEquals(a, m.pose(chat, late + 600L, 40f, p).rot, 0f)
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

    @Test fun theFlipCascadeAndTheOpenRippleAreDeterministic() {
        assertEquals(listOf(0L, 18L, 36L, 54L, 72L, 72L), (0..5).map { HomeEditMotion.stagger(it) })
        assertEquals(0L, HomeEditMotion.ripple(0f, 900f)); assertEquals(Motion.ENTER_RIPPLE, HomeEditMotion.ripple(900f, 900f))
        // the ripple's delays hold each key still until its turn
        val m = HomeEditMotion()
        val d = LongArray(HomeEditMotion.KEYS).also { it[EditStop.CHAT.ordinal] = 60L }
        m.enter(0L, d)
        val p = HomeEditMotion.Pose()
        assertEquals("the chat waits", 1f, m.pose(EditStop.CHAT.ordinal, 50L, 300f, p).scale, 0f)
        assertTrue("the map has started", m.pose(EditStop.MAP.ordinal, 50L, 300f, p).scale < 1f)
    }

    @Test fun flipsAndTheSheetSayTheirWay() {
        val flips = HomePresets.ALL.map { p -> HomeEditModel.Flip(p.id, p.available) { l -> p.apply(l) } }
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val m = HomeEditModel(classic, EditStop.CHIP, flips, HomePresets.nameOf(classic), true)
        val right = m.flip(1).filterIsInstance<HomeEditModel.Fx.Relay>()
        assertEquals(listOf(1), right.map { it.morph })
        val left = m.flip(-1).filterIsInstance<HomeEditModel.Fx.Relay>()
        assertEquals(listOf(-1), left.map { it.morph })
        // the sheet: a later preset is +1, an earlier one -1
        val n = HomeEditModel(classic, EditStop.CHIP, flips, HomePresets.nameOf(classic), true)
        assertEquals(listOf(1), n.sheetUse(2).filterIsInstance<HomeEditModel.Fx.Relay>().map { it.morph })
        assertEquals(listOf(-1), n.sheetUse(1).filterIsInstance<HomeEditModel.Fx.Relay>().map { it.morph })
        // anything else is no flip
        val o = HomeEditModel(classic, EditStop.CHAT, flips, HomePresets.nameOf(classic), true)
        val step = o.lift(EditStop.CHAT) + o.drop()
        assertTrue(step.filterIsInstance<HomeEditModel.Fx.Relay>().all { it.morph == 0 })
    }
}
