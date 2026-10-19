import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.MapHarvest
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NetJoins on a made-up lattice (no dumps): a plain 300 yd across cut north-south by a ridge (a wall) with one gap; a road on either side,
 * and a cave (a mouth and its floor) with no spur to the ground. The joins: through the gap, and the mouth onto the road, every
 * segment walkable; with the gap shut, the far side is a walled pocket; ringed by deep water, an island.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetJoinsTest {
    private val ORG = 32 * MapHarvest.ADT_YD
    private val S = Climb.STEP
    private fun yOf(gc: Int) = ORG - gc * S
    private fun xOf(gr: Int) = ORG - gr * S
    /** heights: a 40 yd high wall face along world y in [-6, 6], open where world x is in the gap (null: no gap) */
    private fun grid(gap: ClosedFloatingPointRange<Double>?) = Climb.build(150.0, -150.0, 150.0, -150.0, { gr, gc ->
        val y = yOf(gc); val x = xOf(gr)
        if (y in -6.0..6.0 && (gap == null || x !in gap)) 40f else 0f })
    private fun net(cave: Boolean): SafePath.Net {
        val n = ArrayList<SafePath.Node>(); val e = ArrayList<SafePath.Edge>()
        // the west road (y = +60) and the east road (y = -60), north to south
        for (k in 0..4) { n.add(SafePath.Node(k, 120.0 - k * 60, 60.0, SafePath.POINT)); n.add(SafePath.Node(10 + k, 120.0 - k * 60, -60.0, SafePath.POINT)) }
        for (k in 0 until 4) { e.add(SafePath.Edge(k, k + 1, SafePath.ROAD)); e.add(SafePath.Edge(10 + k, 11 + k, SafePath.ROAD)) }
        if (cave) { n.add(SafePath.Node(20, 0.0, 110.0, SafePath.MOUTH, "cave")); n.add(SafePath.Node(21, 0.0, 130.0, SafePath.CAVE, "cave"))
            e.add(SafePath.Edge(20, 21, SafePath.CAVE)) }
        return SafePath.Net(1, 1, "test", n, e, emptyList())
    }
    private val noWater: (Double, Double) -> Boolean = { _, _ -> false }
    private val calm: (Double, Double) -> Float = { _, _ -> 0f }

    @Test fun joinsThroughTheGapAndTheCaveMouth() {
        val g = grid(-20.0..20.0)
        val net = net(cave = true)
        assertEquals(3, NetJoins.pieceOf(net).toSet().size)
        val r = NetJoins.joins(net, g, noWater, calm, 0.0, log = { println(it) })
        println(r.summary("test"))
        r.joins.forEach { println("  ${it.why} ${it.pieceA}-${it.pieceB} ${it.yd.toInt()} yd ${NetJoins.flagNames(it.flags)} ${it.pts.map { p -> "${p[0].toInt()},${p[1].toInt()}" }}") }
        assertEquals(1, r.after); assertTrue(r.apart.isEmpty())
        assertEquals(2, r.tree.size)
        for (j in r.joins) {
            assertTrue(j.pts.zipWithNext().all { (p, q) -> Climb.walkable(g, p, q, noWater) })
            assertTrue(j.flags and ShippedNet.F_JOIN != 0)
        }
        // the ridge's join goes through the gap (|x| < 20 where it crosses y = 0), ~120 yd: the roads' facing points
        val ridge = r.joins.first { j -> (j.a.y > 0) != (j.b.y > 0) && j.a.y < 100 && j.b.y < 100 }
        val cross = ridge.pts.zipWithNext().first { pq: Pair<DoubleArray, DoubleArray> -> (pq.first[1] > 0) != (pq.second[1] > 0) }.toList()
        assertTrue("crosses at ${cross[0][0]}", Math.abs(cross[0][0]) < 20 && Math.abs(cross[1][0]) < 20)
        assertTrue(ridge.yd in 115.0..140.0)
        // the cave's mouth onto the west road (y 110 -> 60)
        val mouth = r.joins.first { it.a.node == 20 || it.b.node == 20 }
        assertTrue(mouth.yd in 45.0..60.0)
        // the exporter's wiring: the ways split where a join ends mid-way, one Extra a join; the applied net is one piece
        val (split, xs) = NetJoins.extras(net, r.joins)
        assertEquals(r.joins.size, xs.size)
        assertTrue(xs.all { x -> split.nodes.any { it.id == x.a } && split.nodes.any { it.id == x.b } })
        assertEquals(1, NetJoins.pieceOf(NetJoins.apply(net, r.joins)).toSet().size)
    }

    @Test fun aShutRidgeLeavesAWalledPocket() {
        val r = NetJoins.joins(net(cave = false), grid(null), noWater, calm, 0.0)
        println(r.summary("shut")); r.apart.forEach { println("  apart ${it.pieces}: ${it.why}") }
        assertEquals(0, r.joins.size); assertEquals(1, r.apart.size)
        assertTrue(r.apart[0].why.startsWith("a walled pocket"))
    }

    @Test fun anIslandIsSaidSo() {
        // the east road on an island: deep water all round it (|y + 60| < 40, |x| < 140 dry), the ridge open everywhere
        val deep: (Double, Double) -> Boolean = { x, y -> y < -6 && !(Math.abs(y + 60) < 40 && Math.abs(x) < 140) }
        val r = NetJoins.joins(net(cave = false), grid(-200.0..200.0), deep, calm, 0.0)
        println(r.summary("island")); r.apart.forEach { println("  apart ${it.pieces}: ${it.why}") }
        assertEquals(1, r.apart.size); assertTrue(r.apart[0].why.startsWith("an island"))
        // a declared swim link joins it
        val swim = ShippedNet.Link("swim", "the isle", false, 80.0, listOf(doubleArrayOf(0.0, -60.0, 3.0), doubleArrayOf(0.0, 60.0, 3.0)))
        val r2 = NetJoins.joins(net(cave = false), grid(-200.0..200.0), deep, calm, 0.0, links = listOf(swim))
        assertEquals(1, r2.after); assertEquals(0, r2.joins.size)
    }
}
