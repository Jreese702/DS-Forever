package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * Memory work #56, D1 (the user's yes 2026-11-19): the SafePath networks loaded lazily, one cache shared by the in-zone route and the
 * cross-zone legs (the zone the player is in and its neighbours kept), counted and let go under pressure, no start-up parse of every
 * file - and cross-zone planning still the same. Off-device: a small synthetic world (ShippedCrossTest's way: NET1 files built and read
 * back) and the 45 shipped files themselves.
 */
class MemDsSafePathTest {
    private val A = TransportRoutes.ALLIANCE

    // ---------------------------------------------------------------- the shared cache's bound (RouteWork.KeepLru)

    @Test fun theCacheKeepsThePlayersZoneAndItsNeighboursWhileOtherZonesComeAndGo() {
        var keep = listOf(1)
        val c = RouteWork.KeepLru<String>(3) { keep }
        c.put(1, "player"); c.put(2, "b"); c.put(3, "c")
        assertEquals(listOf(2), c.put(4, "d"))                    // the least recently used that is not kept goes, not the player's
        assertEquals(listOf(3), c.put(5, "e"))
        assertEquals(setOf(1, 4, 5), c.keys().toSet())
        keep = listOf(1, 4, 5)                                     // every one kept: the bound still holds, the newest stays
        assertEquals(listOf(1), c.put(6, "f"))
        assertEquals(3, c.size)
        assertNotNull(c.get(6))
        assertEquals(listOf("d", "e"), c.dropAllBut(listOf(6)).sorted())
        assertEquals(listOf(6), c.keys())
    }

    @Test fun aGetMakesAZoneRecentAndAPeekDoesNot() {
        val c = RouteWork.KeepLru<String>(2)
        c.put(1, "a"); c.put(2, "b")
        c.peek(1)
        assertEquals(listOf(1), c.put(3, "c"))                     // the peek did not keep 1
        c.get(2)
        assertEquals(listOf(3), c.put(4, "d"))                     // the get kept 2
    }

    @Test fun noBoundMeansNoneGo() {
        val c = RouteWork.KeepLru<String>(0)
        for (i in 1..50) assertTrue(c.put(i, "z$i").isEmpty())
        assertEquals(50, c.size)
    }

    // ---------------------------------------------------------------- what the pressure lets go

    @Test fun thePressureKeepsOnlyThePlayersZoneThenNone() {
        assertNull(ShippedNets.keepAt(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, 1429))
        assertEquals(listOf(1429), ShippedNets.keepAt(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 1429))     // every relief's level
        assertEquals(listOf(1429), ShippedNets.keepAt(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, 1429))
        assertEquals(emptyList<Int>(), ShippedNets.keepAt(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, 0))   // no zone yet
        for (l in listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, MemWatch.LOW_MEMORY, ComponentCallbacks2.TRIM_MEMORY_MODERATE,
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE)) assertEquals(emptyList<Int>(), ShippedNets.keepAt(l, 1429))
        assertEquals(MemWatch.RELIEF_LEVEL, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    }

    @Test fun aZonesNeighboursAreTheMapsItsExitsLeadInto() {
        val l = ShippedNets.Loaded(pack(1453, listOf(1429 to 990.0, 1455 to 10.0), emptyList()), null)
        assertEquals(setOf(1429, 1455), l.neighbours)
        assertTrue(l.bytes > 0)
    }

    // ---------------------------------------------------------------- lazy: nothing read until a cross-zone plan asks

    @Test fun theCrossZoneGraphIsReadOnlyWhenAskedAndOnceFromTheCacheAfter() {
        val world = synthetic()
        val opened = ArrayList<String>()
        val open: (String) -> java.io.InputStream? = { p -> opened.add(p); world.files[p]?.let { ByteArrayInputStream(it) } }
        val dir = Files.createTempDirectory("slim").toFile()
        try {
            val cache = File(dir, ShippedCrossStore.CACHE_FILE)
            val stamp = ShippedCrossStore.stampOf(1234L, world.maps)
            assertNull(ShippedCrossStore.readCache(cache, stamp))                                  // the first time: no cache file
            assertTrue(opened.isEmpty())
            val packs = ShippedCrossStore.slimPacks(world.maps, open) {}
            assertEquals(world.maps.sorted().map { ShippedNet.assetPath(it) }, opened)              // each file once, one at a time
            ShippedCrossStore.writeCache(cache, stamp, packs)
            opened.clear()
            val again = ShippedCrossStore.readCache(cache, stamp)!!                                // the next time: the small file only
            assertTrue(opened.isEmpty())
            assertEquals(sig(packs), sig(again))
            assertNull(ShippedCrossStore.readCache(cache, ShippedCrossStore.stampOf(1235L, world.maps)))    // another install: read again
            assertNull(ShippedCrossStore.readCache(cache, ShippedCrossStore.stampOf(1234L, world.maps + 9999)))
            // the slim zones hold the graph only: the towns' hubs are not in it
            assertTrue(again.all { p -> p.sections.all { s -> s.net.nodes.none { it.kind == SafePath.HUB } } })
        } finally { dir.deleteRecursively() }
    }

    @Test fun aZoneFromAnotherBuildOrRulesIsMarkedStaleAndStillUsed() {
        val world = synthetic()
        val packs = ShippedCrossStore.slimPacks(world.maps, { world.files[it]?.let { b -> ByteArrayInputStream(b) } }) {}
        val (zs, stale) = ShippedCrossStore.zonesOf(packs, "build-test")
        assertTrue(stale.isEmpty())
        assertEquals(world.maps, zs.getValue(A).keys)
        val (zs2, stale2) = ShippedCrossStore.zonesOf(packs, "build-other")
        assertEquals(world.maps.sorted(), stale2.sorted())
        assertTrue(zs2.getValue(A).values.all { it.stale })
    }

    // ---------------------------------------------------------------- cross-zone planning still works over the lazy graph

    @Test fun aCrossZonePlanOverTheLazyGraphIsTheEagerOnesAndItsLegsLoadOnlyTheChainsZones() {
        val world = synthetic()
        val dir = Files.createTempDirectory("slim").toFile()
        try {
            val cache = File(dir, ShippedCrossStore.CACHE_FILE)
            val stamp = ShippedCrossStore.stampOf(1L, world.maps)
            ShippedCrossStore.writeCache(cache, stamp, ShippedCrossStore.slimPacks(world.maps, { world.files[it]?.let { b -> ByteArrayInputStream(b) } }) {})
            val lazyZones = ShippedCrossStore.zonesOf(ShippedCrossStore.readCache(cache, stamp)!!, "build-test").first.getValue(A).values
            // the old way: every file read whole at the start and cut to its slim sections
            val eager = world.maps.map { m -> ShippedCross.Zone(m, ShippedCross.slim(world.pack(m).section(A)!!)) }
            val loads = ArrayList<Int>()
            val cacheOfWholes = RouteWork.KeepLru<ShippedNet.Pack>(ShippedNets.KEEP)
            val whole: (Int) -> ShippedNet.Pack? = { m -> cacheOfWholes.get(m) ?: world.pack(m).also { loads.add(m); cacheOfWholes.put(m, it) } }
            fun plan(zs: Collection<ShippedCross.Zone>, legs: ShippedCross.LegYards?) = ShippedCross.plan(ShippedCross.inputs(world.base(), zs),
                TravelLinks.Character(A), 1453, world.at(1453)[0], world.at(1453)[1], 1437, world.at(1437)[0], world.at(1437)[1],
                zs.map { it.map }.toSet(), emptySet(), legs)
            val before = plan(eager, null)!!
            val legs = ShippedCrossStore.legYards(A, { m -> m in world.maps }, whole)
            val after = plan(lazyZones, legs)!!
            assertEquals(before.route.keys, after.route.keys)
            assertEquals(listOf("w:1453>1429", "w:1429>1437"), after.route.keys)
            assertTrue(after.covered)
            assertTrue(after.legs.first().onNet)                                                    // the leg walked on the zone's own network
            assertEquals(setOf(1453, 1429, 1437), loads.toSet())                                    // only the chain's zones, never 1455
            assertFalse(1455 in loads)
        } finally { dir.deleteRecursively() }
    }

    // ---------------------------------------------------------------- the 45 shipped files

    private fun assetDir(): File? = listOf("../companion/app/src/main/assets/safepath/net", "companion/app/src/main/assets/safepath/net")
        .map(::File).firstOrNull { it.isDirectory }

    @Test fun theShippedFilesCacheGivesTheSameGraphAsReadingEveryFile() {
        val dir = assetDir() ?: return                                    // (a checkout without the assets)
        val ids = ShippedCrossStore.listIds(dir.list()!!.toList())
        assertEquals(45, ids.size)
        val open: (String) -> java.io.InputStream? = { p -> File(dir.parentFile.parentFile, p).takeIf { it.isFile }?.inputStream() }
        val packs = ShippedCrossStore.slimPacks(ids, open) {}
        assertEquals(45, packs.size)
        val tmp = Files.createTempDirectory("slim").toFile()
        try {
            val f = File(tmp, ShippedCrossStore.CACHE_FILE)
            ShippedCrossStore.writeCache(f, "s", packs)
            assertTrue("the cache file is small: ${f.length()}", f.length() < 2L * 1024 * 1024)
            val back = ShippedCrossStore.readCache(f, "s")!!
            for (faction in listOf(A, TransportRoutes.HORDE)) {
                val a = ShippedCrossStore.zonesOf(packs, null).first.getValue(faction).values
                val b = ShippedCrossStore.zonesOf(back, null).first.getValue(faction).values
                val base = TravelLinks.Inputs(mapAt = { _, _, _ -> null }, mapName = { "map $it" }, nets = emptyList())
                fun links(zs: Collection<ShippedCross.Zone>) = TravelLinks.build(ShippedCross.inputs(base, zs), TravelLinks.Character(faction)).links
                    .map { "${it.key}|${it.fromMap}>${it.toMap}|${it.ax},${it.ay}|${it.yd}|${it.avoid}|${it.words}" }.sorted()
                assertEquals(links(a), links(b))
                assertTrue(links(a).isNotEmpty())
            }
        } finally { tmp.deleteRecursively() }
    }

    @Test fun aZonesEstimatedBytesAreWithinAFactorOfTwoOfItsHeap() {
        val dir = assetDir() ?: return
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(30) }; return rt.totalMemory() - rt.freeMemory() }
        var est = 0L; var heap = 0L
        // the four biggest and four small ones (the heap measure is a JVM's, not ART's: a sum over several, not one by one)
        val files = dir.listFiles()!!.sortedByDescending { it.length() }.let { it.take(4) + it.takeLast(4) }
        val kept = ArrayList<ShippedNet.Pack>()
        for (f in files) {
            val b0 = used()
            val p = ShippedNet.read(f.inputStream())!!
            kept.add(p)
            heap += used() - b0
            est += ShippedNets.estimate(p)
        }
        assertTrue("estimate $est vs heap $heap", est in heap / 2..heap * 2)
        assertTrue(kept.size == 8)
    }

    // ---------------------------------------------------------------- the synthetic world

    /** zones on a line, 1000 yd boxes: 1453 -> 1429 -> 1437, and 1453 -> 1455 off to the side */
    private class World(val files: Map<String, ByteArray>, val maps: Set<Int>, val boxes: Map<Int, DoubleArray>, val pack: (Int) -> ShippedNet.Pack) {
        fun at(m: Int) = doubleArrayOf(boxes.getValue(m)[0] + 500.0, boxes.getValue(m)[1] + 500.0)
        fun mapAt(x: Double, y: Double): Int? = boxes.entries.firstOrNull { (_, b) -> x >= b[0] && x <= b[0] + 1000 && y >= b[1] && y <= b[1] + 1000 }?.key
        fun base() = TravelLinks.Inputs(mapAt = { _, x, y -> mapAt(x, y) }, mapName = { "map $it" }, nets = emptyList())
    }
    private val boxes = mapOf(1453 to doubleArrayOf(0.0, 0.0), 1429 to doubleArrayOf(1000.0, 0.0), 1437 to doubleArrayOf(2000.0, 0.0),
        1455 to doubleArrayOf(0.0, 1000.0))

    /** zone [map]'s NET1 file: a hub, an exit node per (to, x offset) on the box's line, a section a faction */
    private fun pack(map: Int, exits: List<Pair<Int, Double>>, starts: List<Int>): ShippedNet.Pack {
        val b = boxes.getValue(map)
        val nodes = arrayListOf(SafePath.Node(1, b[0] + 500.0, b[1] + 500.0, SafePath.HUB, name = "the town"))
        val edges = ArrayList<SafePath.Edge>(); val xs = ArrayList<SafePath.Exit>()
        var id = 2
        for ((to, dx) in exits) {
            val tb = boxes.getValue(to)
            // the exit on the shared edge: towards the neighbour's box
            val ex = if (tb[0] != b[0]) (if (tb[0] > b[0]) b[0] + 999.0 else b[0] + 1.0) else b[0] + dx
            val ey = if (tb[1] != b[1]) (if (tb[1] > b[1]) b[1] + 999.0 else b[1] + 1.0) else b[1] + 500.0
            nodes.add(SafePath.Node(id, ex, ey, SafePath.EXIT))
            edges.add(SafePath.Edge(1, id, SafePath.ROAD, SafePath.OK))
            xs.add(SafePath.Exit(id, to, "walk", "to map $to")); id++
        }
        val net = SafePath.Net(map, 0, "off-device", nodes, edges, xs)
        val p = ShippedNet.Pack(ShippedNet.Header(map, 0, Climb.RULES_VERSION, "test", "build-test", "2026-11-19T00:00:00Z"),
            listOf(TransportRoutes.HORDE, A).map { ShippedNet.section(net, it, 10, "t$map") })
        return ShippedNet.read(ShippedNet.bytes(p).inputStream())!!
    }
    private fun synthetic(): World {
        val links = mapOf(1453 to listOf(1429 to 0.0, 1455 to 0.0), 1429 to listOf(1453 to 0.0, 1437 to 0.0), 1437 to listOf(1429 to 0.0),
            1455 to listOf(1453 to 0.0))
        val packs = links.mapValues { (m, l) -> pack(m, l, emptyList()) }
        return World(packs.map { (m, p) -> ShippedNet.assetPath(m) to ShippedNet.bytes(p) }.toMap(), packs.keys, boxes) { packs.getValue(it) }
    }
    private fun sig(ps: List<ShippedNet.Pack>) = ps.joinToString("\n") { it.toJson().toString() }
}
