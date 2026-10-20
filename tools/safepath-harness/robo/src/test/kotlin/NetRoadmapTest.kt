import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.ShippedNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot

/**
 * NetRoadmap's rules on a made lattice (no game data): a ridge no edge crosses, deep water and a room no node or edge enters, the
 * danger bytes per faction, RM1's round trip. SAFEPATH_TEST=NetRoadmapTest bash tools/safepath-harness/run.sh "" "NetRoadmapTest|FAIL"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetRoadmapTest {
    private val ORG = 32.0 * com.jrees.wowcompanion.MapHarvest.ADT_YD
    // a 400 x 400 yd patch of flat ground round world (0, 0)
    private val T = 200.0; private val B = -200.0; private val L = 200.0; private val R = -200.0
    private fun gcOf(wy: Double) = Math.round((ORG - wy) / Climb.STEP).toInt()
    private fun grOf(wx: Double) = Math.round((ORG - wx) / Climb.STEP).toInt()

    private fun ground(height: (Int, Int) -> Float, deep: (Double, Double) -> Boolean = { _, _ -> false },
                       rooms: (Double, Double) -> Boolean = { _, _ -> false }): NetRoadmap.Ground {
        val g = Climb.build(T, B, L, R, height)
        g.solid = rooms
        return NetRoadmap.Ground(g, deep, null, { x, y -> if (deep(x, y)) 5f else Float.NaN }, rooms,
            { x, y -> if (rooms(x, y)) 2f else 0f }, 8.0, null)
    }
    private fun samples(rm: ShippedNet.Roadmap, e: Int): List<DoubleArray> = rm.points(e).zipWithNext().flatMap { (a, b) ->
        val n = maxOf(1, (hypot(b[0] - a[0], b[1] - a[1]) * 4).toInt()); (0..n).map { q -> doubleArrayOf(a[0] + (b[0] - a[0]) * q / n, a[1] + (b[1] - a[1]) * q / n) } }

    @Test fun flatGroundIsCovered() {
        val b = NetRoadmap.build(ground({ _, _ -> 0f }), 10)
        assertTrue("nodes ${b.rm.nodes}", b.rm.nodes >= 64)
        // every forward neighbour joined straight on flat ground: no inner points
        assertTrue(b.rm.edges >= b.rm.nodes * 3)
        assertTrue((0 until b.rm.edges).all { b.rm.edgePts[it] == null })
    }

    @Test fun aRidgeIsNeverCrossed() {
        // a cliff 40 yd high, 3 vertices wide, north to south across the patch at y = 0 (no way over it)
        val c0 = gcOf(0.0)
        val gnd = ground({ _, gc -> if (gc in c0..c0 + 2) 40f else 0f })
        val b = NetRoadmap.build(gnd, 10)
        assertTrue(b.rm.edges > 0)
        for (e in 0 until b.rm.edges) {
            val ya = b.rm.y(b.rm.edgeA[e]); val yb = b.rm.y(b.rm.edgeB[e])
            val side = { y: Double -> gcOf(y) < c0 }
            assertEquals("edge $e crosses the ridge", side(ya), side(yb))
            for ((p, q) in b.rm.points(e).zipWithNext()) assertTrue("edge $e segment not walkable", Climb.walkable(gnd.g, p, q))
        }
        // and no node on the ridge's top
        for (n in 0 until b.rm.nodes) assertFalse(b.rm.nodeGc[n] in c0..c0 + 2)
    }

    @Test fun deepWaterIsRefused() {
        // a lake 120 yd round the middle
        val lake = { x: Double, y: Double -> hypot(x, y) < 60.0 }
        val b = NetRoadmap.build(ground({ _, _ -> 0f }, deep = lake), 10)
        for (n in 0 until b.rm.nodes) assertFalse("node $n in the lake", lake(b.rm.x(n), b.rm.y(n)))
        for (e in 0 until b.rm.edges) assertTrue("edge $e enters the lake", samples(b.rm, e).none { lake(it[0], it[1]) })
        // round it, not cut: some edges walked round the shore
        assertTrue(b.rm.edges > 0)
    }

    @Test fun roomsAreRefused() {
        // a building's room, 30 x 30 yd at (50, 50)
        val room = { x: Double, y: Double -> x in 35.0..65.0 && y in 35.0..65.0 }
        val b = NetRoadmap.build(ground({ _, _ -> 0f }, rooms = room), 10)
        for (n in 0 until b.rm.nodes) assertFalse("node $n in the room", room(b.rm.x(n), b.rm.y(n)))
        for (e in 0 until b.rm.edges) assertTrue("edge $e crosses the room", samples(b.rm, e).none { room(it[0], it[1]) })
        assertTrue("an edge beside the room is flagged", (0 until b.rm.edges).any { b.rm.edgeFlags[it] and ShippedNet.RE_BUILDING != 0 })
    }

    @Test fun dangerTiersPerFaction() {
        val b = NetRoadmap.build(ground({ _, _ -> 0f }), 10)
        // the Horde's foes in the north half (2 units), the Alliance's none
        val h = NetRoadmap.dangerBytes(b) { x, _ -> if (x > 0) 2f else 0f }
        val a = NetRoadmap.dangerBytes(b) { _, _ -> 0f }
        assertEquals(b.rm.edges, h.size); assertEquals(b.rm.edges, a.size)
        assertTrue(a.all { it.toInt() == 0 })
        for (e in 0 until b.rm.edges) {
            val north = b.rm.points(e).all { it[0] > 1.0 }; val south = b.rm.points(e).all { it[0] < -1.0 }
            if (north) assertEquals(120, h[e].toInt() and 0xFF)             // 2 x ROADMAP_DANGER_SCALE
            if (south) assertEquals(0, h[e].toInt())
        }
    }

    @Test fun connectivityRepairChainsAcrossACauseway() {
        // a cell row of deep water across the whole patch, crossed only by a causeway one vertex wide: no node can stand on the
        // causeway (2 open steps), so the main pass leaves north and south apart; the repair's two-cell leg joins them through a node
        // on the causeway
        val g0 = Climb.build(T, B, L, R, { _, _ -> 0f })
        val r = (g0.ny / 10) / 2; val cc = (g0.nx / 10) / 2
        val xHi = g0.wx(r * 10) + 3.0; val xLo = g0.wx(r * 10 + 9) - 3.0; val yC = g0.wy(cc * 10 + 4)
        val deep = { x: Double, y: Double -> x in xLo..xHi && abs(y - yC) > 2.0 }
        val gnd = ground({ _, _ -> 0f }, deep = deep)
        val b = NetRoadmap.build(gnd, 10)
        assertTrue("pieces before ${b.stats.compBefore}", b.stats.compBefore >= 2)
        assertEquals("pieces after: ${b.stats}", 1, b.stats.compAfter)
        assertTrue(b.stats.repaired2 >= 1)
        for (e in 0 until b.rm.edges) {
            for ((p, q) in b.rm.points(e).zipWithNext()) assertTrue("edge $e segment not walkable", Climb.walkable(gnd.g, p, q, deep))
            assertTrue("edge $e enters the water", samples(b.rm, e).none { deep(it[0], it[1]) })
        }
        // RM1 still encodes it (nodes in cell order, edges in node order)
        assertEquals(b.rm.edges, ShippedNet.Roadmap.decode(b.rm.encode()).edges)
    }

    @Test fun rm1RoundTrip() {
        val c0 = gcOf(0.0)
        val b = NetRoadmap.build(ground({ _, gc -> if (gc in c0..c0 + 2) 40f else 0f }, deep = { x, y -> hypot(x - 120, y - 120) < 30 }), 10)
        val bytes = b.rm.encode()
        val back = ShippedNet.Roadmap.decode(bytes)
        assertTrue(back.encode().contentEquals(bytes))
        assertEquals(b.rm.edges, back.edges)
        for (e in 0 until b.rm.edges) assertEquals(b.rm.points(e).map { it.toList() }, back.points(e).map { it.toList() })
    }
}
