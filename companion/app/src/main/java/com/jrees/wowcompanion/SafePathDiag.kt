package com.jrees.wowcompanion

import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * SAFEPATH'S DIAGNOSTIC LINES (E:/safepath-work/docs/DEVICE_INTEGRATION_PLAN.md, build step 13): what the WCMAP log (and the problem
 * report's [DiagLog]) says about the device's SafePath, so a field log answers "which zones could it route, and how long did it take"
 * without a debugger.
 *
 * - [coverage]: one line over the zones asked about: which have a lattice (CLB3, and made by today's [Climb.RULES_VERSION]), water
 *   (WTR2), ground zones (ZNS2) and a drafted network, each file's age and size. At most once per [COVERAGE_MS] unless what it says
 *   (the files and their versions, not their ages) changed.
 * - [harvestStep]: a harvest step's time for one zone (`climb`, `safepathzones`, `terrain`), at most one line per step per [TIMING_MS];
 *   the ones held back are counted into the next line with their slowest time. A failed step is always said.
 * - [plan]: one route plan's time, its search window (lattice vertices) and its scratch peak, at most one line per map per [TIMING_MS]
 *   the same way; a plan over the plan's budget ([PLAN_WORST_MS], [SCRATCH_CAP_BYTES], [WINDOW_CAP]) is always said, marked OVER.
 *
 * Pure but for [sink] (the device: Log.i("WCMAP") and DiagLog.event("safepath")) and [clock]; tests set both.
 */
object SafePathDiag {
    const val COVERAGE_MS = 10 * 60_000L
    const val TIMING_MS = 5_000L
    /** the plan's budget (the plan's §5): ≤150 ms typical, ≤1 s worst, scratch ≤24 MB, a window of at most 1.0 M vertices */
    const val PLAN_TYPICAL_MS = 150L
    const val PLAN_WORST_MS = 1_000L
    const val SCRATCH_CAP_BYTES = 24L * 1024 * 1024
    const val WINDOW_CAP = 1_000_000L

    @Volatile var sink: (String) -> Unit = { line -> android.util.Log.i("WCMAP", line); DiagLog.event("safepath", line) }
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    // ---------------------------------------------------------------- the files

    /** one zone file as found: [kind] (lattice, water, zones, draft), its [format] (the magic's four letters, "json", null: none or
     *  unreadable), the lattice's rules [version], [current] (readable by today's code), its age and size */
    class FileState(val kind: String, val rel: String, val present: Boolean, val format: String?, val version: Int?, val current: Boolean,
                    val ageMs: Long, val bytes: Long)

    /** a zone file the coverage line reports: [rel] its path under the art root for a map; [read] its format and version (null: unreadable) */
    class Kind(val name: String, val rel: (Int) -> String, val read: (File) -> Pair<String, Int?>?, val current: (String, Int?) -> Boolean)

    private fun magic(v: Int) = String(charArrayOf((v ushr 24).toChar(), ((v ushr 16) and 255).toChar(), ((v ushr 8) and 255).toChar(), (v and 255).toChar()))
        .takeIf { s -> s.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' } } ?: "?%08x".format(v)

    /** the kinds the device writes today (K's FLR1 / CAV1 files, when they have their own paths, are added here) */
    val KINDS: List<Kind> = listOf(
        Kind("lattice", { "terrain/$it.clb" }, { f -> runCatching { DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { i ->
                val m = i.readInt(); magic(m) to (if (m == Climb.Grid.MAGIC) i.readInt() else null) } }.getOrNull() },
            { fmt, v -> fmt == magic(Climb.Grid.MAGIC) && v == Climb.RULES_VERSION }),
        Kind("water", { "safepath/water/$it.bin" }, { f -> runCatching { DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { i ->
                magic(i.readInt()) to null } }.getOrNull() },
            { fmt, _ -> fmt == magic(SafePath.Water.MAGIC) }),
        Kind("zones", { "safepath/zones/$it.bin" }, { f -> runCatching { DataInputStream(f.inputStream().buffered()).use { i -> magic(i.readInt()) to null } }.getOrNull() },
            { fmt, _ -> fmt == magic(SafePath.Zones.MAGIC) }),
        Kind("draft", { "safepath/$it.draft.json" }, { f -> if (f.length() > 0) "json" to null else null }, { fmt, _ -> fmt == "json" }))

    /** [map]'s files under [root] */
    fun probe(root: File, map: Int, now: Long = clock(), kinds: List<Kind> = KINDS): List<FileState> = kinds.map { k ->
        val rel = k.rel(map); val f = File(root, rel)
        if (!f.isFile) FileState(k.name, rel, false, null, null, false, 0L, 0L)
        else { val r = k.read(f)
            FileState(k.name, rel, true, r?.first, r?.second, r != null && k.current(r.first, r.second), (now - f.lastModified()).coerceAtLeast(0L), f.length()) }
    }

    /** an age in its largest whole unit: 40s, 12m, 5h, 3d */
    fun age(ms: Long): String = when { ms < 60_000 -> "${ms / 1000}s"; ms < 3_600_000 -> "${ms / 60_000}m"; ms < 86_400_000 -> "${ms / 3_600_000}h"; else -> "${ms / 86_400_000}d" }
    /** a size: 812B, 40KB, 2.1MB */
    fun size(b: Long): String = when { b < 1024 -> "${b}B"; b < 1024 * 1024 -> "${b / 1024}KB"; else -> "%.1fMB".format(b / 1048576.0) }

    private fun fileWord(s: FileState): String = when {
        !s.present -> "-"
        s.format == null -> "unreadable"
        else -> s.format.lowercase() + (s.version?.let { " r$it" } ?: "") + (if (s.current) "" else " OLD") + " " + age(s.ageMs) + " " + size(s.bytes)
    }

    /** the coverage line for [maps] under [root] and what it says without the ages and sizes (the rate limit's key) */
    fun coverageLine(root: File, maps: Collection<Int>, now: Long = clock(), kinds: List<Kind> = KINDS): Pair<String, String> {
        val all = maps.sorted().map { it to probe(root, it, now, kinds) }
        val counts = kinds.joinToString(", ") { k -> "${k.name} ${all.count { (_, fs) -> fs.first { it.kind == k.name }.current }}/${all.size}" }
        val per = all.joinToString("; ") { (m, fs) -> "$m " + fs.joinToString(", ") { "${it.kind} ${fileWord(it)}" } }
        val key = all.joinToString(";") { (m, fs) -> "$m " + fs.joinToString(",") { "${it.present}/${it.format}/${it.version}/${it.current}" } }
        return "safepath coverage: ${all.size} maps (rules ${Climb.RULES_VERSION}); $counts${if (per.isEmpty()) "" else " | $per"}" to key
    }

    private var lastCoverageAt = Long.MIN_VALUE / 2
    private var lastCoverageKey: String? = null

    /** the coverage line for [maps] under [root] (the device: ArtHarvest.rootDir), said when it changed or [COVERAGE_MS] passed; true if said */
    fun coverage(root: File?, maps: Collection<Int>, force: Boolean = false): Boolean {
        val r = root ?: return false
        val now = clock()
        val (line, key) = coverageLine(r, maps, now)
        synchronized(this) {
            if (!force && key == lastCoverageKey && now - lastCoverageAt < COVERAGE_MS) return false
            lastCoverageAt = now; lastCoverageKey = key
        }
        sink(line)
        return true
    }

    // ---------------------------------------------------------------- the timings

    /** at most one line per key per [intervalMs]; the held-back ones counted (and their largest value) into the next line said */
    class Limiter(private val intervalMs: Long) {
        private class State(var at: Long, var held: Int, var worst: Long)
        private val by = HashMap<String, State>()
        /** whether [key]'s line may be said at [now] ([value]: its time, for the held-back summary); the summary to add, or null when held */
        @Synchronized fun pass(key: String, now: Long, value: Long, always: Boolean = false): String? {
            val s = by[key]
            if (s == null) { by[key] = State(now, 0, 0); return "" }
            if (!always && now - s.at < intervalMs) { s.held++; s.worst = maxOf(s.worst, value); return null }
            val note = if (s.held > 0) " (+${s.held} not logged, slowest ${s.worst} ms)" else ""
            s.at = now; s.held = 0; s.worst = 0
            return note
        }
        @Synchronized fun reset() { by.clear() }
    }

    private val harvestLimit = Limiter(TIMING_MS)
    private val planLimit = Limiter(TIMING_MS)

    /** a harvest step ([step]: climb, safepathzones, terrain) for [map] took [ms]; [bytes] written (-1 unknown); [why] a failure's
     *  reason (null: it worked). True if said */
    fun harvestStep(step: String, map: Int, ms: Long, bytes: Long = -1, why: String? = null): Boolean {
        val note = harvestLimit.pass(step, clock(), ms, always = why != null) ?: return false
        sink("safepath harvest: $step $map ${if (why == null) "in $ms ms" else "FAILED after $ms ms: $why"}${if (bytes >= 0) ", ${size(bytes)}" else ""}$note")
        return true
    }

    /** one route plan for [map] ([label]: the quest or the leg) took [ms] over a window of [windowVertices] lattice vertices, its
     *  scratch peak [scratchPeakBytes] (-1 unknown); [reached] its goal. True if said */
    fun plan(map: Int, ms: Long, windowVertices: Long, scratchPeakBytes: Long, reached: Boolean, label: String = ""): Boolean {
        val over = ms > PLAN_WORST_MS || scratchPeakBytes > SCRATCH_CAP_BYTES || windowVertices > WINDOW_CAP
        val note = planLimit.pass("$map", clock(), ms, always = over) ?: return false
        val win = if (windowVertices >= 0) "window %.2f M vertices".format(windowVertices / 1e6) else "window ?"
        val scr = if (scratchPeakBytes >= 0) "scratch peak ${size(scratchPeakBytes)}" else "scratch ?"
        sink("safepath plan: map $map${if (label.isEmpty()) "" else " $label"} $ms ms, $win, $scr, ${if (reached) "reached" else "NOT reached"}" +
            (if (over) " OVER" else if (ms > PLAN_TYPICAL_MS) " slow" else "") + note)
        return true
    }

    /** forget the rate limits (tests; a new session) */
    fun reset() { synchronized(this) { lastCoverageAt = Long.MIN_VALUE / 2; lastCoverageKey = null }; harvestLimit.reset(); planLimit.reset() }
}
