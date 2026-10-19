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

    private fun pack(): ShippedNet.Pack = ShippedNet.Pack(
        ShippedNet.Header(1411, 1, Climb.RULES_VERSION, "5682a5e", "25822068f8fce8b21bc714e7e3210481", "2026-10-19T12:00:00Z"),
        listOf(ShippedNet.section(net(1f), H, 8, "durotar", links, ::wet), ShippedNet.section(net(0.5f), A, 8, "durotar_alliance", emptyList(), ::wet)))

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
        assertEquals("NET1", p.header.format); assertEquals(1, p.header.version)
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
            for ((wa, wb) in a.ways.zip(b.ways)) assertEquals(wa.yd, wb.yd, 0.051)
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
