package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.hypot

/**
 * The device's route on the shipped network (ShippedNets, ShippedRoute; DEVICE_INTEGRATION_PLAN.md SHIPPED NETWORK): the store and its
 * "may be out of date" mark, the attach legs' 200 yd rule and walls, the router's detour cap, its avoided ways and its swims (only for
 * a goal on the island), and test20's ruling (no v1 / v2 switch: the retired pref removed, the lattice dormant)
 */
class ShippedRouteTest {
    private val H = TransportRoutes.HORDE
    private val flat: (Double, Double) -> Float = { _, _ -> 1f }

    private fun node(id: Int, x: Double, y: Double) = SafePath.Node(id, x, y, SafePath.POINT)
    private fun road(a: Int, b: Int, state: String = SafePath.OK) = SafePath.Edge(a, b, SafePath.ROAD, state, "auto", 0f, 1f)
    private fun section(nodes: List<SafePath.Node>, edges: List<SafePath.Edge>, links: List<ShippedNet.Link> = emptyList()) =
        ShippedNet.section(SafePath.Net(1411, 1, "off-device", nodes, edges, emptyList()), H, 10, "test", links)
    private fun pack(build: String, sec: ShippedNet.Section = straightRoad()) =
        ShippedNet.Pack(ShippedNet.Header(1411, 1, Climb.RULES_VERSION, "abc", build, "2026-10-19T12:00:00Z"), listOf(sec))
    /** a road north-south of 1000 yd: world x 0, y 0 .. 1000 */
    private fun straightRoad() = section(listOf(node(0, 0.0, 0.0), node(1, 0.0, 1000.0)), listOf(road(0, 1)))

    // ---------------------------------------------------------------- NetStore

    @Test fun theStoreReadsAZonesFileAndMarksAnotherBuildOnce() {
        val bytes = ShippedNet.bytes(pack("aaaa"))
        val log = ArrayList<String>(); val said = HashSet<Int>()
        val open: (String) -> java.io.InputStream? = { p -> if (p == "safepath/net/1411.bin") ByteArrayInputStream(bytes) else null }
        val l = ShippedNets.load(1411, open, "bbbb", { log.add(it) }, said)!!
        assertEquals("exported for build aaaa, game is bbbb", l.stale)
        assertEquals(listOf("net 1411: may be out of date (exported for build aaaa, game is bbbb)"), log)
        assertNotNull(ShippedNets.sectionOf(l.pack, H))
        assertNotNull("an unknown side takes the first section", ShippedNets.sectionOf(l.pack, 0))
        // still used, and said once a zone
        assertNotNull(ShippedNets.load(1411, open, "bbbb", { log.add(it) }, said))
        assertEquals(1, log.size)
        // the same build, or no game build known: no mark
        assertNull(ShippedNets.load(1411, open, "AAAA", { log.add(it) })!!.stale)
        assertNull(ShippedNets.load(1411, open, null, { log.add(it) })!!.stale)
        // no file, or not NET1: none
        assertNull(ShippedNets.load(1412, open, "aaaa", { log.add(it) }))
        assertNull(ShippedNets.load(1411, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, "aaaa", { log.add(it) }))
    }

    @Test fun theStoreReadsNoFileBiggerThanItsBound() {
        assertEquals(0, ShippedNets.readBounded(ByteArrayInputStream(ByteArray(1000)), 999).size)
        assertEquals(1000, ShippedNets.readBounded(ByteArrayInputStream(ByteArray(1000)), 1000).size)
    }

    // ---------------------------------------------------------------- the attach legs

    @Test fun aPlayerFartherThan200YdFromEveryWayGetsNoRoute() {
        val r = ShippedRoute.plan(straightRoad(), flat, null, doubleArrayOf(250.0, 0.0), doubleArrayOf(-150.0, 900.0))
        assertFalse(r.reached)
        assertTrue(r.why, r.why.contains("attach at most 200 yd"))
        assertEquals(2, r.pts.size)
        val g = ShippedRoute.plan(straightRoad(), flat, null, doubleArrayOf(150.0, 0.0), doubleArrayOf(-250.0, 900.0))
        assertFalse(g.reached); assertTrue(g.why, g.why.startsWith("the goal is"))
    }

    @Test fun aPlayerWithin200YdIsAttachedAndRouted() {
        val r = ShippedRoute.plan(straightRoad(), flat, null, doubleArrayOf(150.0, 100.0), doubleArrayOf(-150.0, 900.0))
        assertTrue(r.why, r.reached)
        assertTrue(r.attachFromYd in 149.0..200.0); assertTrue(r.attachToYd in 149.0..200.0)
        assertEquals("on, along the road, off: ${r.pts.map { it.toList() }}", 4, r.pts.size)
        assertTrue("never past the goal's cut and back", r.pts.all { it[1] in 99.0..901.0 })
        assertEquals(150.0, r.pts.first()[0], 1e-9); assertEquals(-150.0, r.pts.last()[0], 1e-9)
    }

    @Test fun theAttachLegGoesRoundAWallAndNeverThroughIt() {
        // a wall between the player and the road: world x 70 .. 80, y -60 .. 110
        fun wall(x: Double, y: Double) = x in 70.0..80.0 && y in -60.0..110.0
        val ground: (Double, Double) -> Float = { x, y -> if (wall(x, y)) Terrain.WALL else 1f }
        val r = ShippedRoute.plan(straightRoad(), ground, null, doubleArrayOf(120.0, 50.0), doubleArrayOf(-150.0, 900.0))
        assertTrue(r.why, r.reached)
        assertTrue("walked round: ${r.attachFromYd}", r.attachFromYd > 140.0 && r.attachFromYd <= 200.0)
        for ((a, b) in r.pts.zipWithNext()) for (q in 0..200) {
            val x = a[0] + (b[0] - a[0]) * q / 200; val y = a[1] + (b[1] - a[1]) * q / 200
            assertFalse("crosses the wall at ($x, $y)", wall(x, y))
        }
        // a wall all round (no gap within 200 yd of walking): no route
        val boxed: (Double, Double) -> Float = { x, y -> val d = hypot(x - 120.0, y - 50.0); if (d in 20.0..30.0) Terrain.WALL else 1f }
        val n = ShippedRoute.plan(straightRoad(), boxed, null, doubleArrayOf(120.0, 50.0), doubleArrayOf(-150.0, 900.0))
        assertFalse(n.reached); assertTrue(n.why, n.why.contains("no walk of 200 yd"))
    }

    // ---------------------------------------------------------------- the router

    /** the short road x 0 (y 0 .. 1000) with danger on its middle, and a detour road out to world x [out] and back */
    private fun twoWays(out: Double, shortState: String = SafePath.OK) = section(
        listOf(node(0, 0.0, 0.0), node(1, 0.0, 1000.0), node(2, out, 0.0), node(3, out, 1000.0)),
        listOf(road(0, 1, shortState), road(0, 2), road(2, 3), road(3, 1)))
    private val camp: (Double, Double) -> Float = { x, y -> if (x in -50.0..50.0 && y in 300.0..700.0) 4f else 0f }
    private val from = doubleArrayOf(0.0, -100.0); private val to = doubleArrayOf(0.0, 1100.0)

    @Test fun aModestDetourRoundTheDangerIsTaken() {
        val r = ShippedRoute.plan(twoWays(300.0), flat, camp, from, to)
        assertTrue(r.why, r.reached); assertFalse(r.capped)
        assertTrue("round the camp", r.pts.any { it[0] > 250.0 })
    }

    @Test fun aDetourLongerThanTheCapLosesToTheWayIgnoringTheDanger() {
        // round: 2000 + 1000 + 2000 + 200 yd, over 1.75 x 1200 + 500
        val r = ShippedRoute.plan(twoWays(2000.0), flat, camp, from, to)
        assertTrue(r.why, r.reached); assertTrue(r.why, r.capped)
        assertTrue("straight through", r.pts.all { it[0] < 100.0 })
        assertTrue(r.why.contains("capped"))
    }

    @Test fun anAvoidedWayCostsSixTimesItsYards() {
        val r = ShippedRoute.plan(twoWays(300.0, SafePath.AVOID), flat, null, from, to)
        assertTrue(r.reached); assertTrue("round the avoided way", r.pts.any { it[0] > 250.0 })
        // round by 3000 + 1000 + 3000: dearer than 6 x 1000, the avoided way taken
        val f = ShippedRoute.plan(twoWays(3000.0, SafePath.AVOID), flat, null, from, to)
        assertTrue(f.reached); assertTrue("on the avoided way", f.pts.all { it[0] < 100.0 })
    }

    @Test fun theNetsOwnTierStandsInWhenThereIsNoCharacterDanger() {
        val sec = section(listOf(node(0, 0.0, 0.0), node(1, 0.0, 1000.0), node(2, 300.0, 0.0), node(3, 300.0, 1000.0)),
            listOf(SafePath.Edge(0, 1, SafePath.ROAD, SafePath.OK, "auto", 1f, 1f), road(0, 2), road(2, 3), road(3, 1)))
        val r = ShippedRoute.plan(sec, flat, null, from, to)
        assertTrue(r.reached); assertTrue(r.pts.any { it[0] > 250.0 })
        val calm = ShippedRoute.plan(sec, flat, null, from, to, k = 0.0)
        assertTrue(calm.pts.all { it[0] < 100.0 })
    }

    /** the mainland's road (y 0 .. 1000), the island's (y 1600 .. 2100), a swim between (y 1000 .. 1600) for the island round
     *  (0, 1850) r 200, and a long walk round on the mainland to the island road's far end */
    private fun islands() = section(
        listOf(node(0, 0.0, 0.0), node(1, 0.0, 1000.0), node(2, 0.0, 1600.0), node(3, 0.0, 2100.0), node(4, 500.0, 1000.0), node(5, 500.0, 2300.0),
            node(6, 0.0, 2300.0)),
        listOf(road(0, 1), road(2, 3), road(1, 4), road(4, 5), road(5, 6), road(6, 3)),
        listOf(ShippedNet.Link("swim", "the isle", false, 600.0, listOf(doubleArrayOf(0.0, 1000.0, Climb.SWIM), doubleArrayOf(0.0, 1600.0, Climb.SWIM)),
            doubleArrayOf(0.0, 1850.0, 200.0))))

    @Test fun aSwimIsTakenOnlyForAGoalOnItsIsland() {
        val on = ShippedRoute.plan(islands(), flat, null, from, doubleArrayOf(0.0, 1850.0))
        assertTrue(on.why, on.reached)
        assertTrue("swum: ${on.why}", on.swims > 0 && on.pts.any { Climb.atSwim(it) })
        // a goal past the island's ground: the walk round, never the swim
        val off = ShippedRoute.plan(islands(), flat, null, from, doubleArrayOf(0.0, 2200.0))
        assertTrue(off.why, off.reached)
        assertEquals(0, off.swims)
        assertTrue("round by the mainland", off.pts.any { it[0] > 400.0 })
    }

    @Test fun aNearGoalIsTheGroundsOwnWalk() {
        val r = ShippedRoute.plan(straightRoad(), flat, null, doubleArrayOf(100.0, 300.0), doubleArrayOf(100.0, 400.0))
        assertTrue(r.reached); assertEquals(2, r.pts.size)
    }

    // ---------------------------------------------------------------- test20: no v1 / v2 switch

    @Test fun theRetiredV2PrefIsRemovedOnce() {
        val p = FakePrefs(mutableMapOf(Settings.RETIRED_SAFEPATH_V2 to true, "avoidDanger" to "high"))
        assertTrue(Settings.dropRetired(p))
        assertFalse(p.contains(Settings.RETIRED_SAFEPATH_V2))
        assertTrue("the rest kept", p.contains("avoidDanger"))
        assertFalse("nothing left to remove", Settings.dropRetired(p))
    }

    @Test fun noSettingTurnsTheLatticeOnOrTheShippedNetworkOff() {
        assertFalse("the lattice is dormant by default", SafePathV2.enabled)
        val names = Settings::class.java.methods.map { it.name }
        assertTrue("no SafePath v2 setting", names.none { it.contains("safePathV2", ignoreCase = true) })
    }

    /** a SharedPreferences in memory (android.jar's are stubs off the device) */
    private class FakePrefs(val m: MutableMap<String, Any?>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?) = d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val put = HashMap<String, Any?>(); val rm = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { rm.add(k) }
            override fun clear() = apply { rm.addAll(m.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { rm.forEach { m.remove(it) }; m.putAll(put) }
        }
    }
}
