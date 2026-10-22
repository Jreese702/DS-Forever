package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

/**
 * THE LIVE AGGRO LAYER, app side (AggroDoc, LiveMobs, LivePlace, LiveDanger, AggroFit; AGGRO_DOC_CONTRACT_v1.md with its "Built: ag1"
 * fields): recorded-style records of every kind, the slots, the placement rules, absence and decay, the disc formula, the vote fit,
 * a route bending round a mob just seen and the forced replan. The samples are written to the contract (no capture from the Thor yet).
 */
class LiveAggroTest {
    private var now = 100_000L
    private lateinit var store: LiveMobs

    @Before fun setUp() { AggroFit.reset(); store = LiveMobs { now } }
    @After fun tearDown() { AggroFit.reset() }

    // ---------------------------------------------------------------- the samples (ag1)

    /** a moving pos doc carrying a full record: the player at (-612.4 N, -4251.0 W) facing 2.11, a Bristleback (3098) and a mob met in combat */
    private val POS_DOC = """{"type":"pos","map":{"mapID":1411,"x":0.52,"y":0.61,"wx":-612.4,"wy":-4251.0,"facing":2.11,"tr":[[1,2]],
        "ag":{"v":1,"q":7,"t":1234.5,"comb":0,"me":{"lv":6,"map":1411,"x":-612.4,"y":-4251.0,"f":2.11},
          "m":[{"s":3,"n":3098,"ct":1,"lv":7,"c":"n","r":2,"cb":0,"a":15,"b":20,"rk":"s","sx":-0.31,"br":2.40,"bs":"c","age":4.2},
               {"s":5,"lv":8,"c":"e","r":2,"cb":1,"om":2,"a":5,"b":8,"rk":"i","age":0.8},
               {"s":9,"n":3100,"lv":6,"c":"n","r":4,"cb":0,"a":28,"rk":"d","age":12.0}],
          "pl":[{"s":3,"n":3098,"ct":1,"lv":7,"plv":6,"lo":17,"hi":21,"t":1230.1}]}}}"""
    private val SAME_DOC = """{"type":"aggro","v":1,"q":7,"same":1,"t":1235.5}"""
    private val OFF_DOC = """{"type":"aggro","v":1,"off":"inst","t":1300.0}"""
    private val COV_DOC = """{"type":"aggrocov","v":1,"cv":{"maxd":"41","off":"1","ena":"1","vmax":"30"},
        "sp":[[403,30],[8042,20]],"it":[[34368,25],[28767,40]],"ci":[28,8,7,28]}"""
    private val ACK_DOC = """{"type":"aggroack","op":"pull","k":2,"s":-1,"ok":1}"""

    private fun mob(s: Int, n: Int?, lv: Int, a: Int, b: Int?, extra: String = "") =
        "{\"s\":$s" + (n?.let { ",\"n\":$it" } ?: "") + ",\"lv\":$lv,\"c\":\"n\",\"r\":2,\"a\":$a" + (b?.let { ",\"b\":$it" } ?: "") + ",\"rk\":\"s\"$extra}"
    private fun full(q: Int, x: Double, y: Double, mobs: List<String>, f: Double? = null, comb: Int = 0, lv: Int = 10) =
        AggroDoc.record(JSONObject("{\"type\":\"aggro\",\"v\":1,\"q\":$q,\"t\":${q * 0.2},\"comb\":$comb,\"me\":{\"lv\":$lv,\"map\":1411," +
            "\"x\":$x,\"y\":$y" + (f?.let { ",\"f\":$it" } ?: "") + "},\"m\":[${mobs.joinToString(",")}]}"))!!
    private fun spawn(key: Int, npc: Int, x: Double, y: Double, lv: Int = 10, elite: Boolean = false) =
        LivePlace.SpawnPt(key, npc, x, y, lv, lv, elite, true)

    // ---------------------------------------------------------------- parsing

    @Test fun aFullRecordRidingThePosDocIsReadFieldByFieldAndOmittedMeansUnknown() {
        val r = AggroDoc.fromPos(JSONObject(POS_DOC).getJSONObject("map")) as AggroDoc.Full
        assertEquals(7, r.q); assertFalse(r.comb)
        assertEquals(6, r.me.lv); assertEquals(1411, r.me.map); assertEquals(-612.4, r.me.x, 1e-9); assertEquals(2.11, r.me.f!!, 1e-9)
        assertEquals(listOf(3, 5, 9), r.m.map { it.s })
        val bristle = r.m[0]
        assertEquals(3098, bristle.n); assertEquals(1, bristle.ct); assertEquals(15.0, bristle.a!!, 0.0); assertEquals(20.0, bristle.b!!, 0.0)
        assertEquals(2.40, bristle.br!!, 1e-9); assertEquals("c", bristle.bs); assertNull("not on us: om omitted", bristle.om)
        assertTrue(bristle.aggressive); assertFalse(bristle.elite)
        val combatAdd = r.m[1]
        assertNull("first seen in combat: no npc id", combatAdd.n); assertTrue(combatAdd.elite); assertEquals(2, combatAdd.om)
        assertNull(combatAdd.br); assertNull(combatAdd.sx); assertNull(combatAdd.qi)
        val neutral = r.m[2]
        assertFalse("reaction 4: no circle", neutral.aggressive); assertNull("b omitted: beyond our largest check", neutral.b)
        assertEquals(1, r.pl.size); assertEquals(17.0, r.pl[0].lo, 0.0); assertEquals(6, r.pl[0].plv)
    }

    @Test fun sameOffCoverageAndAckRecordsAreRead() {
        val same = AggroDoc.record(JSONObject(SAME_DOC)) as AggroDoc.Same
        assertEquals(7, same.q)
        assertEquals("inst", (AggroDoc.record(JSONObject(OFF_DOC)) as AggroDoc.Off).why)
        val cov = AggroDoc.coverage(JSONObject(COV_DOC))!!
        assertEquals(41.0, cov.maxd!!, 0.0); assertEquals(true, cov.offscreen); assertEquals(true, cov.enemies)
        assertEquals(listOf(403 to 30.0, 8042 to 20.0), cov.sp); assertEquals(4, cov.ci.size)
        val ack = AggroDoc.ack(JSONObject(ACK_DOC))!!
        assertEquals("pull", ack.op); assertEquals(2, ack.k); assertEquals(-1, ack.s); assertTrue(ack.ok)
        // another version, or no position: not read
        assertNull(AggroDoc.record(JSONObject("""{"v":2,"q":1,"me":{"x":0,"y":0},"m":[]}""")))
        assertNull(AggroDoc.record(JSONObject("""{"v":1,"q":1,"m":[]}""")))
        // a slot out of 0..23, or a repeated slot: dropped
        val bad = AggroDoc.record(JSONObject("""{"v":1,"q":1,"me":{"x":0,"y":0},"m":[{"s":24,"r":2},{"s":2,"r":2},{"s":2,"r":3}]}""")) as AggroDoc.Full
        assertEquals(listOf(2), bad.m.map { it.s }); assertEquals(2, bad.m[0].r)
    }

    // ---------------------------------------------------------------- the store: slots, same, q reset, off

    @Test fun slotsKeepTheirTrackAndAReusedSlotIsANewPlate() {
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(3, 3098, 10, 15, 20, ",\"age\":1.0"))))
        val id = store.tracks().single().id
        now += 200
        store.onRecord(full(2, 1.0, 0.0, listOf(mob(3, 3098, 10, 15, 20, ",\"age\":1.2"))))
        assertEquals("the same plate", id, store.tracks().single().id)
        // the slot given to another creature: a new plate
        now += 200
        store.onRecord(full(3, 1.0, 0.0, listOf(mob(3, 3100, 10, 15, 20, ",\"age\":0.0"))))
        val id2 = store.tracks().single().id
        assertNotEquals(id, id2)
        // its age going back: freed and given again
        now += 200
        store.onRecord(full(4, 1.0, 0.0, listOf(mob(3, 3100, 10, 15, 20, ",\"age\":5.0"))))
        now += 200
        store.onRecord(full(5, 1.0, 0.0, listOf(mob(3, 3100, 10, 15, 20, ",\"age\":0.1"))))
        assertNotEquals(id2, store.tracks().single().id)
        // missing from m: gone; back later: a new track
        val id3 = store.tracks().single().id
        now += 200; store.onRecord(full(6, 1.0, 0.0, emptyList()))
        assertTrue(store.tracks().isEmpty())
        now += 200; store.onRecord(full(7, 1.0, 0.0, listOf(mob(3, 3100, 10, 15, 20))))
        assertNotEquals(id3, store.tracks().single().id)
    }

    @Test fun anNpcIdReadBeforeCombatStaysOnThePlate() {
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(4, 3098, 10, 10, 15))))
        now += 200
        store.onRecord(full(2, 0.0, 0.0, listOf(mob(4, null, 10, 5, 8, ",\"cb\":1,\"om\":2")), comb = 1))
        assertEquals(3098, store.tracks().single().mob.n)
    }

    @Test fun qGoingBackwardsMakesEverySlotNewAndSameKeepsTheLastFullSet() {
        store.onRecord(full(40, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 20), mob(1, 3098, 10, 25, 30))))
        val ids = store.tracks().map { it.id }
        now += 6_000
        // a same naming the last full keeps the set seen (not stale)
        store.onRecord(AggroDoc.record(JSONObject("""{"type":"aggro","v":1,"q":40,"same":1,"t":9}"""))!!)
        assertEquals(2, store.snapshot(1411, 10).mobs)
        assertEquals(ids, store.tracks().map { it.id })
        // a same naming another q: ignored (the set goes stale, its discs fade)
        now += 6_000
        store.onRecord(AggroDoc.record(JSONObject("""{"type":"aggro","v":1,"q":39,"same":1,"t":10}"""))!!)
        now += 2_000
        assertEquals("stale: counted no more", 0, store.snapshot(1411, 10).mobs)
        // a reload: q = 1
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 20), mob(1, 3098, 10, 25, 30))))
        assertTrue(store.tracks().map { it.id }.none { it in ids })
    }

    @Test fun anOffRecordClearsEverything() {
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 20))))
        assertFalse(store.snapshot(1411, 10).empty)
        store.onRecord(AggroDoc.record(JSONObject(OFF_DOC))!!)
        val s = store.snapshot(1411, 10)
        assertTrue(s.empty); assertEquals("inst", s.off); assertTrue(store.tracks().isEmpty())
        // back on with the next full record
        now += 1000
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 20))))
        assertNull(store.snapshot(1411, 10).off); assertFalse(store.snapshot(1411, 10).empty)
        // another map's record: none for this one
        assertTrue(store.snapshot(1412, 10).empty)
    }

    // ---------------------------------------------------------------- placement

    private fun me(x: Double = 0.0, y: Double = 0.0, f: Double? = null) = AggroDoc.Me(10, 1411, x, y, f)
    private fun parsed(json: String) = (AggroDoc.record(JSONObject("{\"v\":1,\"q\":1,\"me\":{\"x\":0,\"y\":0},\"m\":[$json]}")) as AggroDoc.Full).m[0]

    @Test fun theAddonsBearingAndTheBracketMakeASector() {
        // br 0 (north), bracket 15-20: the mob 17.5 yd north, the camera's yaw tight, the facing's looser
        val m = parsed(mob(0, 3098, 10, 15, 20, ",\"br\":0.0,\"bs\":\"c\""))
        val p = LivePlace.place(m, me(), emptyList(), emptyList(), emptySet(), moving = false, maxd = null)
        assertEquals(LivePlace.How.SECTOR, p.how)
        assertEquals(17.5, p.x, 1e-6); assertEquals(0.0, p.y, 1e-6)
        val camU = p.u
        assertTrue("bracket half 2.5 + 17.5 x 10 deg", camU in 3.0..4.0)
        val f = LivePlace.place(parsed(mob(0, 3098, 10, 15, 20, ",\"br\":1.5708,\"bs\":\"f\"")), me(), emptyList(), emptyList(), emptySet(), true, null)
        assertEquals("west", 17.5, f.y, 1e-3); assertTrue(f.u > camU)
        assertTrue(LiveDanger.trust(p) > LiveDanger.trust(f))
        // no br: sx through the FOV with the facing as the yaw, only while moving. sx +1 = the screen's right edge = 45 deg clockwise
        val sx = parsed(mob(0, 3098, 10, 15, 20, ",\"sx\":1.0"))
        val s = LivePlace.place(sx, me(f = 0.0), emptyList(), emptyList(), emptySet(), true, null)
        assertEquals(LivePlace.How.SECTOR, s.how)
        assertEquals(17.5 * Math.cos(Math.PI / 4), s.x, 1e-6); assertEquals("clockwise of north = east = -west", -17.5 * Math.sin(Math.PI / 4), s.y, 1e-6)
        assertEquals("standing: no sector from sx", LivePlace.How.RING, LivePlace.place(sx, me(f = 0.0), emptyList(), emptyList(), emptySet(), false, null).how)
    }

    @Test fun aSpawnInsideTheBracketRingIsSnappedToAndOneOutsideTheBearingIsNot() {
        val m = parsed(mob(0, 3098, 10, 15, 20))
        val north = spawn(1, 3098, 18.0, 0.0); val farAway = spawn(2, 3098, 60.0, 0.0)
        val p = LivePlace.place(m, me(), emptyList(), listOf(north, farAway), emptySet(), false, null)
        assertEquals(LivePlace.How.SPAWN, p.how); assertTrue(p.sure); assertEquals(1, p.spawn!!.key)
        assertEquals(LivePlace.SPAWN_U, p.u, 0.0)
        // with a bearing pointing south the northern spawn disagrees: the sector stands
        val south = parsed(mob(0, 3098, 10, 15, 20, ",\"br\":3.1416,\"bs\":\"c\""))
        val q = LivePlace.place(south, me(), emptyList(), listOf(north), emptySet(), false, null)
        assertEquals(LivePlace.How.SECTOR, q.how); assertTrue(q.x < 0)
        // taken by another plate: not snapped again
        assertEquals(LivePlace.How.RING, LivePlace.place(m, me(), emptyList(), listOf(north), setOf(1), false, null).how)
        // a plate first seen in combat (no npc id): counted, never snapped
        val anon = parsed(mob(0, null, 10, 15, 20))
        assertEquals(LivePlace.How.RING, LivePlace.place(anon, me(), emptyList(), listOf(north), emptySet(), false, null).how)
    }

    @Test fun ringsFromTheWalkPickOneOfTwoSpawnsInTheFirstRing() {
        // two Bristlebacks 17 yd from the start, north and south: the first ring cannot tell them apart (the unclear snap, wide margin)
        val n = spawn(1, 3098, 17.0, 0.0); val s = spawn(2, 3098, -17.0, 0.0)
        store.setSpawns(1411, listOf(n, s))
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 20))))
        val first = store.tracks().single().placed!!
        assertEquals(LivePlace.How.SPAWN, first.how); assertFalse(first.sure); assertEquals(LivePlace.AMBIG_U, first.u, 0.0)
        assertNull("an unclear snap keeps the static share", store.spawnState(1))
        // walked 12 yd north: the bracket closes to 5-8 (the north spawn), the south one would be 29 away
        now += 300; store.onRecord(full(2, 6.0, 0.0, listOf(mob(0, 3098, 10, 10, 15))))
        now += 300; store.onRecord(full(3, 12.0, 0.0, listOf(mob(0, 3098, 10, 5, 8))))
        val p = store.tracks().single().placed!!
        assertEquals(LivePlace.How.SPAWN, p.how); assertTrue(p.sure); assertEquals(1, p.spawn!!.key)
        assertEquals("confirmed", store.spawnState(1))
    }

    @Test fun ringsAloneFixAFreeMobWhenTheyAgree() {
        // a mob QuestieDB does not know at (20, 10): three rings from three spots
        val mob = doubleArrayOf(20.0, 10.0)
        val spots = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, -10.0), doubleArrayOf(0.0, 20.0))
        val rings = spots.map { p -> val d = hypot(mob[0] - p[0], mob[1] - p[1]); LivePlace.Ring(p[0], p[1], d - 2.5, d + 2.5, 0L) }
        val f = LivePlace.fix(rings, null)!!
        assertEquals(LivePlace.How.FIX, f.how)
        assertTrue("within its margin: ${f.x},${f.y} u ${f.u}", hypot(f.x - mob[0], f.y - mob[1]) <= f.u)
        assertNull("one ring: no fix", LivePlace.fix(rings.take(1), null))
    }

    @Test fun withNothingElseTheMobIsARingBandInThePlatesHalfPlane() {
        val m = parsed(mob(0, 3098, 10, 15, 20, ",\"sx\":0.2"))
        val p = LivePlace.place(m, me(f = 0.0), emptyList(), emptyList(), emptySet(), false, null)
        assertEquals(LivePlace.How.RING, p.how); assertEquals(0.0, p.half!!, 0.0)
        val d = LiveDanger.Disc(1, p.x, p.y, 20.0, 0.0, 1f, true, p.a, p.b, p.half)
        assertEquals("in front, on the band", 1f, d.at(17.5, 0.0), 0f)
        assertEquals("behind the player, beyond the radius", 0f, d.at(-30.0, 0.0), 0f)
        // b omitted: bounded by cv.maxd, else 41
        assertEquals(41.0, LivePlace.bracket(parsed(mob(0, 1, 10, 28, null)), null).second, 0.0)
        assertEquals(35.0, LivePlace.bracket(parsed(mob(0, 1, 10, 28, null)), 35.0).second, 0.0)
    }

    // ---------------------------------------------------------------- absence and decay

    @Test fun aSpawnInViewWithNoPlateForTwoSecondsIsAbsentAndItsStaticShareDropped() {
        store.onCoverage(AggroDoc.coverage(JSONObject(COV_DOC))!!.let { AggroDoc.Coverage(mapOf("maxd" to "30", "off" to "1", "ena" to "1"), it.sp, it.it, it.ci) })
        store.setSpawns(1411, listOf(spawn(1, 3098, 20.0, 0.0), spawn(2, 3098, 200.0, 0.0)))
        store.onRecord(full(1, 0.0, 0.0, emptyList()))
        now += 1_000; store.onRecord(full(2, 0.0, 0.0, emptyList()))
        assertNull("not yet 2 s", store.spawnState(1))
        now += 1_100; store.onRecord(full(3, 0.0, 0.0, emptyList()))
        assertEquals("absent", store.spawnState(1))
        assertNull("beyond the plates' range: unknown, the prior kept", store.spawnState(2))
        val s = store.snapshot(1411, 10)
        assertEquals(1, s.drops.size); assertEquals("absent", s.drops[0].why)
        assertEquals(DangerGrid.weight(10, 10, false, 10), s.drop(20.0, 0.0), 1e-6f)
        val combined = LiveDanger.combine({ _, _ -> 0.7f }, s)!!
        assertEquals("the static share taken off", 0f, combined(20.0, 0.0), 1e-6f)
        // held 5 min, then expected again
        now += LiveMobs.HOLD_MS + 1
        assertNull(store.spawnState(1))
    }

    @Test fun noAbsenceInCombatOrWithAPlateThatCouldBeIt() {
        store.onCoverage(AggroDoc.Coverage(mapOf("maxd" to "30", "off" to "1"), emptyList(), emptyList(), emptyList()))
        store.setSpawns(1411, listOf(spawn(1, 3098, 20.0, 0.0)))
        for (i in 1..5) { store.onRecord(full(i, 0.0, 0.0, emptyList(), comb = 1)); now += 1_000 }
        assertNull(store.spawnState(1))
        // a plate of its npc whose bracket holds its distance (somewhere on the ring): could be it
        for (i in 6..10) { store.onRecord(full(i, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 25, ",\"br\":3.14,\"bs\":\"c\"")))); now += 1_000 }
        assertNull(store.spawnState(1))
    }

    @Test fun aPlateThatLeavesFadesAndADeathDropsItAtOnceWithItsSpawnHeldDead() {
        store.setSpawns(1411, listOf(spawn(1, 3098, 18.0, 0.0)))
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 20))))
        val w0 = store.snapshot(1411, 10).at(18.0, 0.0)
        assertTrue(w0 > 0f)
        now += 200; store.onRecord(full(2, 0.0, 0.0, emptyList()))
        now += LiveMobs.GONE_FADE_MS / 2
        val half = store.snapshot(1411, 10)
        assertEquals("fading, not counted", 0, half.mobs)
        assertEquals(w0 / 2, half.at(18.0, 0.0), w0 * 0.05f)
        now += LiveMobs.GONE_FADE_MS
        assertEquals(0f, store.snapshot(1411, 10).at(18.0, 0.0), 0f)
        // a death: gone at once, its spawn dead (its static share dropped for the hold)
        store.onRecord(full(3, 0.0, 0.0, listOf(mob(1, 3098, 10, 15, 20))))
        now += 200; store.onRecord(full(4, 0.0, 0.0, listOf(mob(1, 3098, 10, 15, 20, ",\"dd\":1"))))
        val s = store.snapshot(1411, 10)
        assertEquals(0f, s.at(18.0, 0.0), 0f)
        assertEquals("dead", store.spawnState(1)); assertEquals("dead", s.drops.single().why)
    }

    // ---------------------------------------------------------------- the disc

    @Test fun theDiscFormulaSkullNeutralGreyAndElite() {
        assertEquals("equal level", 20.0, LiveDanger.radius(10, 10), 0.0)
        assertEquals("5 above", 25.0, LiveDanger.radius(15, 10), 0.0)
        assertEquals("floor", 5.0, LiveDanger.radius(1, 40), 0.0)
        assertEquals("cap", 45.0, LiveDanger.radius(60, 10), 0.0)
        assertEquals("skull: 45 (the user's ruling)", 45.0, LiveDanger.radius(-1, 10), 0.0)
        assertEquals("a learned base", 23.0, LiveDanger.radius(10, 10, 23.0), 0.0)
        val normal = parsed(mob(0, 1, 10, 15, 20))
        val elite = parsed("{\"s\":0,\"n\":1,\"lv\":10,\"c\":\"e\",\"r\":2,\"a\":15,\"b\":20}")
        val neutral = parsed("{\"s\":0,\"n\":1,\"lv\":10,\"c\":\"n\",\"r\":4,\"a\":15,\"b\":20}")
        val unfriendly = parsed("{\"s\":0,\"n\":1,\"lv\":10,\"c\":\"n\",\"r\":3,\"a\":15,\"b\":20}")
        assertEquals(DangerGrid.weight(10, 10, false, 10), LiveDanger.weight(normal, 10, 10), 0f)
        assertEquals("elite: x1.5 weight, same radius", LiveDanger.weight(normal, 10, 10) * 1.5f, LiveDanger.weight(elite, 10, 10), 1e-6f)
        assertEquals("neutral: none", 0f, LiveDanger.weight(neutral, 10, 10), 0f)
        assertTrue("unfriendly: a circle", LiveDanger.weight(unfriendly, 10, 10) > 0f)
        assertEquals("grey: none", 0f, LiveDanger.weight(normal, 3, 20), 0f)
        assertTrue("skull weighs as 10 above", LiveDanger.weight(parsed(mob(0, 1, -1, 15, 20)), -1, 10) >= DangerGrid.weight(20, 20, false, 10))
        // the store's disc: snapped spawn, radius 20 + margin 4, faded over 5 yd
        store.setSpawns(1411, listOf(spawn(1, 3098, 18.0, 0.0)))
        store.onRecord(full(1, 0.0, 0.0, listOf(mob(0, 3098, 10, 15, 20), "{\"s\":1,\"n\":3100,\"lv\":10,\"r\":4,\"a\":5,\"b\":8}")))
        val s = store.snapshot(1411, 10)
        assertEquals("the neutral counted out", 1, s.discs.size)
        val d = s.discs.single()
        assertEquals(24.0, d.reach, 1e-9)
        assertEquals(d.w, s.at(18.0 + 23.9, 0.0), 1e-6f)
        assertEquals(0f, s.at(18.0 + 29.1, 0.0), 0f)
        assertEquals(LiveDanger.LIVE_GAIN * DangerGrid.weight(10, 10, false, 10), d.w, 1e-6f)
    }

    // ---------------------------------------------------------------- the learned radius (FSR's vote)

    @Test fun theVoteFitOutvotesAnOutlierAndUsesTheMostSpecificTier() {
        // three pulls agree the base is in 22-24, one outlier at 30-35: the vote, not a mean
        val f = AggroFit.fit(listOf(21.0 to 24.0, 22.0 to 25.0, 22.0 to 24.0, 30.0 to 35.0), 20.0)
        assertEquals(3, f.agree); assertEquals(22.0, f.low, 0.0); assertEquals(24.0, f.high, 0.0)
        assertEquals("the prior moved just into the region", 22.0, f.base, 0.0)
        assertEquals(20.0, AggroFit.fit(listOf(18.0 to 21.0), 20.0).base, 0.0)
        // tiers: the npc's own, else its creature type, else all, else 20
        assertEquals(20.0, AggroFit.base(3098, 1), 0.0)
        fun pull(n: Int, ct: Int, lo: Double, hi: Double, t: Double) = AggroDoc.Pull(0, n, ct, 12, 10, lo, hi, t)
        assertTrue(AggroFit.add(pull(3098, 1, 24.0, 27.0, 1.0)))
        assertFalse("each pull once", AggroFit.add(pull(3098, 1, 24.0, 27.0, 1.0)))
        assertTrue(AggroFit.add(pull(3098, 1, 25.0, 28.0, 2.0)))
        // (test70: a tier moves only with AggroFit.MIN_PULLS pulls: the same two pulls again till it has them)
        assertEquals("too few pulls yet", 20.0, AggroFit.base(3098, 1), 0.0)
        for (k in 0 until AggroFit.MIN_PULLS - 2) AggroFit.add(pull(3098, 1, if (k % 2 == 0) 24.0 else 25.0, if (k % 2 == 0) 27.0 else 28.0, 10.0 + k))
        // the level gap (12 - 10) taken off: 22-25 and 23-26 agree on 23-25, the prior 20 moved into it: 23
        assertEquals(23.0, AggroFit.base(3098, 1), 0.0)
        assertEquals("its type's", 23.0, AggroFit.base(9999, 1), 0.0)
        assertEquals("all pulls'", 23.0, AggroFit.base(null, 7), 0.0)
        assertFalse("too wide", AggroFit.add(AggroDoc.Pull(0, 1, 1, 12, 10, 10.0, 30.0, 3.0)))
        assertFalse("near the clamps", AggroFit.add(AggroDoc.Pull(0, 1, 1, 30, 10, 38.0, 40.0, 4.0)))
        // the store learns from the full record's pl
        AggroFit.reset()
        store.onRecord(AggroDoc.fromPos(JSONObject(POS_DOC).getJSONObject("map"))!!)
        assertEquals(1, AggroFit.count())
    }

    @Test fun theLearnedPullsAreStoredAndAnInvalidFilePausesLearning() {
        val dir = java.nio.file.Files.createTempDirectory("aggro").toFile()
        val f = java.io.File(dir, "aggro_pulls.json")
        AggroFit.init(f)
        for (k in 0 until AggroFit.MIN_PULLS) assertTrue(AggroFit.add(AggroDoc.Pull(0, 3098, 1, 12, 10, 24.0, 27.0, 1.0 + k)))
        AggroFit.reset(); AggroFit.init(f)
        assertEquals(AggroFit.MIN_PULLS, AggroFit.count()); assertEquals(22.0, AggroFit.base(3098, null), 0.0)
        val bad = java.io.File(dir, "bad.json").apply { writeText("{\"schema\":1,\"records\":[{\"lo\":5,\"hi\":2,\"lv\":1,\"plv\":1}]}") }
        AggroFit.reset(); AggroFit.init(bad)
        assertTrue(AggroFit.paused)
        assertFalse(AggroFit.add(AggroDoc.Pull(0, 3098, 1, 12, 10, 24.0, 27.0, 1.0)))
        assertTrue("left untouched", bad.readText().contains("\"lo\":5"))
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- the route and the forced replan

    private val flat: (Double, Double) -> Float = { _, _ -> 1f }
    private fun node(id: Int, x: Double, y: Double) = SafePath.Node(id, x, y, SafePath.POINT)
    private fun road(a: Int, b: Int) = SafePath.Edge(a, b, SafePath.ROAD, SafePath.OK, "auto", 0f, 1f)
    /** a road x 0 (y 0 .. 1000) and a way round it out to x 120 */
    private fun twoWays() = ShippedNet.section(SafePath.Net(1411, 1, "off-device",
        listOf(node(0, 0.0, 0.0), node(1, 0.0, 1000.0), node(2, 120.0, 0.0), node(3, 120.0, 1000.0)),
        listOf(road(0, 1), road(0, 2), road(2, 3), road(3, 1)), emptyList()), TransportRoutes.HORDE, 10, "test")

    @Test fun theRouteBendsRoundAMobJustSeenOnIt() {
        val from = doubleArrayOf(0.0, -40.0); val to = doubleArrayOf(0.0, 1100.0)
        val quiet: (Double, Double) -> Float = { _, _ -> 0f }
        val before = ShippedRoute.plan(twoWays(), flat, quiet, from, to)
        assertTrue(before.pts.all { it[0] < 10.0 })
        // test45: an EMPTY live layer is still said on the route's line (an empty layer and a missing one told apart)
        val empty = ShippedRoute.plan(twoWays(), flat, quiet, from, to, live = LiveDanger.NONE)
        assertTrue(empty.why, empty.why.contains("; live 0 (0 mobs)"))
        // a pack of three lv 12 Bristlebacks on the road at y 500 (plates, sure snaps), for a level 10
        val spawns = (0 until 3).map { spawn(it, 3098, -15.0 + 15 * it, 500.0, 12) }
        store.setSpawns(1411, spawns)
        val me = doubleArrayOf(0.0, 480.0)
        store.onRecord(full(1, me[0], me[1], spawns.mapIndexed { i, s -> val d = hypot(s.x - me[0], s.y - me[1])
            mob(i, 3098, 12, (d - 2).toInt(), (d + 3).toInt(), ",\"br\":${Math.atan2(s.y - me[1], s.x - me[0])},\"bs\":\"c\"") }))
        val live = store.snapshot(1411, 10)
        assertEquals(3, live.mobs); assertTrue(live.discs.all { it.how == LivePlace.How.SPAWN })
        val after = ShippedRoute.plan(twoWays(), flat, quiet, from, to, live = live)
        assertTrue(after.why, after.reached)
        assertTrue("round the pack", after.pts.any { it[0] > 100.0 })
        assertTrue(after.why, after.why.contains("; live 0 (3 mobs)"))
        assertTrue("the road ignoring it pays the live danger", after.avoided > 0.0)
        // a live layer with no static danger under it: none (the net's own shares, as before)
        assertTrue(ShippedRoute.plan(twoWays(), flat, null, from, to, live = live).pts.all { it[0] < 10.0 })
    }

    @Test fun aNewMobOnTheWayAheadForcesAReplanOnce() {
        val rest = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 400.0))
        val goal = rest.last()
        val onWay = LiveDanger.Disc(7, 10.0, 200.0, 20.0, 4.0, 1f)
        val offWay = LiveDanger.Disc(8, 60.0, 200.0, 20.0, 4.0, 1f)
        val atGoal = LiveDanger.Disc(9, 5.0, 380.0, 20.0, 4.0, 1f)
        val band = LiveDanger.Disc(10, 0.0, 0.0, 20.0, 0.0, 1f, ring = true, a = 15.0, b = 20.0)
        val s = LiveDanger.Snapshot(listOf(offWay, atGoal, band, onWay), emptyList(), 4)
        assertEquals(7L, LiveDanger.newOnRoute(rest, s, emptySet(), goal)!!.id)
        assertNull("planned with it already", LiveDanger.newOnRoute(rest, s, s.pointIds, goal))
        assertEquals(setOf(7L, 8L, 9L), s.pointIds)
        assertNull("off the way, at the goal, a band: none forces", LiveDanger.newOnRoute(rest,
            LiveDanger.Snapshot(listOf(offWay, atGoal, band), emptyList(), 3), emptySet(), goal))
        // the forced answer takes the new way past the 10% rule
        assertFalse(ShippedPins.takeNew(500.0, 520.0, false)); assertTrue(ShippedPins.takeNew(500.0, 520.0, true))
        assertTrue(LiveDanger.FORCE_MS < SafePathV2.REPLAN_MS)
    }
}
