package com.jrees.wowcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** NET1, the shipped network's file (ShippedNet; E:/safepath-work/docs/NET1_FORMAT.md): written, read back the same */
class ShippedNetTest {
    private val H = TransportRoutes.HORDE
    private val A = TransportRoutes.ALLIANCE

    /** a small zone: a road from a town through a junction to a flight master, a trail off it to a cave's mouth and a cave way, a
     *  walled trail (REMOVED), an avoided spur, a rewalked road, a dock with a boat exit and a walk exit */
    private fun net(dangerK: Float): SafePath.Net {
        val n = listOf(
            SafePath.Node(0, 100.0, 200.0, SafePath.HUB, "auto", "Razor Hill"),
            SafePath.Node(1, 150.5, 210.25, SafePath.JUNCTION),
            SafePath.Node(2, 200.0, 260.0, SafePath.FLIGHT, "auto", "Razor Hill flight master"),
            SafePath.Node(3, 160.0, 300.0, SafePath.MOUTH, "cave"),
            SafePath.Node(4, 170.0, 320.0, SafePath.CAVE, "cave"),
            SafePath.Node(5, 300.0, 100.0, SafePath.POINT),
            SafePath.Node(6, 90.0, 120.0, SafePath.DOCK, "auto", "the dock"),
            SafePath.Node(7, 50.0, 400.0, SafePath.EXIT, "ground"),
            SafePath.Node(8, 999.0, 999.0, SafePath.POINT),          // only on the removed way: left out
        )
        val e = listOf(
            SafePath.Edge(0, 1, SafePath.ROAD, SafePath.OK, "auto", 0.1f * dangerK, 1.0f),
            SafePath.Edge(1, 2, SafePath.ROAD, SafePath.REWALK, "auto", 0.25f * dangerK, 1.2f),
            SafePath.Edge(1, 3, SafePath.TRAIL, SafePath.DRAFT, "auto", 0.5f * dangerK, 1.7f),
            SafePath.Edge(3, 4, SafePath.CAVE, SafePath.DRAFT, "cave", 0.9f * dangerK, 1.0f),
            SafePath.Edge(1, 8, SafePath.TRAIL, SafePath.REMOVED, "auto", 0f, 1f),
            SafePath.Edge(0, 5, SafePath.SPUR, SafePath.AVOID, "user", 0.33f * dangerK, 1.1f),
            SafePath.Edge(0, 6, SafePath.ROAD, SafePath.OK, "auto", 0f, 1f),
            SafePath.Edge(2, 7, SafePath.TRAIL, SafePath.OK, "auto", 0f, 1.4f),
        )
        val x = listOf(SafePath.Exit(6, 1413, "boat", "The Barrens"), SafePath.Exit(7, 1413, "walk", "The Barrens"))
        return SafePath.Net(1411, 1, "off-device", n, e, x)
    }
    private val links = listOf(
        ShippedNet.Link("portal", "", true, 15.0, listOf(doubleArrayOf(10.0, 20.0, 0.0), doubleArrayOf(11.5, 22.5, 1.0))),
        ShippedNet.Link("swim", "Fenris Isle", false, 212.3, listOf(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(5.0, 6.0, 3.0)), doubleArrayOf(40.0, 50.0, 120.0)))
    // the ford under way 0 -> 1, the bridge on 0 -> 6
    private fun wet(a: DoubleArray, b: DoubleArray): Int =
        if (a[0] == 100.0 && b[0] == 150.5) ShippedNet.F_WADE else if (a[0] == 100.0 && b[0] == 90.0) ShippedNet.F_BRIDGE else 0

    // the rewalked way 1 -> 2 walked by a bend
    private val walk: (DoubleArray, DoubleArray) -> List<DoubleArray>? = { a, b -> listOf(a, doubleArrayOf(180.25, 215.5), b) }
    /** a 3 x 2 cell roadmap: nodes in cells 0, 1, 3, 4 (cells 2 and 5 empty); edges 0-1 (east, straight), 0-2 (south, walked by two
     *  points), 0-3 (south-east), 1-2 (south-west: wade, building, bridge, the tiers) */
    private fun roadmap(): ShippedNet.Roadmap {
        val gr0 = 1000; val gc0 = -2000; val cell = 10
        val cells = intArrayOf(0, 1, 3, 4)
        val gr = intArrayOf(gr0 + 4, gr0 + 5, gr0 + 13, gr0 + 19); val gc = intArrayOf(gc0 + 4, gc0 + 10, gc0 + 6, gc0 + 19)
        return ShippedNet.Roadmap(gr0, gc0, cell, 3, 2, cells, gr, gc, intArrayOf(0, ShippedNet.R_FLOOR, ShippedNet.R_BUILDING, 3),
            intArrayOf(0, 0, 0, 1), intArrayOf(1, 2, 3, 2),
            intArrayOf(0, ShippedNet.RE_WALKED, (2 shl 2) or (1 shl 4), ShippedNet.RE_WADE or ShippedNet.RE_BUILDING or (3 shl 2) or (2 shl 4) or ShippedNet.RE_BRIDGE),
            arrayOf(null, intArrayOf(gr0 + 7, gc0 + 3, gr0 + 9, gc0 + 7), null, null))
    }
    private fun pack(): ShippedNet.Pack = ShippedNet.Pack(
        ShippedNet.Header(1411, 1, Climb.RULES_VERSION, "5682a5e", "25822068f8fce8b21bc714e7e3210481", "2026-10-19T12:00:00Z"),
        listOf(ShippedNet.section(net(1f), H, 8, "durotar", links, ::wet, walk, roadmapDanger = byteArrayOf(0, 60, 120, -1)),
            ShippedNet.section(net(0.5f), A, 8, "durotar_alliance", emptyList(), ::wet, roadmapDanger = byteArrayOf(1, 2, 3, 4))), roadmap())

    @Test fun aSectionDropsTheRemovedWaysAndFlagsTheRest() {
        val s = ShippedNet.section(net(1f), H, 8, "durotar", links, ::wet)
        assertEquals(7, s.net.edges.size)
        assertTrue(s.net.nodes.none { it.id == 8 })
        assertEquals(s.net.edges.size, s.ways.size)
        val f = s.ways.map { ShippedNet.flagNames(it.flags) }
        assertEquals(listOf(listOf("road", "wade"), listOf("road", "rewalk"), listOf("trail"), listOf("cave"), listOf("spur", "avoid"),
            listOf("road", "bridge"), listOf("trail")), f)
        assertEquals(Math.hypot(50.5, 10.25), s.ways[0].yd, 1e-9)
        // the starts: the flight master, the dock (its boat), and no walk exit
        assertEquals(listOf(2 to "flight", 6 to "dock"), s.starts.map { it.node to it.kind })
        assertEquals(1413, s.starts[1].toMap)
        assertEquals(listOf(0, 2, 6), s.checkpoints)
    }

    @Test fun aPackRoundTrips() {
        val p0 = pack()
        val bytes = ShippedNet.bytes(p0)
        // gzip
        assertEquals(0x1f, bytes[0].toInt() and 0xff); assertEquals(0x8b, bytes[1].toInt() and 0xff)
        val p = ShippedNet.read(ByteArrayInputStream(bytes))
        assertNotNull(p); p!!
        assertEquals("NET1", p.header.format); assertEquals(ShippedNet.VERSION, p.header.version)
        assertEquals(1411, p.header.map); assertEquals(1, p.header.cont); assertEquals(Climb.RULES_VERSION, p.header.rules)
        assertEquals("5682a5e", p.header.rulesCommit); assertEquals("25822068f8fce8b21bc714e7e3210481", p.header.build)
        assertEquals("2026-10-19T12:00:00Z", p.header.exported)
        assertEquals(2, p.sections.size)
        for ((a, b) in p0.sections.zip(p.sections)) {
            assertEquals(a.faction, b.faction); assertEquals(a.level, b.level); assertEquals(a.tag, b.tag)
            assertEquals(a.net.map, b.net.map); assertEquals(a.net.cont, b.net.cont); assertEquals(a.net.built, b.net.built)
            assertEquals(a.net.nodes.map { listOf(it.id, it.kind, it.src, it.name) }, b.net.nodes.map { listOf(it.id, it.kind, it.src, it.name) })
            for ((na, nb) in a.net.nodes.zip(b.net.nodes)) { assertEquals(na.wx, nb.wx, 0.051); assertEquals(na.wy, nb.wy, 0.051) }
            assertEquals(a.net.edges.map { listOf(it.a, it.b, it.kind, it.state, it.src) }, b.net.edges.map { listOf(it.a, it.b, it.kind, it.state, it.src) })
            for ((ea, eb) in a.net.edges.zip(b.net.edges)) { assertEquals(ea.danger, eb.danger, 0.005f); assertEquals(ea.climb, eb.climb, 0.005f) }
            assertEquals(a.ways.map { it.flags }, b.ways.map { it.flags })
            for ((wa, wb) in a.ways.zip(b.ways)) { assertEquals(wa.yd, wb.yd, 0.051); assertEquals(wa.pts == null, wb.pts == null)
                wa.pts?.zip(wb.pts!!)?.forEach { (pa, pb) -> assertArrayEquals(pa, pb, 0.0051) } }
            assertArrayEquals(a.roadmapDanger, b.roadmapDanger)
            assertEquals(a.net.exits.map { listOf(it.node, it.toMap, it.how, it.name) }, b.net.exits.map { listOf(it.node, it.toMap, it.how, it.name) })
            assertEquals(a.starts.map { listOf(it.node, it.kind, it.toMap, it.name) }, b.starts.map { listOf(it.node, it.kind, it.toMap, it.name) })
            assertEquals(a.checkpoints, b.checkpoints)
            assertEquals(a.links.size, b.links.size)
            for ((la, lb) in a.links.zip(b.links)) {
                assertEquals(listOf(la.kind, la.name, la.oneWay), listOf(lb.kind, lb.name, lb.oneWay)); assertEquals(la.yd, lb.yd, 0.051)
                assertEquals(la.pts.size, lb.pts.size)
                for ((pa, pb) in la.pts.zip(lb.pts)) assertArrayEquals(pa, pb, 0.051)
                assertEquals(la.island == null, lb.island == null); la.island?.let { assertArrayEquals(it, lb.island, 0.051) }
            }
        }
        // the danger is per faction
        assertEquals(0.5f, p.section(H)!!.net.edges[2].danger, 1e-6f)
        assertEquals(0.25f, p.section(A)!!.net.edges[2].danger, 1e-6f)
        // the rewalked way's own points (both ends included), its length theirs
        val rw = p.section(H)!!.ways[1]
        assertEquals(3, rw.pts!!.size); assertEquals(180.25, rw.pts!![1][0], 1e-9)
        assertEquals(Math.hypot(180.25 - 150.5, 215.5 - 210.25) + Math.hypot(200.0 - 180.25, 260.0 - 215.5), rw.yd, 0.051)
        assertNull(p.section(A)!!.ways[1].pts)                // no walk given: the straight line
        // the roadmap, as it was
        val r0 = roadmap(); val r = p.roadmap!!
        assertEquals(listOf(r0.gr0, r0.gc0, r0.cell, r0.nx, r0.ny), listOf(r.gr0, r.gc0, r.cell, r.nx, r.ny))
        assertArrayEquals(r0.nodeCell, r.nodeCell); assertArrayEquals(r0.nodeGr, r.nodeGr); assertArrayEquals(r0.nodeGc, r.nodeGc); assertArrayEquals(r0.nodeFlags, r.nodeFlags)
        assertArrayEquals(r0.edgeA, r.edgeA); assertArrayEquals(r0.edgeB, r.edgeB); assertArrayEquals(r0.edgeFlags, r.edgeFlags)
        for (e in 0 until r0.edges) assertArrayEquals(r0.edgePts[e], r.edgePts[e])
        assertEquals(4, r.points(1).size); assertEquals(ShippedNet.vx(1007), r.points(1)[1][0], 1e-9); assertEquals(ShippedNet.vy(-1997), r.points(1)[1][1], 1e-9)
        assertEquals(3, ShippedNet.edgeTier(r.edgeFlags[3])); assertEquals(2, ShippedNet.climbTier(r.edgeFlags[3]))
        assertEquals(255 / ShippedNet.ROADMAP_DANGER_SCALE, p.section(H)!!.roadmapDangerOf(3), 1e-6f)
        assertEquals(1f, p.section(H)!!.roadmapDangerOf(1), 1e-6f)
        // the lattice frame: a vertex's world point and back
        assertEquals(1007, ShippedNet.gr(ShippedNet.vx(1007))); assertEquals(-1997, ShippedNet.gc(ShippedNet.vy(-1997)))
        // the same pack, the same bytes (written again from what was read)
        assertArrayEquals(bytes, ShippedNet.bytes(p))
    }

    @Test fun aSectionIsTheDevicesOwnNetDocument() {
        // SafePath.Net.fromJson reads a section as it is (the device's loader barely changes)
        val o = ShippedNet.section(net(1f), H, 8, "durotar", links, ::wet).toJson()
        val n = SafePath.Net.fromJson(o)
        assertNotNull(n)
        assertEquals(7, n!!.edges.size); assertEquals(2, n.exits.size)
    }

    @Test fun aVersion1FileStillReads() {
        // a v1 file (no roadmap, no walked points): read by this reader
        val v1 = ShippedNet.Pack(ShippedNet.Header(1411, 1, 5, "c", "b", "t", "NET1", 1), listOf(ShippedNet.section(net(1f), H, 8, "durotar")))
        val p = ShippedNet.read(ByteArrayInputStream(ShippedNet.bytes(v1)))!!
        assertEquals(1, p.header.version); assertNull(p.roadmap); assertEquals(7, p.sections[0].net.edges.size)
        assertEquals(0f, p.sections[0].roadmapDangerOf(0), 0f)
    }

    @Test fun aRoadmapRefusesWhatItCannotEncode() {
        val r = roadmap()
        // a node outside its cell
        val bad = ShippedNet.Roadmap(r.gr0, r.gc0, r.cell, r.nx, r.ny, r.nodeCell, intArrayOf(r.gr0 + 11, r.nodeGr[1], r.nodeGr[2], r.nodeGr[3]), r.nodeGc, r.nodeFlags,
            r.edgeA, r.edgeB, r.edgeFlags, r.edgePts)
        assertTrue(runCatching { bad.encode() }.isFailure)
        // an edge to a cell that is no forward neighbour (cell 1 -> cell 0: west)
        val back = ShippedNet.Roadmap(r.gr0, r.gc0, r.cell, r.nx, r.ny, r.nodeCell, r.nodeGr, r.nodeGc, r.nodeFlags,
            intArrayOf(1), intArrayOf(0), intArrayOf(0), arrayOf(null))
        assertTrue(runCatching { back.encode() }.isFailure)
    }

    @Test fun theJoinsJoinThePiecesTheGroundJoins() {
        // three pieces: A (0-1), B (2-3) 100 yd on from A, C (4-5) 900 yd away (too far); and a cave's way (6 mouth - 7 inside) by B
        val n = listOf(SafePath.Node(0, 0.0, 0.0, SafePath.POINT), SafePath.Node(1, 0.0, 50.0, SafePath.POINT),
            SafePath.Node(2, 0.0, 150.0, SafePath.POINT), SafePath.Node(3, 0.0, 200.0, SafePath.POINT),
            SafePath.Node(4, 0.0, 1100.0, SafePath.POINT), SafePath.Node(5, 0.0, 1150.0, SafePath.POINT),
            SafePath.Node(6, 30.0, 210.0, SafePath.MOUTH, "cave"), SafePath.Node(7, 31.0, 210.0, SafePath.CAVE, "cave"))
        val e = listOf(SafePath.Edge(0, 1, SafePath.ROAD), SafePath.Edge(2, 3, SafePath.ROAD), SafePath.Edge(4, 5, SafePath.ROAD),
            SafePath.Edge(6, 7, SafePath.CAVE))
        val net = SafePath.Net(1, 0, "", n, e, emptyList())
        // the ground: a walk of 1.2x the line, except between node 3 and the cave's mouth, which is walled (not reached)
        fun walled(a: DoubleArray, b: DoubleArray) = (a[1] == 200.0 && b[0] == 30.0) || (b[1] == 200.0 && a[0] == 30.0)
        val js = ShippedNet.joins(net, { a, b ->
            if (walled(a, b)) listOf(a, b) to false
            else listOf(a, doubleArrayOf((a[0] + b[0]) / 2 + 0.33 * Math.hypot(b[0] - a[0], b[1] - a[1]), (a[1] + b[1]) / 2), b) to true })
        val pairs = js.map { setOf(it.first, it.second) }
        assertTrue(setOf(1, 2) in pairs)              // A and B by their nearest ends
        assertTrue(setOf(2, 6) in pairs)              // the cave's mouth joined, by node 2 (node 3's way to it is walled)
        assertTrue(pairs.none { 4 in it || 5 in it }) // C: too far
        assertTrue(pairs.none { 7 in it })            // never from a cave's inside
        assertEquals(2, js.size)
    }

    @Test fun specsSharingAMapMergeIntoOneSection() {
        // Alterac's network and Dalaran's over the same map: Dalaran's copy of a node within 1 yd (of the same kind) is that node
        val alt = ShippedNet.section(net(1f), H, 8, "alterac", links, ::wet)
        val dn = listOf(SafePath.Node(100, 100.4, 200.3, SafePath.HUB), SafePath.Node(101, 150.5, 210.25, SafePath.JUNCTION),
            SafePath.Node(102, 400.0, 400.0, SafePath.CAVE, "floor"))
        val de = listOf(SafePath.Edge(100, 101, SafePath.ROAD), SafePath.Edge(101, 102, SafePath.CAVE, SafePath.OK, "floor"))
        val dal = ShippedNet.section(SafePath.Net(1411, 1, "off-device", dn, de, listOf(SafePath.Exit(102, 1424, "portal", "Hillsbrad"))), H, 20, "dalaran_horde")
        val m = ShippedNet.merge(listOf(alt, dal), byteArrayOf(9))
        assertEquals("alterac+dalaran_horde", m.tag); assertEquals(listOf("alterac", "dalaran_horde"), m.tags)
        // nodes: Alterac's, and Dalaran's floor point (its hub and junction are Alterac's own)
        assertEquals(alt.net.nodes.size + 1, m.net.nodes.size)
        assertEquals("Razor Hill", m.net.nodes.first { it.id == 0 }.name)
        // ways: Dalaran's road 0-1 is Alterac's own (kept once); its floor way is new
        assertEquals(alt.net.edges.size + 1, m.net.edges.size)
        assertEquals("dalaran_horde", m.wayTagOf(m.net.edges.size - 1)); assertEquals("alterac", m.wayTagOf(0))
        assertEquals(alt.net.exits.size + 1, m.net.exits.size); assertEquals("dalaran_horde", m.exitTagOf(m.net.exits.size - 1))
        assertEquals(20, m.level); assertArrayEquals(byteArrayOf(9), m.roadmapDanger)
        assertEquals(m.net.edges.size, m.ways.size)
        // written and read: the tags kept
        val p = ShippedNet.read(ByteArrayInputStream(ShippedNet.bytes(ShippedNet.Pack(ShippedNet.Header(1411, 1, 5, "c", "b", "t"), listOf(m)))))!!
        assertEquals("dalaran_horde", p.sections[0].wayTagOf(m.net.edges.size - 1)); assertEquals("alterac", p.sections[0].exitTagOf(0))
    }

    @Test fun joinAndFloorWaysKeepTheirPointsAndLayers() {
        // a join (node 5 -> 7: two pieces) and a floor street (a new floor node 50, its points on layers 0, 1, 1)
        val floor = SafePath.Node(50, 300.0, 120.0, SafePath.CAVE, "floor")
        val s = ShippedNet.section(net(1f), H, 8, "dalaran", extraNodes = listOf(floor), extras = listOf(
            ShippedNet.Extra(5, 7, SafePath.TRAIL, ShippedNet.F_JOIN or ShippedNet.F_TRAIL, listOf(doubleArrayOf(300.0, 100.0), doubleArrayOf(150.0, 300.0), doubleArrayOf(50.0, 400.0)), 0.2f),
            ShippedNet.Extra(5, 50, SafePath.CAVE, ShippedNet.F_FLOOR or ShippedNet.F_CAVE, listOf(doubleArrayOf(300.0, 100.0), doubleArrayOf(300.0, 110.0), doubleArrayOf(300.0, 120.0)),
                0f, "floor", intArrayOf(0, 1, 1))))
        val p = ShippedNet.read(ByteArrayInputStream(ShippedNet.bytes(ShippedNet.Pack(ShippedNet.Header(1416, 0, 5, "c", "b", "t"), listOf(s)))))!!.sections[0]
        val n = p.net.edges.size
        assertEquals(listOf("trail", "join"), ShippedNet.flagNames(p.ways[n - 2].flags))
        assertEquals("join", p.net.edges[n - 2].src); assertEquals(0.2f, p.net.edges[n - 2].danger, 0.005f)
        assertEquals(3, p.ways[n - 2].pts!!.size); assertNull(p.ways[n - 2].layers)
        assertEquals(Math.hypot(150.0, 200.0) + Math.hypot(100.0, 100.0), p.ways[n - 2].yd, 0.051)
        assertEquals(listOf("cave", "floor"), ShippedNet.flagNames(p.ways[n - 1].flags))
        assertArrayEquals(intArrayOf(0, 1, 1), p.ways[n - 1].layers)
        assertTrue(p.net.nodes.any { it.id == 50 && it.src == "floor" })
    }

    @Test fun stitchesRoundTripAndFollowAMerge() {
        val st = listOf(ShippedNet.Stitch(2, 3, ShippedNet.F_JOIN, listOf(doubleArrayOf(200.0, 260.0), doubleArrayOf(210.5, 270.25), doubleArrayOf(220.0, 280.0))))
        val s = ShippedNet.section(net(1f), H, 8, "durotar").copy(stitches = st)
        val p = ShippedNet.read(ByteArrayInputStream(ShippedNet.bytes(ShippedNet.Pack(ShippedNet.Header(1411, 1, 5, "c", "b", "t"), listOf(s), roadmap()))))!!
        val b = p.sections[0].stitches.single()
        assertEquals(listOf(2, 3, ShippedNet.F_JOIN), listOf(b.node, b.rm, b.flags)); assertEquals(3, b.pts.size); assertEquals(270.25, b.pts[1][1], 1e-9)
        assertEquals(st[0].yd, b.yd, 0.02)
        // merged after another section: the stitch's node is the merged node (the first section's own ids kept, the second's mapped)
        val other = ShippedNet.section(SafePath.Net(1411, 1, "", listOf(SafePath.Node(70, 200.2, 260.1, SafePath.FLIGHT), SafePath.Node(71, 0.0, 0.0, SafePath.POINT)),
            listOf(SafePath.Edge(70, 71, SafePath.ROAD)), emptyList()), H, 8, "x").copy(stitches = listOf(ShippedNet.Stitch(70, 1, 0, st[0].pts)))
        val m = ShippedNet.merge(listOf(s, other))
        assertEquals(listOf(2 to 3, 2 to 1), m.stitches.map { it.node to it.rm })
    }

    @Test fun notNet1IsNull() {
        assertNull(ShippedNet.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        val later = pack().toJson().put("v", ShippedNet.VERSION + 1).toString().toByteArray()
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(later) } }.toByteArray()
        assertNull(ShippedNet.read(ByteArrayInputStream(gz)))
        val net = SafePath.Net(1, 0, "", emptyList(), emptyList(), emptyList()).toJson().toString().toByteArray()
        val gz2 = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(net) } }.toByteArray()
        assertNull(ShippedNet.read(ByteArrayInputStream(gz2)))
    }
}
