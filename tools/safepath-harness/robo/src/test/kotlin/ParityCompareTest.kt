import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The parity test's comparison (ParityCompare) on its own: the metrics on lines whose answers are known, the py checks' verdicts, and
 * two harness runs of one zone - the same run twice is identical, a hand-perturbed copy fails exactly where it was perturbed.
 *   SAFEPATH_TEST=ParityCompareTest bash tools/safepath-harness/run.sh "" "PARITY|TWICE|PERTURB"
 * Env: PARITY_A, PARITY_B (two harness out folders; default <work>/out-P/run1, run2), PARITY_TAG (default durotar:kalimdor),
 * PARITY_PY=0 (no py checks on the copy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ParityCompareTest {
    private val out = File(System.getProperty("out.dir") ?: "out").also { it.mkdirs() }
    private val work = File(System.getenv("SAFEPATH_WORK") ?: "E:/safepath-work")
    private fun r(letter: String, reached: Boolean, vararg p: Double) = ParityRoute(letter, "route $letter", reached, p.toList().chunked(2).map { doubleArrayOf(it[0], it[1]) })
    private fun kinds(l: ParityCompare.Line) = l.fails.map { it.substringBefore(' ').substringBefore(':') }.toSet()

    @Test fun hausdorffOnKnownLines() {
        val a = r("A", true, 0.0, 0.0, 100.0, 0.0).pts
        assertEquals(0.0, ParityCompare.hausdorff(a, a), 1e-9)
        assertEquals("a line and its reverse", 0.0, ParityCompare.hausdorff(a, a.reversed()), 1e-9)
        assertEquals("the same line with a vertex in the middle", 0.0, ParityCompare.hausdorff(a, r("A", true, 0.0, 0.0, 50.0, 0.0, 100.0, 0.0).pts), 1e-9)
        assertEquals("parallel 5 yd apart", 5.0, ParityCompare.hausdorff(a, r("A", true, 0.0, 5.0, 100.0, 5.0).pts), 1e-9)
        // a 7 yd bump at the middle: the bump's tip 7 from a, a 7 from the bump only at the tip (directed a -> bump is less)
        val bump = r("A", true, 0.0, 0.0, 50.0, 7.0, 100.0, 0.0).pts
        assertEquals(7.0, ParityCompare.hausdorff(a, bump), 1e-9)
        assertTrue(ParityCompare.directed(a, bump) < 7.0)
        // one line shorter at its end: the missing 20 yd count (a's end is 20 from the shorter line's)
        assertEquals(20.0, ParityCompare.hausdorff(a, r("A", true, 0.0, 0.0, 80.0, 0.0).pts), 1e-9)
    }

    @Test fun eachToleranceFailsJustPastItsLimit() {
        val h = r("A", true, 0.0, 0.0, 500.0, 0.0, 1000.0, 0.0)
        assertTrue(ParityCompare.compareRoute(h, h).ok)
        // flags: one more is within tolerance, two more fails
        assertTrue(ParityCompare.compareRoute(h, r("A", true, 0.0, 0.0, 250.0, 0.0, 500.0, 0.0, 1000.0, 0.0)).ok)
        assertEquals(setOf("flags"), kinds(ParityCompare.compareRoute(h, r("A", true, 0.0, 0.0, 250.0, 0.0, 500.0, 0.0, 750.0, 0.0, 1000.0, 0.0))))
        // a spur: two flags more, its length and its reach all at once
        val spur = r("A", true, 0.0, 0.0, 500.0, 0.0, 500.0, 15.0, 500.0, 0.0, 1000.0, 0.0)              // 15 yd out and back: +3%
        assertEquals(setOf("flags", "length", "hausdorff"), kinds(ParityCompare.compareRoute(h, spur)))
        // length: a slightly bent line, longer by little, passes
        val long = r("A", true, 0.0, 0.0, 400.0, 3.0, 1000.0, 0.0)
        val pct = (long.yd - h.yd) / h.yd
        assertTrue("the bent line is longer by little: $pct", pct < 0.02); assertTrue(ParityCompare.compareRoute(h, long).ok)
        val h2 = r("A", true, 0.0, 0.0, 100.0, 0.0)
        val bent = r("A", true, 0.0, 0.0, 50.0, 7.5, 100.0, 0.0)                 // 2 x hypot(50, 7.5) = 101.1: +1.1%, under 8 yd
        assertTrue(ParityCompare.compareRoute(h2, bent).ok)
        val bent2 = r("A", true, 0.0, 0.0, 50.0, 7.9, 100.0, 0.0)                // flags +1 (ok), +1.2%, 7.9 yd: ok
        assertTrue(ParityCompare.compareRoute(h2, bent2).ok)
        val short = r("A", true, 0.0, 0.0, 97.0, 0.0)                            // -3%: length; 3 yd off
        assertEquals(setOf("length"), kinds(ParityCompare.compareRoute(h2, short)))
        // the Hausdorff: 8 yd passes, 8.1 fails
        val shifted8 = ParityRoute("A", "", true, h.pts.map { doubleArrayOf(it[0], it[1] + 8.0) })
        assertTrue(ParityCompare.compareRoute(h, shifted8).ok)
        assertEquals(setOf("hausdorff"), kinds(ParityCompare.compareRoute(h, ParityRoute("A", "", true, h.pts.map { doubleArrayOf(it[0], it[1] + 8.1) }))))
        // reached
        assertEquals(setOf("reached"), kinds(ParityCompare.compareRoute(h, ParityRoute("A", "", false, h.pts))))
    }

    @Test fun routesMissingOrExtraAreFailures() {
        val h = listOf(r("A", true, 0.0, 0.0, 10.0, 0.0), r("B", true, 0.0, 0.0, 0.0, 10.0))
        val rep = ParityCompare.compare("t", h, listOf(r("A", true, 0.0, 0.0, 10.0, 0.0), r("Z", true, 1.0, 1.0, 2.0, 2.0)), "none", "none")
        assertEquals(2, rep.fails.size)
        assertTrue(rep.fails.toString(), rep.fails.any { it.startsWith("t B: missing on the device") })
        assertTrue(rep.fails.toString(), rep.fails.any { it.startsWith("t Z: not in the harness's routes") })
        assertEquals(1, ParityCompare.compare("t", h, h, "B3-B4 (50 deg, 1 wall steps)", "none").fails.size)
        assertEquals(1, ParityCompare.compare("t", h, h, "none", "FAILED TO RUN").fails.size)
        assertEquals(0, ParityCompare.compare("t", h, h, null, null).fails.size)       // py checks not run: no verdict, no failure
    }

    @Test fun thePyChecksVerdicts() {
        assertEquals("none", ParityCompare.segVerdict("loading\n120 segments; crossing a 45+ degree step: none\n"))
        assertEquals("A3-A4 (50 deg, 2 wall steps)", ParityCompare.segVerdict("120 segments; crossing a 45+ degree step: A3-A4 (50 deg, 2 wall steps)"))
        assertNull(ParityCompare.segVerdict("Traceback (most recent call last):"))
        val wades = ArrayList<String>()
        val ok = "WET A: deepest 0.0 yd, 0 yd deeper than 1.5, 0 yd on decks; longest wade 0 yd, 0 yd deeper than 2.2\n" +
            "WET B: deepest 1.9 yd at 10,20, 4 yd deeper than 1.5, 0 yd on decks; longest wade 4 yd at 11,21, 0 yd deeper than 2.2"
        assertEquals("none", ParityCompare.wetVerdict(ok, 2, wades))
        assertEquals(1, wades.size)
        assertEquals("route C swims: 7 yd on end deeper than the ford at 5,6",
            ParityCompare.wetVerdict("WET C: deepest 2.0 yd at 5,5, 7 yd deeper than 1.5, 0 yd on decks; longest wade 7 yd at 5,6, 0 yd deeper than 2.2", 1))
        assertEquals("route D swims: 3 yd deeper than 2.2, 2.6 yd at 1,2",
            ParityCompare.wetVerdict("WET D: deepest 2.6 yd at 1,2, 3 yd deeper than 1.5, 0 yd on decks; longest wade 3 yd at 1,2, 3 yd deeper than 2.2", 1))
        assertNull(ParityCompare.wetVerdict("Error", 3))
    }

    @Test fun theNameBlindSpecLosesItsHandEnteredData() {
        val spec = org.json.JSONObject("""{"tag":"t","zone":1,"area":2,"lifts":[{"x":1}],"floorWmos":[5],"friendlyMinLevel":9999,
            "swims":[{"name":"Fenris Isle","island":{"node":"Fenris Isle","xy":[1,2]},"shore":{"xy":[3,4]}}]}""")
        val (b, gone) = NameBlind.strip(spec)
        assertTrue(!b.has("lifts") && !b.has("floorWmos")); assertEquals(9999, b.getInt("friendlyMinLevel"))
        assertEquals("""{"name":"Fenris Isle","island":{"node":"Fenris Isle"}}""", b.getJSONArray("swims").getJSONObject(0).toString())
        assertEquals(listOf("lifts", "floorWmos", "swims' coordinates"), gone)
        assertTrue("the original untouched", spec.has("lifts"))
    }

    @Test fun theRoutesFileRoundTripsInTheHarnessShape() {
        val rs = listOf(ParityRoute("A", "a → b", false, listOf(doubleArrayOf(1.5, 2.5), doubleArrayOf(3.0, 4.0, 1.0), doubleArrayOf(5.0, 6.0))))
        val f = File(out, "parity-unit/levels_routes_rt.json"); ParityCompare.writeRoutes(f, rs)
        val back = ParityCompare.readRoutes(f)
        assertEquals(1, back.size); assertEquals("a → b", back[0].name); assertEquals(false, back[0].reached)
        assertEquals(listOf(2, 3, 2), back[0].pts.map { it.size }); assertEquals(1.0, back[0].pts[1][2], 0.0)
        assertTrue(ParityCompare.compare("rt", rs, back).fails.isEmpty())
        // goals: a reached route's goal is its last point; an unreached one's the ROUTE2 line's
        val g = ParityCompare.goals(rs, "ROUTE2 A x -> y: 5 yd, 1 flags (the goal unreachable: to the nearest ground); goal 9,-12 h 3")
        assertEquals(listOf(1.5, 2.5), g[0].from.toList()); assertEquals(listOf(9.0, -12.0), g[0].to.toList())
    }

    // ---------------------------------------------------------------- two harness runs

    private fun twice(): Triple<File, File, Pair<String, String>>? {
        val (tag, dir) = (System.getenv("PARITY_TAG") ?: "durotar:kalimdor").let { it.substringBefore(':') to it.substringAfter(':') }
        val a = File(System.getenv("PARITY_A") ?: File(work, "out-P/run1").path); val b = File(System.getenv("PARITY_B") ?: File(work, "out-P/run2").path)
        if (!File(a, "levels_routes_$tag.json").isFile || !File(b, "levels_routes_$tag.json").isFile) return null
        return Triple(a, b, tag to dir)
    }

    @Test fun theSameZoneRunTwiceIsIdentical() {
        val t = twice(); assumeTrue("TWICE SKIPPED: no two harness runs (PARITY_A, PARITY_B)", t != null)
        val (a, b, td) = t!!; val (tag, dir) = td
        val ha = ParityCompare.readRoutes(File(a, "levels_routes_$tag.json")); val hb = ParityCompare.readRoutes(File(b, "levels_routes_$tag.json"))
        // the second run's routes written as the device's would be, the harness's floors beside them, then the py checks on that copy
        val df = File(out, "twice/levels_routes_$tag.json"); ParityCompare.writeRoutes(df, hb); ParityCompare.copyBeside(b, tag, df)
        val (seg, wet, notes) = if (System.getenv("PARITY_PY") == "0") Triple(null, null, emptyList()) else
            ParityCompare.checks(File(File(System.getProperty("user.dir")).parentFile, "py"), df, dir, work, hb.size)
        val rep = ParityCompare.compare(tag, ha, ParityCompare.readRoutes(df), seg, wet, notes)
        println(rep.text().lines().joinToString("\n") { "TWICE $it" })
        File(out, "twice_$tag.txt").writeText(rep.text() + "\n")
        assertTrue(rep.fails.toString(), rep.fails.isEmpty())
        assertTrue("every route identical", rep.lines.all { it.hausdorff < 1e-9 && Math.abs(it.harness!!.yd - it.device!!.yd) < 1e-9 && it.harness.flags == it.device.flags && it.harness.reached == it.device.reached })
    }

    @Test fun aHandPerturbedCopyFailsWhereItWasPerturbed() {
        val t = twice(); assumeTrue("PERTURB SKIPPED: no harness run (PARITY_A)", t != null)
        val (a, _, td) = t!!; val tag = td.first
        val h = ParityCompare.readRoutes(File(a, "levels_routes_$tag.json"))
        assertTrue("needs 7 routes, has ${h.size}", h.size >= 7)
        val L = h.map { it.letter }
        fun mid(p: DoubleArray, q: DoubleArray, u: Double) = doubleArrayOf(p[0] + (q[0] - p[0]) * u, p[1] + (q[1] - p[1]) * u)
        fun longest(r: ParityRoute) = (0 until r.pts.size - 1).maxByOrNull { Math.hypot(r.pts[it + 1][0] - r.pts[it][0], r.pts[it + 1][1] - r.pts[it][1]) }!!
        val expect = LinkedHashMap<String, Set<String>>()
        val d = h.mapIndexedNotNull { i, r -> when (i) {
            0 -> { expect[r.letter] = setOf("reached"); ParityRoute(r.letter, r.name, !r.reached, r.pts) }
            1 -> { expect[r.letter] = setOf("hausdorff"); ParityRoute(r.letter, r.name, r.reached, r.pts.map { doubleArrayOf(it[0] + 9.0, it[1]) }) }
            2 -> { expect[r.letter] = setOf("flags"); val k = longest(r)      // two points more on its longest segment, on the line itself
                ParityRoute(r.letter, r.name, r.reached, r.pts.take(k + 1) + listOf(mid(r.pts[k], r.pts[k + 1], 1 / 3.0), mid(r.pts[k], r.pts[k + 1], 2 / 3.0)) + r.pts.drop(k + 1)) }
            3 -> { expect[r.letter] = emptySet(); val k = longest(r)           // one point more: within the flags' tolerance
                ParityRoute(r.letter, r.name, r.reached, r.pts.take(k + 1) + listOf(mid(r.pts[k], r.pts[k + 1], 0.5)) + r.pts.drop(k + 1)) }
            4 -> { expect[r.letter] = setOf("flags", "length", "hausdorff"); val k = r.pts.size / 2   // a 30 yd spur and back at a middle point
                val p = r.pts[k]; ParityRoute(r.letter, r.name, r.reached, r.pts.take(k + 1) + listOf(doubleArrayOf(p[0] + 30.0, p[1]), p.copyOf()) + r.pts.drop(k + 1)) }
            5 -> { expect[r.letter] = setOf("missing"); null }
            else -> { expect[r.letter] = emptySet(); r }
        } } + ParityRoute("Z", "a route the harness hasn't", true, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0)))
        expect["Z"] = setOf("not")
        val rep = ParityCompare.compare(tag, h, d, "none", "none")
        println(rep.text().lines().joinToString("\n") { "PERTURB $it" })
        File(out, "perturb_$tag.txt").writeText(rep.text() + "\n")
        for (line in rep.lines) assertEquals("route ${line.letter}: ${line.fails}", expect[line.letter], kinds(line))
        assertEquals(L.size + 1, rep.lines.size)
        assertEquals(expect.values.count { it.isNotEmpty() }, rep.fails.size)
    }
}
