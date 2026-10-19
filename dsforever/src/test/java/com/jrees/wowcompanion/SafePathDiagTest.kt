package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

/** SafePath's WCMAP diagnostics (SafePathDiag, the plan's step 13): the coverage line, the harvest and plan timings, their rate limits */
class SafePathDiagTest {
    private val said = ArrayList<String>()
    private var now = 1_000_000_000L
    private lateinit var root: File

    @Before fun setUp() {
        SafePathDiag.reset(); SafePathDiag.sink = { said.add(it) }; SafePathDiag.clock = { now }
        root = Files.createTempDirectory("spdiag").toFile()
    }
    @After fun tearDown() { root.deleteRecursively(); SafePathDiag.reset() }

    private fun file(rel: String, ageMs: Long, write: (File) -> Unit) = File(root, rel).also { it.parentFile.mkdirs(); write(it); it.setLastModified(now - ageMs) }
    private fun clb(map: Int, magic: Int, version: Int, ageMs: Long) = file("terrain/$map.clb", ageMs) { f ->
        DataOutputStream(GZIPOutputStream(f.outputStream())).use { it.writeInt(magic); it.writeInt(version); it.write(ByteArray(64)) } }
    private fun water(map: Int, ageMs: Long) = file("safepath/water/$map.bin", ageMs) { f ->
        DataOutputStream(DeflaterOutputStream(f.outputStream())).use { it.writeInt(SafePath.Water.MAGIC); it.write(ByteArray(16)) } }
    private fun zones(map: Int, ageMs: Long) = file("safepath/zones/$map.bin", ageMs) { f ->
        DataOutputStream(f.outputStream()).use { it.writeInt(SafePath.Zones.MAGIC); it.write(ByteArray(16)) } }

    @Test fun theCoverageLineSaysEachZonesFilesVersionsAndAges() {
        clb(1411, Climb.Grid.MAGIC, Climb.RULES_VERSION, 2 * 3_600_000L); water(1411, 3 * 86_400_000L); zones(1411, 3 * 86_400_000L)
        clb(1412, Climb.Grid.MAGIC, Climb.RULES_VERSION - 1, 60_000L)          // made by older rules
        clb(1413, Climb.Grid.MAGIC_CLB2, 0, 60_000L)                          // the old format
        File(root, "safepath/1411.draft.json").writeText("{}")
        val (line, _) = SafePathDiag.coverageLine(root, listOf(1413, 1411, 1412), now)
        assertTrue(line, line.startsWith("safepath coverage: 3 maps (rules ${Climb.RULES_VERSION}); lattice 1/3, water 1/3, zones 1/3, draft 1/3 | 1411 "))
        assertTrue(line, "1411 lattice clb3 r${Climb.RULES_VERSION} 2h " in line)
        assertTrue(line, "water wtr2 3d" in line); assertTrue(line, "zones zns2 3d" in line)
        assertTrue(line, "1412 lattice clb3 r${Climb.RULES_VERSION - 1} OLD 1m" in line)
        assertTrue(line, "1413 lattice clb2 OLD 1m" in line)
        assertTrue(line, "1412 lattice clb3 r${Climb.RULES_VERSION - 1} OLD 1m 0B, water -, zones -, draft -" in line.replace(Regex("""1m \d+B"""), "1m 0B"))
        // the probe's own answers
        val p = SafePathDiag.probe(root, 1411, now)
        assertEquals(listOf("lattice", "water", "zones", "draft"), p.map { it.kind })
        assertTrue(p.all { it.present && it.current })
        assertEquals(Climb.RULES_VERSION, p[0].version)
        // a damaged file: there, but unreadable
        File(root, "terrain/1499.clb").writeText("not gzip")
        val d = SafePathDiag.probe(root, 1499, now)[0]
        assertTrue(d.present); assertFalse(d.current); assertEquals(null, d.format)
        assertTrue(SafePathDiag.coverageLine(root, listOf(1499), now).first, "1499 lattice unreadable" in SafePathDiag.coverageLine(root, listOf(1499), now).first)
    }

    @Test fun theCoverageLineIsSaidOnChangeOrEveryTenMinutes() {
        clb(1411, Climb.Grid.MAGIC, Climb.RULES_VERSION, 0)
        assertTrue(SafePathDiag.coverage(root, listOf(1411)))
        now += 60_000
        assertFalse("the same files a minute later: held", SafePathDiag.coverage(root, listOf(1411)))
        water(1411, 0)
        assertTrue("a new file: said at once", SafePathDiag.coverage(root, listOf(1411)))
        now += SafePathDiag.COVERAGE_MS
        assertTrue("ten minutes on: said again", SafePathDiag.coverage(root, listOf(1411)))
        assertTrue("forced", SafePathDiag.coverage(root, listOf(1411), force = true))
        assertFalse("no root (no harvest yet): nothing", SafePathDiag.coverage(null, listOf(1411)))
        assertEquals(4, said.size)
    }

    @Test fun harvestTimingsAreRateLimitedAndCountWhatTheyHeldBack() {
        assertTrue(SafePathDiag.harvestStep("climb", 1411, 3200, 2_200_000))
        assertEquals("safepath harvest: climb 1411 in 3200 ms, 2.1MB", said.last())
        now += 1000; assertFalse(SafePathDiag.harvestStep("climb", 1412, 9000))
        now += 1000; assertFalse(SafePathDiag.harvestStep("climb", 1413, 4000))
        assertTrue("another step has its own limit", SafePathDiag.harvestStep("safepathzones", 1411, 800))
        now += 1000; assertTrue("a failure is always said", SafePathDiag.harvestStep("climb", 1414, 50, why = "no root ADT"))
        assertEquals("safepath harvest: climb 1414 FAILED after 50 ms: no root ADT (+2 not logged, slowest 9000 ms)", said.last())
        now += SafePathDiag.TIMING_MS; assertTrue(SafePathDiag.harvestStep("climb", 1415, 100))
        assertEquals("safepath harvest: climb 1415 in 100 ms", said.last())
    }

    @Test fun planTimingsSayTheWindowTheScratchAndTheBudget() {
        assertTrue(SafePathDiag.plan(1411, 42, 420_000, 9_800_000, true, "quest 25"))
        assertEquals("safepath plan: map 1411 quest 25 42 ms, window 0.42 M vertices, scratch peak 9.3MB, reached", said.last())
        now += 500; assertFalse("held: within 5 s on the same map", SafePathDiag.plan(1411, 300, 500_000, 1_000_000, true))
        assertTrue("another map has its own limit", SafePathDiag.plan(1412, 200, 100_000, -1, false))
        assertEquals("safepath plan: map 1412 200 ms, window 0.10 M vertices, scratch ?, NOT reached slow", said.last())
        now += 500; assertTrue("over the worst-case budget: always said", SafePathDiag.plan(1411, 1500, 1_200_000, 30L * 1024 * 1024, true))
        assertEquals("safepath plan: map 1411 1500 ms, window 1.20 M vertices, scratch peak 30.0MB, reached OVER (+1 not logged, slowest 300 ms)", said.last())
        now += 500; assertTrue("a scratch over the cap alone is OVER", SafePathDiag.plan(1411, 10, 1000, SafePathDiag.SCRATCH_CAP_BYTES + 1, true))
        assertTrue(said.last(), said.last().endsWith(" OVER"))
    }

    @Test fun ageAndSizeWords() {
        assertEquals("40s", SafePathDiag.age(40_000)); assertEquals("12m", SafePathDiag.age(12 * 60_000L))
        assertEquals("5h", SafePathDiag.age(5 * 3_600_000L)); assertEquals("3d", SafePathDiag.age(3 * 86_400_000L))
        assertEquals("812B", SafePathDiag.size(812)); assertEquals("40KB", SafePathDiag.size(40 * 1024)); assertEquals("2.1MB", SafePathDiag.size(2_200_000))
    }
}
