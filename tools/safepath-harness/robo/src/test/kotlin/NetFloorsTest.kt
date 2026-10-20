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
import kotlin.math.hypot

/**
 * NetFloors' own checks on a made-up flat lattice (no game data): a far camp gets a walked spur, a cave mouth a join onto the nearest
 * ground way, a place on a floor layer a floor way with its layer index, a wall is walked round; [NetFloors.apply] cuts the net's way
 * where they end and every extra's ends are nodes; the floor layers' pieces are numbered from the lowest up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetFloorsTest {
    private val S = Climb.STEP; private val ORG = 32 * MapHarvest.ADT_YD
    // a 120 x 120 lattice (500 yd square) round world (0, 0): x north, y west
    private val gr0 = Math.round(ORG / S).toInt() - 60; private val gc0 = gr0
    private fun grid(wallAtJ: Int? = null): Climb.Grid {
        val n = 120; val cls = ByteArray(n * n)
        // a wall: the east and south steps of a row of vertices (j = wallAtJ, i = 20..119) - a ridge across the lattice
        if (wallAtJ != null) for (i in 20..119) { val k = wallAtJ * n + i; cls[k] = (Climb.WALL or (Climb.WALL shl 2)).toByte()
            val k2 = (wallAtJ - 1) * n + i; cls[k2] = (cls[k2].toInt() or (Climb.WALL shl 2)).toByte() }
        return Climb.Grid(gr0, gc0, n, n, cls)
    }
    private fun wx(j: Int) = ORG - (gr0 + j) * S
    private fun wy(i: Int) = ORG - (gc0 + i) * S
    private fun net(): SafePath.Net {
        // a road along the lattice's row j = 30, i 5..115; a cave: its mouth at (j 90, i 60), a cave way inside from it
        val nodes = listOf(SafePath.Node(1, wx(30), wy(5), SafePath.JUNCTION), SafePath.Node(2, wx(30), wy(115), SafePath.JUNCTION),
            SafePath.Node(3, wx(90), wy(60), SafePath.MOUTH, "cave"), SafePath.Node(4, wx(100), wy(60), SafePath.CAVE, "cave"))
        val edges = listOf(SafePath.Edge(1, 2, SafePath.ROAD), SafePath.Edge(3, 4, SafePath.CAVE, src = "cave"))
        return SafePath.Net(1, 1, "test", nodes, edges, emptyList())
    }
    private fun walkable(g: Climb.Grid, pts: List<DoubleArray>) = pts.zipWithNext().all { (a, b) -> Climb.walkable(g, a, b, null) }

    @Test fun joinsSpursAndFloors() {
        val g = grid(wallAtJ = 60)
        // a floor layer: a 10 x 10 piece of vertices at j 100..109, i 20..29 (40 yd up), and a lower one at j 5..10, i 100..105 (2 yd)
        val layers = HashMap<Long, Float>()
        for (j in 100..109) for (i in 20..29) layers[Climb.vkey(gr0 + j, gc0 + i)] = 40f
        for (j in 5..10) for (i in 100..105) layers[Climb.vkey(gr0 + j, gc0 + i)] = 2f
        val nl = NetFloors.Layers(layers, g)
        assertEquals(2, nl.count)
        assertEquals(1, nl.at(wx(7), wy(102)))        // the lower piece first
        assertEquals(2, nl.at(wx(105), wy(25)))
        val n = net()
        val places = NetFloors.places(n, hostile = (0 until 6).map { Triple(wx(115) + it, wy(115), "Kobold") }, npcs = listOf(Triple(wx(105), wy(25), "Trainer")),
            goals = emptyList(), layers = nl).filter { it.why != "opening" }
        assertTrue(places.any { it.why == "camp" }); assertTrue(places.any { it.why == "mouth" }); assertTrue(places.any { it.why == "npc" && it.floor == 2 })
        val inp = NetFloors.Input(n, emptyList(), emptyList(), g, { _, _ -> false }, nl, { _, _ -> true }, { _, _ -> 0f }, places)
        val plan = NetFloors.ways(inp)
        assertEquals(plan.unreached.toString(), 0, plan.unreached.size)
        assertEquals(1, plan.count(NetFloors.JOIN)); assertEquals(1, plan.count(NetFloors.SPUR)); assertEquals(1, plan.count(NetFloors.FLOOR))
        for (w in plan.ways) assertTrue("${w.kind} ${w.name} walkable", walkable(g, w.pts))
        // the join and the spur walk round the wall (its row j = 60 from i 20 to the edge): longer than their straight lines
        val join = plan.ways.first { it.kind == NetFloors.JOIN }
        assertTrue(join.yd > hypot(join.pts.first()[0] - join.pts.last()[0], join.pts.first()[1] - join.pts.last()[1]) + 10)
        val floor = plan.ways.first { it.kind == NetFloors.FLOOR }
        assertEquals(2, floor.layer); assertEquals(2, floor.layers.first())
        // into the section: the road cut where they end, each extra's ends nodes, its points from node a to node b
        val (net2, nodes2, extras2) = NetFloors.apply(plan, n, emptyList(), emptyList(), g, { _, _ -> false })
        val byId = (net2.nodes + nodes2).associateBy { it.id }
        assertTrue(plan.ways.joinToString { w -> "${w.kind} ${w.pts.first().map { it.toInt() }} -> ${w.pts.last().map { it.toInt() }} ends ${plan.ends[plan.ways.indexOf(w)].let { e -> "edge ${e.edge} mine ${e.mine} node ${e.node}" }}" } + " road " + listOf(wx(30), wy(5), wy(115)).map { it.toInt() },
            net2.edges.count { it.kind == SafePath.ROAD } >= 2)
        for (x in extras2) { val a = byId[x.a]!!; val b = byId[x.b]!!
            assertTrue(hypot(a.wx - x.pts.first()[0], a.wy - x.pts.first()[1]) < 0.6); assertTrue(hypot(b.wx - x.pts.last()[0], b.wy - x.pts.last()[1]) < 0.6) }
        assertTrue(extras2.any { it.a == 3 || it.b == 3 })                // the join from the mouth's own node
        val sec = ShippedNet.section(net2, 1, 10, "test", extraNodes = nodes2, extras = extras2)
        val back = ShippedNet.Section.fromJson(sec.toJson())!!
        assertEquals(sec.ways.size, back.ways.size)
        assertTrue(back.ways.any { it.layers?.contains(2) == true })
        // a road walked from both ends' nodes: every node of the extras is on a live way
        val live = HashSet<Int>().also { s -> for (e in back.net.edges) { s.add(e.a); s.add(e.b) } }
        assertTrue(extras2.all { it.a in live && it.b in live })
    }

    @Test fun surfacesWallAFloorsDrop() {
        // a floor 4 yd over the ground with no ramp: a line from the floor onto the ground is a wall; along the floor it is not
        val layers = HashMap<Long, Float>()
        for (j in 0..20) for (i in 0..20) layers[Climb.vkey(gr0 + j, gc0 + i)] = 4f
        val surf = NetFloors.Surfaces(layers, { x, y -> if (x <= wx(0) + 0.1 && x >= wx(20) - 0.1 && y <= wy(0) + 0.1 && y >= wy(20) - 0.1) doubleArrayOf(4.0) else DoubleArray(0) },
            { _, _ -> 0f }, { _, _ -> false })
        assertTrue(surf.ok(listOf(doubleArrayOf(wx(2), wy(2)), doubleArrayOf(wx(18), wy(18)))))
        assertTrue(!surf.ok(listOf(doubleArrayOf(wx(10), wy(10)), doubleArrayOf(wx(40), wy(10)))))
    }
}
