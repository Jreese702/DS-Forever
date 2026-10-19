import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * THE PARITY TEST'S COMPARISON (ParityDevice; the plan's §6.2): one zone's routes as the device planned them against the harness's own
 * (levels_routes_<tag>.json), route by route by letter:
 * - the goal reached by both or by neither;
 * - the flags (a route's points less its two ends, as ROUTE2 counts them) within [FLAGS_TOL];
 * - the length within [LENGTH_TOL] of the harness's;
 * - the two lines' symmetric Hausdorff distance at most [HAUSDORFF_YD];
 * - the device's routes through py/segcheck.py ("none": no 45+ degree step crossed) and py/wetcheck.py (no swim: deeper than the
 *   narrow-stream limit, or deeper than the ford for more than 6 yd on end - gate.py's own reading).
 * Every difference is a failure the report names; nothing is loosened.
 */
object ParityCompare {
    const val FLAGS_TOL = 1
    const val LENGTH_TOL = 0.02
    const val HAUSDORFF_YD = 8.0
    /** the whole parity run's budget (the plan: "<5 min") */
    const val BUDGET_MS = 5 * 60_000L
    /** the line's points sampled this far apart for the Hausdorff distance (each vertex too) */
    const val SAMPLE_YD = 1.0
    // the user's narrow-stream ruling (gate.py: NARROW, NARROW_YD)
    const val NARROW = 2.2
    const val NARROW_YD = 6

    // ---------------------------------------------------------------- the routes' files

    fun readRoutes(f: File): List<ParityRoute> = readRoutes(JSONObject(f.readText()))
    fun readRoutes(o: JSONObject): List<ParityRoute> {
        val a = o.optJSONArray("routes") ?: return emptyList()
        return (0 until a.length()).map { i -> val r = a.getJSONObject(i); val ps = r.getJSONArray("pts")
            ParityRoute(r.getString("letter"), r.optString("name"), r.optBoolean("reached", true),
                (0 until ps.length()).map { k -> val p = ps.getJSONArray(k); DoubleArray(p.length()) { p.getDouble(it) } }) }
    }
    /** routes in the harness's own shape ({"routes": [{letter, name, reached, pts}]}: the py checks read it) */
    fun toJson(rs: List<ParityRoute>): JSONObject = JSONObject().put("routes", JSONArray(rs.map { r ->
        JSONObject().put("letter", r.letter).put("name", r.name).put("reached", r.reached)
            .put("pts", JSONArray(r.pts.map { p -> JSONArray(if (p.size > 2 && p[2] != 0.0) listOf(p[0], p[1], p[2].toInt()) else listOf(p[0], p[1])) })) }))
    fun writeRoutes(f: File, rs: List<ParityRoute>) { f.parentFile?.mkdirs(); f.writeText(toJson(rs).toString()) }

    /** the harness's goals for the device: each route's start (its first point) and goal - its last point when reached, else the goal
     *  the harness's ROUTE2 line names ([log]: the harness's output, full_<tag>.xml; its last point when there is none) */
    fun goals(harness: List<ParityRoute>, log: String?): List<ParityGoal> {
        val said = HashMap<String, DoubleArray>()
        log?.lineSequence()?.forEach { l -> Regex("""ROUTE2 (\w) .*; goal (-?\d+),(-?\d+)""").find(l)?.let { m ->
            said[m.groupValues[1]] = doubleArrayOf(m.groupValues[2].toDouble(), m.groupValues[3].toDouble()) } }
        return harness.filter { it.pts.isNotEmpty() }.map { r ->
            ParityGoal(r.letter, r.name, r.pts.first().copyOf(2), if (r.reached) r.pts.last().copyOf(2) else said[r.letter] ?: r.pts.last().copyOf(2), r.reached) }
    }

    // ---------------------------------------------------------------- the metrics

    private fun segDist(px: Double, py: Double, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
        val u = if (l2 <= 0) 0.0 else (((px - a[0]) * dx + (py - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(a[0] + u * dx - px, a[1] + u * dy - py)
    }
    /** the farthest any point of line [a] (each vertex, and every [SAMPLE_YD] along it) lies from line [b] */
    fun directed(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        if (a.isEmpty() || b.isEmpty()) return Double.POSITIVE_INFINITY
        fun toB(x: Double, y: Double): Double = if (b.size == 1) hypot(b[0][0] - x, b[0][1] - y)
            else (0 until b.size - 1).minOf { segDist(x, y, b[it], b[it + 1]) }
        var worst = toB(a[0][0], a[0][1])
        for ((p, q) in a.zipWithNext()) {
            val n = maxOf(1, Math.ceil(hypot(q[0] - p[0], q[1] - p[1]) / SAMPLE_YD).toInt())
            for (s in 1..n) worst = maxOf(worst, toB(p[0] + (q[0] - p[0]) * s / n, p[1] + (q[1] - p[1]) * s / n))
        }
        return worst
    }
    /** the symmetric Hausdorff distance between two lines (x, y only) */
    fun hausdorff(a: List<DoubleArray>, b: List<DoubleArray>): Double = maxOf(directed(a, b), directed(b, a))

    /** one route's comparison */
    class Line(val letter: String, val name: String, val harness: ParityRoute?, val device: ParityRoute?, val hausdorff: Double, val fails: List<String>) {
        val ok get() = fails.isEmpty()
        fun text(): String {
            val h = harness; val d = device
            if (h == null || d == null) return "PARITY $letter $name: ${fails.joinToString("; ")}"
            val pct = if (h.yd > 0) 100.0 * (d.yd - h.yd) / h.yd else 0.0
            return "PARITY $letter $name: reached ${h.reached}/${d.reached}, flags ${h.flags}/${d.flags}, %d/%d yd (%+.1f%%), hausdorff %.1f yd: %s"
                .format(h.yd.toInt(), d.yd.toInt(), pct, hausdorff, if (ok) "ok" else "FAIL " + fails.joinToString("; "))
        }
        fun json(): JSONObject = JSONObject().put("letter", letter).put("name", name).put("ok", ok).put("fails", JSONArray(fails))
            .put("hausdorff", if (hausdorff.isFinite()) hausdorff else -1.0)
            .apply { harness?.let { put("harness", JSONObject().put("reached", it.reached).put("flags", it.flags).put("yd", it.yd)) }
                     device?.let { put("device", JSONObject().put("reached", it.reached).put("flags", it.flags).put("yd", it.yd)) } }
    }

    fun compareRoute(h: ParityRoute, d: ParityRoute): Line {
        val fails = ArrayList<String>()
        if (h.reached != d.reached) fails.add("reached: harness ${h.reached}, device ${d.reached}")
        if (Math.abs(h.flags - d.flags) > FLAGS_TOL) fails.add("flags ${h.flags} -> ${d.flags} (more than $FLAGS_TOL apart)")
        val tol = LENGTH_TOL * h.yd
        if (Math.abs(d.yd - h.yd) > maxOf(tol, 1e-6)) fails.add("length %.0f -> %.0f yd (%+.1f%%, over %.0f%%)".format(h.yd, d.yd, if (h.yd > 0) 100 * (d.yd - h.yd) / h.yd else 100.0, LENGTH_TOL * 100))
        val hd = hausdorff(h.pts, d.pts)
        if (!(hd <= HAUSDORFF_YD)) fails.add("hausdorff %.1f yd (over %.0f)".format(hd, HAUSDORFF_YD))
        return Line(h.letter, h.name, h, d, hd, fails)
    }

    /** a zone's report: its routes' lines, the py checks' verdicts on the device's routes, how long it took */
    class Report(val tag: String, val lines: List<Line>, val segcheck: String?, val wetcheck: String?, val notes: List<String>, val ms: Long) {
        val fails: List<String> get() = lines.filter { !it.ok }.map { "$tag ${it.letter}: ${it.fails.joinToString("; ")}" } +
            (if (segcheck != null && segcheck != "none") listOf("$tag: segcheck $segcheck") else emptyList()) +
            (if (wetcheck != null && wetcheck != "none") listOf("$tag: wetcheck $wetcheck") else emptyList())
        fun text(): String = (lines.map { it.text() } +
            listOf("PARITY $tag segcheck: ${segcheck ?: "not run"}", "PARITY $tag wetcheck: ${wetcheck ?: "not run"}") +
            notes.map { "PARITY $tag note: $it" } +
            "PARITY $tag: ${lines.count { it.ok }}/${lines.size} routes ok, ${fails.size} failures, ${ms} ms").joinToString("\n")
        fun json(): JSONObject = JSONObject().put("tag", tag).put("ms", ms).put("segcheck", segcheck ?: "not run").put("wetcheck", wetcheck ?: "not run")
            .put("routes", JSONArray(lines.map { it.json() })).put("fails", JSONArray(fails)).put("notes", JSONArray(notes))
    }

    /** the device's routes [device] against the harness's [harness], by letter ([segcheck], [wetcheck]: the py checks' verdicts on the device's) */
    fun compare(tag: String, harness: List<ParityRoute>, device: List<ParityRoute>, segcheck: String? = null, wetcheck: String? = null,
                notes: List<String> = emptyList(), ms: Long = 0): Report {
        val byD = device.associateBy { it.letter }
        val lines = harness.map { h -> byD[h.letter]?.let { compareRoute(h, it) }
            ?: Line(h.letter, h.name, h, null, Double.NaN, listOf("missing on the device")) } +
            device.filter { d -> harness.none { it.letter == d.letter } }.map { d -> Line(d.letter, d.name, null, d, Double.NaN, listOf("not in the harness's routes")) }
        return Report(tag, lines, segcheck, wetcheck, notes, ms)
    }

    // ---------------------------------------------------------------- the py checks

    /** segcheck's verdict from its output: "none", the crossings it lists, or null when it said nothing (a failed run) */
    fun segVerdict(out: String): String? = out.lineSequence().mapNotNull { Regex("""^\d+ segments; crossing a 45\+ degree step: (.*)$""").find(it.trim())?.groupValues?.get(1) }.lastOrNull()

    /** wetcheck's verdict from its WET lines, as gate.py reads them: "none", or each route that swims; null when no WET line came
     *  (with [routes] routes: a failed run). The narrow streams waded are [wades] (notes, not failures) */
    fun wetVerdict(out: String, routes: Int, wades: MutableList<String>? = null): String? {
        val swims = ArrayList<String>(); var n = 0
        for (l in out.lineSequence()) {
            val m = Regex("""^WET (\w): deepest ([\d.]+) yd(?: at (-?\d+,-?\d+))?, (\d+) yd deeper than .*?longest wade (\d+) yd(?: at (-?\d+,-?\d+))?, (\d+) yd deeper than""").find(l.trim()) ?: continue
            n++
            val (letter, deepest, at, deep, run, runAt, over) = m.destructured
            if (over.toInt() > 0) swims.add("route $letter swims: $over yd deeper than $NARROW, $deepest yd at $at")
            else if (run.toInt() > NARROW_YD) swims.add("route $letter swims: $run yd on end deeper than the ford at $runAt")
            else if (deep.toInt() > 0) wades?.add("route $letter wades a narrow stream: $deep yd deeper than the ford, at most $run yd on end")
        }
        if (n == 0 && routes > 0) return null
        return if (swims.isEmpty()) "none" else swims.joinToString("; ")
    }

    /** runs py/[script] on [routes] (cwd the py folder, ZDIR [zdir]); its output, or the failure's */
    fun runPy(py: File, script: String, routes: File, zdir: String, work: File, timeoutMin: Long = 10): String {
        val pb = ProcessBuilder(System.getenv("PYTHON") ?: "python", script, routes.absolutePath).directory(py).redirectErrorStream(true)
        pb.environment().apply { put("ZDIR", zdir); put("SAFEPATH_WORK", work.path); put("PYTHONIOENCODING", "utf-8") }
        val p = runCatching { pb.start() }.getOrElse { return "could not run python: ${it.message}" }
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.also { it.start() }
        if (!p.waitFor(timeoutMin, TimeUnit.MINUTES)) { p.destroyForcibly(); return "$out\n$script timed out" }
        reader.join()
        return out.toString()
    }

    /** the files segcheck / wetcheck read beside a zone's routes (the harness's floors, holes, layers, decks): copied next to [dest] */
    fun copyBeside(harnessDir: File, tag: String, dest: File) {
        for (k in listOf("floors", "holes", "layers", "decks")) File(harnessDir, "${k}_$tag.json").takeIf { it.isFile }?.copyTo(File(dest.parentFile, "${k}_$tag.json"), overwrite = true)
    }

    /** both py checks on the routes in [routes] (levels_routes_<tag>.json, its floors beside it): segcheck's and wetcheck's verdicts, notes */
    fun checks(py: File, routes: File, zdir: String, work: File, nRoutes: Int): Triple<String?, String?, List<String>> {
        val notes = ArrayList<String>()
        val seg = runPy(py, "segcheck.py", routes, zdir, work)
        val wet = runPy(py, "wetcheck.py", routes, zdir, work)
        val sv = segVerdict(seg); val wv = wetVerdict(wet, nRoutes, notes)
        if (sv == null) notes.add("segcheck said nothing: " + seg.lines().takeLast(3).joinToString(" / "))
        if (wv == null) notes.add("wetcheck said nothing: " + wet.lines().takeLast(3).joinToString(" / "))
        return Triple(sv ?: "FAILED TO RUN", wv ?: "FAILED TO RUN", notes)
    }
}
