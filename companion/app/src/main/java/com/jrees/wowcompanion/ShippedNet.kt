package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * THE SHIPPED NETWORK, format NET1 (the user, 2026-10-19: "Ship our path network"; E:/safepath-work/docs/NET1_FORMAT.md): the
 * harness's own gate-checked network for one zone, one gzip file a map in the APK's assets (`safepath/net/<map>.bin`). OUR OWN
 * computed data only: ways and nodes with their rule flags, the danger along each way per faction, the exits, the cross-zone link
 * starts, the checkpoints and the in-zone links (portals, updrafts, declared swims), stamped with the rules and the game build they came
 * from; since v2 each walked way's own points (a rewalked way, a join) and the [Roadmap]: a coverage graph of the zone's walkable
 * ground (lattice vertices about 40 yd apart, their walkable joins). NEVER game art, tables, terrain heights, WMO geometry, textures
 * or file ids.
 *
 * The file is gzip(JSON): a header, the map's [Roadmap] (v2, one for both factions) and one [Section] a faction (the draft differs by
 * side: its towns, its foes). A section is a [SafePath.Net] v1 document ([SafePath.Net.toJson]: `nodes`, `edges`, `exits`) with NET1's
 * own fields beside it, so [SafePath.Net.fromJson] reads a section as it is; [Section.net] is that. The reader reads v1 and v2.
 */
object ShippedNet {
    const val FORMAT = "NET1"
    const val VERSION = 2
    /** the assets' folder: `safepath/net/<map>.bin` */
    const val ASSET_DIR = "safepath/net"
    fun assetPath(map: Int) = "$ASSET_DIR/$map.bin"

    // a way's flags (bits of [Way.flags])
    const val F_ROAD = 1; const val F_TRAIL = 2; const val F_SPUR = 4; const val F_CAVE = 8; const val F_LIFT = 16
    const val F_SWIM = 32; const val F_WADE = 64; const val F_BRIDGE = 128; const val F_AVOID = 256; const val F_REWALK = 512
    /** v2: a join the exporter walked on the lattice between two pieces of the network (or onto a cave's mouth); its [Way.pts] */
    const val F_JOIN = 1024
    /** v2: a city's street on its own floor (Dalaran): its points are on the floor (route it as a cave's way: marked in-floor) */
    const val F_FLOOR = 2048
    val FLAG_NAMES = listOf(F_ROAD to "road", F_TRAIL to "trail", F_SPUR to "spur", F_CAVE to "cave", F_LIFT to "lift", F_SWIM to "swim",
        F_WADE to "wade", F_BRIDGE to "bridge", F_AVOID to "avoid", F_REWALK to "rewalk", F_JOIN to "join", F_FLOOR to "floor")
    fun flagNames(f: Int) = FLAG_NAMES.filter { f and it.first != 0 }.map { it.second }

    /** a point's mark in a [Link] (Climb's own marks: ground 0, [Climb.IN_CAVE] on a floor, [Climb.LIFT], [Climb.SWIM]) */
    const val M_GROUND = 0

    /** the lattice's frame (Climb.Grid.wx / wy): lattice row [gr] / column [gc] (global, Grid.gr0 + j / gc0 + i) in world yards */
    fun vx(gr: Int): Double = 32.0 * MapHarvest.ADT_YD - gr * Climb.STEP
    fun vy(gc: Int): Double = 32.0 * MapHarvest.ADT_YD - gc * Climb.STEP
    fun gr(wx: Double): Int = Math.round((32.0 * MapHarvest.ADT_YD - wx) / Climb.STEP).toInt()
    fun gc(wy: Double): Int = Math.round((32.0 * MapHarvest.ADT_YD - wy) / Climb.STEP).toInt()

    /** the stamp: [rules] = Climb.RULES_VERSION, [rulesCommit] the harness checkout's commit, [build] the game's Build Key (GameFiles.buildKey,
     *  .build.info's), [exported] ISO time, [map] / [cont] the uiMap and its continent */
    class Header(val map: Int, val cont: Int, val rules: Int, val rulesCommit: String, val build: String, val exported: String,
                 val format: String = FORMAT, val version: Int = VERSION)

    /** a way's NET1 fields beside its [SafePath.Edge] (same index): its [flags], its length [yd]; v2: [pts] its walked points from its
     *  node a to its node b (both ends included) when it is not the straight line between them (a rewalked way, a join, a floor's
     *  street) - follow them instead of the straight line; [layers] (v2, optional, one a point): a floor's layer index at each point
     *  (0 the ground; 1, 2 ... a city's floors from the lowest up: a street over a street), never a height */
    class Way(val flags: Int, val yd: Double, val pts: List<DoubleArray>? = null, val layers: IntArray? = null) {
        init { require(layers == null || (pts != null && layers.size == pts.size)) { "a layer a point" } }
    }

    /** an in-zone link that is no walk on the ground (Climb.Link): a portal, an updraft, a declared swim, a floor's stair; [pts] x, y,
     *  mark (no heights); [oneWay] from its first point only; [yd] its cost in walking yards; [island] for a swim: x, y, radius of the
     *  island's ground (the swim is offered only to a goal there) */
    class Link(val kind: String, val name: String, val oneWay: Boolean, val yd: Double, val pts: List<DoubleArray>, val island: DoubleArray? = null)

    /** v2: a STITCH - a net node's own walk onto a [Roadmap] node: [node] the section's node id, [rm] the roadmap node's index, [flags]
     *  (way flags: F_JOIN, and F_WADE / F_BRIDGE when it crosses them), [pts] its lattice-walked line from the net node to the roadmap
     *  node, ends included, checked by segcheck at export. The device joins the net and the roadmap by these only (never its own leg) */
    class Stitch(val node: Int, val rm: Int, val flags: Int, val pts: List<DoubleArray>) {
        val yd: Double get() = pts.zipWithNext { u, v -> hypot(v[0] - u[0], v[1] - u[1]) }.sum()
    }

    /** a cross-zone link's start in this zone: node [node] ([kind]: flight, dock, tram, zeppelin, boat, portal, gate...), into [toMap]
     *  (0 = several / by its own data), [name] */
    class Start(val node: Int, val kind: String, val toMap: Int, val name: String)

    /** one faction's network: [faction] TransportRoutes.HORDE / ALLIANCE, [level] the test character's level its danger was weighed for,
     *  [tag] the harness spec it came from; [net] the network (REMOVED ways already left out), [ways] its edges' NET1 fields (same order),
     *  [links], [starts], [checkpoints] (node ids: the named stops a route's pins can name); v2: [roadmapDanger] one byte a [Roadmap]
     *  edge (its order): this faction's danger along it ([ROADMAP_DANGER_SCALE] a unit of the danger grid's 0..4, the zone's typical
     *  danger taken off) */
    class Section(val faction: Int, val level: Int, val tag: String, val net: SafePath.Net, val ways: List<Way>,
                  val links: List<Link> = emptyList(), val starts: List<Start> = emptyList(), val checkpoints: List<Int> = emptyList(),
                  val roadmapDanger: ByteArray? = null, val tags: List<String> = listOf(tag), val wayTag: IntArray? = null, val exitTag: IntArray? = null,
                  val stitches: List<Stitch> = emptyList()) {
        /** this section with its ways / stitches / roadmap danger replaced (the exporter's checks: a way walked or dropped) */
        fun copy(net: SafePath.Net = this.net, ways: List<Way> = this.ways, roadmapDanger: ByteArray? = this.roadmapDanger, stitches: List<Stitch> = this.stitches,
                 wayTag: IntArray? = this.wayTag) = Section(faction, level, tag, net, ways, links, starts, checkpoints, roadmapDanger, tags, wayTag, exitTag, stitches)
        init { require(ways.size == net.edges.size) { "ways ${ways.size} != edges ${net.edges.size}" } }
        /** v2: the spec a way / an exit came from (a map several specs cover: their union, [merge]) */
        fun wayTagOf(i: Int): String = tags.getOrElse(wayTag?.getOrNull(i) ?: 0) { tag }
        fun exitTagOf(i: Int): String = tags.getOrElse(exitTag?.getOrNull(i) ?: 0) { tag }
        /** roadmap edge [e]'s danger in the danger grid's units (0 = none, or no roadmap danger shipped) */
        fun roadmapDangerOf(e: Int): Float = roadmapDanger?.getOrNull(e)?.let { (it.toInt() and 0xFF) / ROADMAP_DANGER_SCALE } ?: 0f
        fun toJson(): JSONObject {
            fun r1(v: Double) = (v * 10).roundToInt() / 10.0
            fun r2(v: Double) = (v * 100).roundToInt() / 100.0
            val o = net.toJson()
            o.put("faction", faction).put("level", level).put("tag", tag)
            o.put("ways", JSONArray(ways.map { w -> JSONArray(listOf<Any>(w.flags, r1(w.yd)) +
                (w.pts?.let { p -> listOf(JSONArray(p.flatMap { listOf(r2(it[0]), r2(it[1])) })) } ?: emptyList()) +
                (w.layers?.let { l -> listOf(JSONArray(l.toList())) } ?: emptyList())) }))
            o.put("links", JSONArray(links.map { l -> JSONObject().put("kind", l.kind).put("name", l.name).put("oneWay", l.oneWay).put("yd", r1(l.yd))
                .put("pts", JSONArray(l.pts.map { p -> JSONArray(listOf(r1(p[0]), r1(p[1]), if (p.size > 2) p[2].roundToInt() else M_GROUND)) }))
                .also { j -> l.island?.let { j.put("island", JSONArray(it.map { v -> r1(v) })) } } }))
            o.put("starts", JSONArray(starts.map { s -> JSONArray(listOf(s.node, s.kind, s.toMap, s.name)) }))
            o.put("checkpoints", JSONArray(checkpoints))
            roadmapDanger?.let { o.put("roadmapDanger", pack64(it)) }
            if (tags.size > 1 || tags.firstOrNull() != tag) o.put("tags", JSONArray(tags))
            wayTag?.let { o.put("wayTags", JSONArray(it.toList())) }
            if (stitches.isNotEmpty()) o.put("stitches", JSONArray(stitches.map { st -> JSONArray(listOf<Any>(st.node, st.rm, st.flags, JSONArray(st.pts.flatMap { listOf(r2(it[0]), r2(it[1])) }))) }))
            exitTag?.let { o.put("exitTags", JSONArray(it.toList())) }
            return o
        }
        companion object {
            fun fromJson(o: JSONObject): Section? {
                val net = SafePath.Net.fromJson(o) ?: return null
                val ws = o.optJSONArray("ways") ?: JSONArray()
                val ways = (0 until net.edges.size).map { i -> ws.optJSONArray(i)?.let { a ->
                    val p = a.optJSONArray(2)?.let { f -> (0 until f.length() / 2).map { k -> doubleArrayOf(f.optDouble(2 * k), f.optDouble(2 * k + 1)) } }
                    val ly = a.optJSONArray(3)?.let { l -> IntArray(l.length()) { l.optInt(it) } }?.takeIf { p != null && it.size == p.size }
                    Way(a.optInt(0), a.optDouble(1, 0.0), p, ly) } ?: Way(0, 0.0) }
                val ls = o.optJSONArray("links") ?: JSONArray()
                val links = (0 until ls.length()).mapNotNull { i -> ls.optJSONObject(i)?.let { l ->
                    val ps = l.optJSONArray("pts") ?: JSONArray()
                    Link(l.optString("kind"), l.optString("name"), l.optBoolean("oneWay"), l.optDouble("yd", 0.0),
                        (0 until ps.length()).mapNotNull { k -> ps.optJSONArray(k)?.let { p -> doubleArrayOf(p.optDouble(0), p.optDouble(1), p.optDouble(2, 0.0)) } },
                        l.optJSONArray("island")?.let { a -> doubleArrayOf(a.optDouble(0), a.optDouble(1), a.optDouble(2)) }) } }
                val ss = o.optJSONArray("starts") ?: JSONArray()
                val starts = (0 until ss.length()).mapNotNull { i -> ss.optJSONArray(i)?.let { Start(it.optInt(0), it.optString(1), it.optInt(2), it.optString(3)) } }
                val cs = o.optJSONArray("checkpoints") ?: JSONArray()
                val rd = o.optString("roadmapDanger", "").takeIf { it.isNotEmpty() }?.let { unpack64(it) }
                fun ints(k: String) = o.optJSONArray(k)?.let { a -> IntArray(a.length()) { a.optInt(it) } }
                val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(o.optString("tag"))
                return Section(o.optInt("faction"), o.optInt("level"), o.optString("tag"), net, ways, links, starts, (0 until cs.length()).map { cs.optInt(it) }, rd,
                    tags, ints("wayTags"), ints("exitTags"),
                    o.optJSONArray("stitches")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { q -> val f = q.optJSONArray(3) ?: JSONArray()
                        Stitch(q.optInt(0), q.optInt(1), q.optInt(2), (0 until f.length() / 2).map { k -> doubleArrayOf(f.optDouble(2 * k), f.optDouble(2 * k + 1)) }) } } } ?: emptyList())
            }
        }
    }

    /** one map's file: its [header], its [roadmap] (v2; null in v1) and a [Section] a faction */
    class Pack(val header: Header, val sections: List<Section>, val roadmap: Roadmap? = null) {
        fun section(faction: Int): Section? = sections.firstOrNull { it.faction == faction }
        fun toJson(): JSONObject = JSONObject().put("fmt", header.format).put("v", header.version).put("map", header.map).put("cont", header.cont)
            .put("rules", header.rules).put("rulesCommit", header.rulesCommit).put("build", header.build).put("exported", header.exported)
            .put("sections", JSONArray(sections.map { it.toJson() }))
            .also { o -> roadmap?.let { o.put("roadmap", JSONObject().put("enc", Roadmap.ENC).put("data", pack64(it.encode()))) } }
        companion object {
            /** null when it is not NET1, or of a later version than this reader */
            fun fromJson(o: JSONObject): Pack? {
                if (o.optString("fmt") != FORMAT || o.optInt("v") !in 1..VERSION) return null
                val h = Header(o.optInt("map"), o.optInt("cont"), o.optInt("rules"), o.optString("rulesCommit"), o.optString("build"),
                    o.optString("exported"), o.optString("fmt"), o.optInt("v"))
                val ss = o.optJSONArray("sections") ?: JSONArray()
                val rm = o.optJSONObject("roadmap")?.takeIf { it.optString("enc") == Roadmap.ENC }?.let { Roadmap.decode(unpack64(it.getString("data"))) }
                return Pack(h, (0 until ss.length()).mapNotNull { i -> ss.optJSONObject(i)?.let { Section.fromJson(it) } }, rm)
            }
        }
    }

    /** the file's bytes: gzip of the pack's JSON (no gzip timestamp: the same pack, the same bytes) */
    fun write(pack: Pack, out: OutputStream) {
        val bytes = pack.toJson().toString().toByteArray(Charsets.UTF_8)
        // GZIPOutputStream writes MTIME 0 already; the JSON's own key order is the writer's
        GZIPOutputStream(out).use { it.write(bytes) }
    }
    fun bytes(pack: Pack): ByteArray = ByteArrayOutputStream().also { write(pack, it) }.toByteArray()
    /** the pack in [input] (closed), or null: not gzip, not NET1, a later version */
    fun read(input: InputStream): Pack? = runCatching {
        Pack.fromJson(JSONObject(GZIPInputStream(input).use { it.readBytes() }.toString(Charsets.UTF_8)))
    }.getOrNull()

    /** a binary blob in the JSON: zlib-deflated, then base64 (the outer gzip cannot shrink it further) */
    fun pack64(b: ByteArray): String {
        val z = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o, java.util.zip.Deflater(9)).use { it.write(b) } }.toByteArray()
        return java.util.Base64.getEncoder().encodeToString(z)
    }
    fun unpack64(s: String): ByteArray = InflaterInputStream(ByteArrayInputStream(java.util.Base64.getDecoder().decode(s))).use { it.readBytes() }

    // ---------------------------------------------------------------- the roadmap (v2)

    const val ROADMAP_DANGER_SCALE = 60f
    // a roadmap node's flags
    const val R_FLOOR = 1           // its vertex is on a floor layer (a walkway's, a building's deck: Climb.floorLayers)
    const val R_BUILDING = 2        // within a few yards of a building's rooms
    // a roadmap edge's flags: bits 0 wade, 1 building, 2-3 the edge-clearance tier, 4-5 the climb tier, 6 walked (points), 7 bridge
    const val RE_WADE = 1; const val RE_BUILDING = 2; const val RE_WALKED = 64; const val RE_BRIDGE = 128
    fun edgeTier(f: Int) = (f shr 2) and 3          // Climb.edgeCost along it, in [EDGE_TIER_COST] steps (0 none .. 3 the full EDGE_COST)
    fun climbTier(f: Int) = (f shr 4) and 3         // the steepest lattice step along it: Climb.LEVEL, CLIMB, STEEP
    const val EDGE_TIER_COST = 0.5
    /** the four forward neighbours of a roadmap cell (di columns, dj rows): east, south, south-east, south-west */
    val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(-1, 1))

    /**
     * THE ROADMAP (v2): a coverage graph of the zone's walkable ground, for attaching a player or a goal within about 40 yd anywhere
     * and routing as the harness does. Cells of [cell] lattice steps (10: 41.7 yd) from lattice vertex ([gr0], [gc0]), [nx] x [ny] of
     * them; at most one node a cell, a walkable lattice vertex ([nodeGr], [nodeGc], global) with [nodeFlags]; edges only to the four
     * forward neighbour cells ([DIRS]), each walkable by the harness's own check (Climb.walkable: straight) or by its lattice leg (then
     * [edgePts]: the leg's inner vertices, flat gr, gc). No heights: points are lattice vertices.
     */
    class Roadmap(val gr0: Int, val gc0: Int, val cell: Int, val nx: Int, val ny: Int,
                  val nodeCell: IntArray, val nodeGr: IntArray, val nodeGc: IntArray, val nodeFlags: IntArray,
                  val edgeA: IntArray, val edgeB: IntArray, val edgeFlags: IntArray, val edgePts: Array<IntArray?>) {
        val nodes get() = nodeGr.size
        val edges get() = edgeA.size
        fun x(n: Int) = vx(nodeGr[n]); fun y(n: Int) = vy(nodeGc[n])
        /** edge [e]'s points from its node a to its node b (world x, y) */
        fun points(e: Int): List<DoubleArray> {
            val a = edgeA[e]; val b = edgeB[e]
            val out = ArrayList<DoubleArray>(); out.add(doubleArrayOf(x(a), y(a)))
            edgePts[e]?.let { p -> for (k in 0 until p.size / 2) out.add(doubleArrayOf(vx(p[2 * k]), vy(p[2 * k + 1]))) }
            out.add(doubleArrayOf(x(b), y(b))); return out
        }
        fun yd(e: Int): Double = points(e).zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }.sum()

        /** RM1: varints (LEB128; signed zigzag) - version, gr0, gc0, cell, nx, ny; a byte a cell (0 none, else 1 + its node's flags x 2)
         *  with its vertex's row and column in the cell (a byte each); then a byte a node: its forward edges ([DIRS] bits); then per edge
         *  its flags byte and, when walked, its inner points' count and each point's (row, column) step from the last (zigzag) */
        fun encode(): ByteArray {
            val o = ByteArrayOutputStream()
            fun u(v: Int) { var x = v; require(x >= 0); while (x >= 0x80) { o.write((x and 0x7F) or 0x80); x = x ushr 7 }; o.write(x) }
            fun s(v: Int) = u((v shl 1) xor (v shr 31))
            u(1); s(gr0); s(gc0); u(cell); u(nx); u(ny)
            val at = IntArray(nx * ny) { -1 }
            for (n in 0 until nodes) { require(at[nodeCell[n]] < 0) { "two nodes in cell ${nodeCell[n]}" }; at[nodeCell[n]] = n }
            // nodes in cell order (the encoder's own order: the decoder numbers them so)
            val order = (0 until nx * ny).filter { at[it] >= 0 }.map { at[it] }
            require(order == (0 until nodes).toList()) { "the nodes must be in cell order" }
            for (c in 0 until nx * ny) { val n = at[c]
                if (n < 0) { o.write(0); continue }
                val dr = nodeGr[n] - (gr0 + (c / nx) * cell); val dc = nodeGc[n] - (gc0 + (c % nx) * cell)
                require(dr in 0 until cell && dc in 0 until cell) { "node $n outside its cell" }
                u(1 + nodeFlags[n] * 2); o.write(dr); o.write(dc) }
            // edges grouped by their node a, in DIRS order
            val byA = HashMap<Int, IntArray>()
            for (e in 0 until edges) { val a = edgeA[e]; val b = edgeB[e]
                val ca = nodeCell[a]; val cb = nodeCell[b]; val di = cb % nx - ca % nx; val dj = cb / nx - ca / nx
                val d = DIRS.indexOfFirst { it[0] == di && it[1] == dj }; require(d >= 0) { "edge $e is no forward neighbour" }
                byA.getOrPut(a) { IntArray(DIRS.size) { -1 } }[d] = e }
            val eOrder = ArrayList<Int>()
            for (n in 0 until nodes) { val m = byA[n]; var mask = 0
                if (m != null) for (d in DIRS.indices) if (m[d] >= 0) { mask = mask or (1 shl d); eOrder.add(m[d]) }
                o.write(mask) }
            require(eOrder == (0 until edges).toList()) { "the edges must be in their node a's order, DIRS order within" }
            for (e in 0 until edges) {
                val p = edgePts[e]; val f = (edgeFlags[e] and RE_WALKED.inv()) or (if (p != null) RE_WALKED else 0)
                o.write(f)
                if (p != null) { u(p.size / 2); var lr = nodeGr[edgeA[e]]; var lc = nodeGc[edgeA[e]]
                    for (k in 0 until p.size / 2) { s(p[2 * k] - lr); s(p[2 * k + 1] - lc); lr = p[2 * k]; lc = p[2 * k + 1] } }
            }
            return o.toByteArray()
        }
        companion object {
            const val ENC = "RM1+zlib+base64"
            fun decode(b: ByteArray): Roadmap {
                var i = 0
                fun u(): Int { var r = 0; var sh = 0; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F) shl sh); if (x < 0x80) return r; sh += 7 } }
                fun s(): Int { val v = u(); return (v ushr 1) xor -(v and 1) }
                require(u() == 1) { "RM version" }
                val gr0 = s(); val gc0 = s(); val cell = u(); val nx = u(); val ny = u()
                val nc = ArrayList<Int>(); val ngr = ArrayList<Int>(); val ngc = ArrayList<Int>(); val nf = ArrayList<Int>()
                for (c in 0 until nx * ny) { val v = u(); if (v == 0) continue
                    val dr = b[i++].toInt() and 0xFF; val dc = b[i++].toInt() and 0xFF
                    nc.add(c); ngr.add(gr0 + (c / nx) * cell + dr); ngc.add(gc0 + (c % nx) * cell + dc); nf.add((v - 1) / 2) }
                val at = HashMap<Int, Int>().also { m -> nc.forEachIndexed { n, c -> m[c] = n } }
                val ea = ArrayList<Int>(); val eb = ArrayList<Int>()
                for (n in nc.indices) { val mask = b[i++].toInt() and 0xFF
                    for (d in DIRS.indices) if (mask and (1 shl d) != 0) { val c = nc[n]; val cb = c + DIRS[d][0] + DIRS[d][1] * nx
                        ea.add(n); eb.add(at[cb] ?: error("edge to an empty cell")) } }
                val ef = IntArray(ea.size); val ep = arrayOfNulls<IntArray>(ea.size)
                for (e in ea.indices) { val f = b[i++].toInt() and 0xFF; ef[e] = f
                    if (f and RE_WALKED != 0) { val k = u(); val p = IntArray(2 * k); var lr = ngr[ea[e]]; var lc = ngc[ea[e]]
                        for (q in 0 until k) { lr += s(); lc += s(); p[2 * q] = lr; p[2 * q + 1] = lc }; ep[e] = p } }
                return Roadmap(gr0, gc0, cell, nx, ny, nc.toIntArray(), ngr.toIntArray(), ngc.toIntArray(), nf.toIntArray(),
                    ea.toIntArray(), eb.toIntArray(), ef, ep)
            }
        }
    }

    // ---------------------------------------------------------------- building a section (the harness's exporter)

    /** a way's kind and state as flags; [wet] what it crosses (bits [F_WADE], [F_BRIDGE], [F_SWIM]) */
    fun flagsOf(e: SafePath.Edge, wet: Int = 0): Int {
        var f = wet
        f = f or when (e.kind) { SafePath.ROAD -> F_ROAD; SafePath.TRAIL -> F_TRAIL; SafePath.SPUR -> F_SPUR; SafePath.CAVE -> F_CAVE
            SafePath.LIFT -> F_LIFT; else -> 0 }
        if (e.state == SafePath.AVOID) f = f or F_AVOID
        if (e.state == SafePath.REWALK) f = f or F_REWALK
        return f
    }

    /** a way's points made to begin exactly at [a] and end exactly at [b] (its nodes): an end within [ANCHOR_YD] is moved onto the node,
     *  else the node is put before (after) it - the hop between them then part of the way, checked with the rest (a lattice leg begins at
     *  the vertex nearest its start when the hop from the start itself crosses a wall: W's Durotar stitch at 645,-4743). [layers] kept
     *  one a point (an added end takes its neighbour's) */
    fun anchored(pts: List<DoubleArray>, a: DoubleArray, b: DoubleArray, layers: IntArray? = null): Pair<List<DoubleArray>, IntArray?> {
        if (pts.isEmpty()) return listOf(a, b) to null
        val out = ArrayList<DoubleArray>(pts.size + 2); val ly = ArrayList<Int>()
        if (hypot(pts[0][0] - a[0], pts[0][1] - a[1]) <= ANCHOR_YD) { out.add(doubleArrayOf(a[0], a[1])); layers?.let { ly.add(it[0]) } }
        else { out.add(doubleArrayOf(a[0], a[1])); out.add(pts[0]); layers?.let { ly.add(it[0]); ly.add(it[0]) } }
        for (k in 1 until pts.size - 1) { out.add(pts[k]); layers?.let { ly.add(it[k]) } }
        if (pts.size >= 2) { val last = pts.last()
            if (hypot(last[0] - b[0], last[1] - b[1]) <= ANCHOR_YD) { out.add(doubleArrayOf(b[0], b[1])); layers?.let { ly.add(it.last()) } }
            else { out.add(last); out.add(doubleArrayOf(b[0], b[1])); layers?.let { ly.add(it.last()); ly.add(it.last()) } } }
        else if (hypot(pts[0][0] - b[0], pts[0][1] - b[1]) > ANCHOR_YD) { out.add(doubleArrayOf(b[0], b[1])); layers?.let { ly.add(it.last()) } }
        return out to layers?.let { ly.toIntArray() }
    }
    const val ANCHOR_YD = 0.02

    /** the node kinds a cross-zone link starts at (TravelLinks: flights, rides) and the exits that are rides, not walks */
    val START_NODES = setOf(SafePath.FLIGHT, SafePath.DOCK, SafePath.TRAM)

    /** an extra way for [section]: a join or a floor's street between nodes [a] and [b], walked by [pts] (ends included), [flags] */
    class Extra(val a: Int, val b: Int, val kind: String, val flags: Int, val pts: List<DoubleArray>, val danger: Float = 0f, val src: String = "join",
                val layers: IntArray? = null)

    /**
     * [net] as a section: its REMOVED ways left out (and the nodes no way, exit or name keeps), each way's flags ([wetOf]: the bits of
     * what the way crosses, from its two ends' points) and length; a rewalked way's own points ([walk]: the lattice's leg between its
     * ends, null = none found, the straight line kept); [extraNodes] / [extras] the exporter's joins and floor streets; the starts
     * (flight masters, docks, trams, and the exits that are no walk) and the checkpoints (the named nodes)
     */
    fun section(net: SafePath.Net, faction: Int, level: Int, tag: String, links: List<Link> = emptyList(),
                wetOf: (DoubleArray, DoubleArray) -> Int = { _, _ -> 0 }, walk: ((DoubleArray, DoubleArray) -> List<DoubleArray>?)? = null,
                extraNodes: List<SafePath.Node> = emptyList(), extras: List<Extra> = emptyList(), roadmapDanger: ByteArray? = null): Section {
        val allNodes = net.nodes + extraNodes
        val byId = allNodes.associateBy { it.id }
        val edges0 = net.edges.filter { it.state != SafePath.REMOVED && byId.containsKey(it.a) && byId.containsKey(it.b) }
        val edges = edges0 + extras.map { x -> SafePath.Edge(x.a, x.b, x.kind, SafePath.OK, x.src, x.danger, 1f) }
        val used = HashSet<Int>().also { s -> for (e in edges) { s.add(e.a); s.add(e.b) }; for (x in net.exits) s.add(x.node) }
        val nodes = allNodes.filter { it.id in used || it.name != null }
        val exits = net.exits.filter { byId.containsKey(it.node) }
        fun len(p: List<DoubleArray>) = p.zipWithNext { u, v -> hypot(v[0] - u[0], v[1] - u[1]) }.sum()
        val ways = edges0.map { e -> val a = byId[e.a]!!; val b = byId[e.b]!!
            val pa = doubleArrayOf(a.wx, a.wy); val pb = doubleArrayOf(b.wx, b.wy)
            val pts = if (e.state == SafePath.REWALK) walk?.invoke(pa, pb)?.takeIf { it.size > 2 }?.let { anchored(it, pa, pb).first } else null
            val wet = if (e.kind == SafePath.CAVE || e.kind == SafePath.LIFT) 0
                else pts?.zipWithNext()?.fold(0) { f, (u, v) -> f or wetOf(u, v) } ?: wetOf(pa, pb)
            Way(flagsOf(e, wet), pts?.let { len(it) } ?: hypot(a.wx - b.wx, a.wy - b.wy), pts) } +
            extras.map { x -> val a = byId[x.a]; val b = byId[x.b]
                val (p, ly) = if (a != null && b != null) anchored(x.pts, doubleArrayOf(a.wx, a.wy), doubleArrayOf(b.wx, b.wy), x.layers) else x.pts to x.layers
                Way(x.flags or p.zipWithNext().fold(0) { f, (u, v) -> f or (if (x.flags and F_FLOOR != 0) 0 else wetOf(u, v)) }, len(p), p, ly) }
        val starts = nodes.filter { it.kind in START_NODES }.map { n -> val x = exits.firstOrNull { it.node == n.id }
            Start(n.id, n.kind, x?.toMap ?: 0, n.name ?: x?.name ?: "") } +
            exits.filter { it.how !in TravelLinks.FOOT && nodes.firstOrNull { n -> n.id == it.node }?.kind !in START_NODES }
                .map { Start(it.node, it.how, it.toMap, it.name) }
        val checkpoints = nodes.filter { it.name != null }.map { it.id }
        return Section(faction, level, tag, SafePath.Net(net.map, net.cont, net.built, nodes, edges, exits, net.reviewed), ways, links, starts, checkpoints, roadmapDanger)
    }

    /**
     * THE JOINS (v2): the pieces of [net]'s ground network (its usable ways; caves' ways count, a cave's mouth is a piece's end) joined
     * where the ground joins them: nearest pairs of [ends]-able nodes first (no cave floor's inside node: a leg starts on the
     * terrain), each pair tried by [leg] (the lattice's own: its points and whether it reached) when no farther than [maxYd], kept when
     * it reached and is no longer than [factor] x the straight line + [slackYd]; joined pieces are one, and the next nearest pair of
     * pieces is tried, until no pair is left. Returns each join's (node a, node b, points).
     */
    fun joins(net: SafePath.Net, leg: (DoubleArray, DoubleArray) -> Pair<List<DoubleArray>, Boolean>?, maxYd: Double = 600.0,
              factor: Double = 1.8, slackYd: Double = 60.0, tries: Int = 3, log: (String) -> Unit = {}): List<Triple<Int, Int, List<DoubleArray>>> {
        val byId = net.nodes.associateBy { it.id }
        val usable = net.edges.filter { it.state != SafePath.REMOVED && byId.containsKey(it.a) && byId.containsKey(it.b) }
        val parent = HashMap<Int, Int>()
        fun find(x: Int): Int { var a = x; while ((parent[a] ?: a) != a) a = parent[a]!!; return a }
        fun union(a: Int, b: Int) { val x = find(a); val y = find(b); if (x != y) parent[x] = y }
        val inNet = HashSet<Int>()
        for (e in usable) { union(e.a, e.b); inNet.add(e.a); inNet.add(e.b) }
        val ends = net.nodes.filter { it.id in inNet && it.kind != SafePath.CAVE && it.kind != SafePath.LIFT }
        val out = ArrayList<Triple<Int, Int, List<DoubleArray>>>()
        val failed = HashSet<Long>()
        while (true) {
            val groups = ends.groupBy { find(it.id) }
            if (groups.size < 2) break
            // each pair of pieces: its nearest few node pairs
            val cands = ArrayList<Triple<Double, SafePath.Node, SafePath.Node>>()
            val gl = groups.values.toList()
            for (i in gl.indices) for (j in i + 1 until gl.size) {
                val best = ArrayList<Triple<Double, SafePath.Node, SafePath.Node>>()
                for (a in gl[i]) for (b in gl[j]) { val d = hypot(a.wx - b.wx, a.wy - b.wy); if (d > maxYd) continue
                    if (((a.id.toLong() shl 32) or (b.id.toLong() and 0xFFFFFFFFL)) in failed) continue
                    best.add(Triple(d, a, b)) }
                best.sortBy { it.first }; cands.addAll(best.take(tries))
            }
            cands.sortBy { it.first }
            var joined = false
            for ((d, a, b) in cands) {
                if (find(a.id) == find(b.id)) continue
                val r = leg(doubleArrayOf(a.wx, a.wy), doubleArrayOf(b.wx, b.wy))
                val pts = r?.first
                val yd = pts?.zipWithNext { u, v -> hypot(v[0] - u[0], v[1] - u[1]) }?.sum() ?: Double.MAX_VALUE
                if (r != null && r.second && pts!!.size >= 2 && yd <= factor * d + slackYd) {
                    out.add(Triple(a.id, b.id, pts)); union(a.id, b.id); joined = true
                    log("JOIN ${a.id} (${a.wx.toInt()},${a.wy.toInt()}) - ${b.id} (${b.wx.toInt()},${b.wy.toInt()}): ${yd.toInt()} yd (straight ${d.toInt()})")
                    break
                }
                failed.add((a.id.toLong() shl 32) or (b.id.toLong() and 0xFFFFFFFFL))
            }
            if (!joined) break
        }
        val left = ends.groupBy { find(it.id) }
        log("JOINS ${out.size} made; ${left.size} piece(s) left: ${left.values.sortedByDescending { it.size }.joinToString { "${it.size} nodes" }}")
        return out
    }

    /** two nodes this near, of one kind, are one when sections are merged */
    const val SAME_NODE_YD = 1.0

    /**
     * ONE FACTION'S SECTIONS OF ONE MAP MERGED (the coordinator's rule, 2026-10-19: specs sharing a map - Alterac and Dalaran, 1416 - ship
     * one file, one section a faction, the UNION of their networks): the first section's node ids kept; another's node within
     * [SAME_NODE_YD] of one of the same kind is that node, else a new id; a way between the same two nodes of the same kind is kept
     * once (the first's), as is an exit to the same map the same way from the same node, a link with the same kind and ends, a start;
     * each way and exit keeps its spec's tag ([Section.wayTagOf] / [exitTagOf]). [roadmapDanger] the merged file's roadmap's danger.
     */
    fun merge(secs: List<Section>, roadmapDanger: ByteArray? = secs.firstOrNull()?.roadmapDanger): Section {
        require(secs.isNotEmpty() && secs.all { it.faction == secs[0].faction && it.net.map == secs[0].net.map }) { "one faction, one map" }
        if (secs.size == 1) return secs[0].copy(roadmapDanger = roadmapDanger)
        val tags = ArrayList<String>()
        val nodes = ArrayList<SafePath.Node>(); val edges = ArrayList<SafePath.Edge>(); val ways = ArrayList<Way>(); val wt = ArrayList<Int>()
        val exits = ArrayList<SafePath.Exit>(); val et = ArrayList<Int>()
        val links = ArrayList<Link>(); val starts = ArrayList<Start>(); val checks = LinkedHashSet<Int>()
        val stitches = ArrayList<Stitch>(); val stitchKeys = HashSet<String>()
        val grid = HashMap<Long, ArrayList<SafePath.Node>>()
        fun key(x: Double, y: Double) = (Math.floor(x).toLong() shl 32) xor (Math.floor(y).toLong() and 0xFFFFFFFFL)
        fun near(x: Double, y: Double, kind: String): SafePath.Node? {
            for (i in -1..1) for (j in -1..1) grid[key(x + i, y + j)]?.forEach { n -> if (n.kind == kind && hypot(n.wx - x, n.wy - y) <= SAME_NODE_YD) return n }
            return null
        }
        var next = (secs[0].net.nodes.maxOfOrNull { it.id } ?: -1) + 1
        val edgeKeys = HashSet<String>(); val exitKeys = HashSet<String>(); val linkKeys = HashSet<String>(); val startKeys = HashSet<String>()
        for (sec in secs) {
            val idMap = HashMap<Int, Int>()
            // (matched only against the sections before: a section's own near nodes stay its own)
            val added = ArrayList<SafePath.Node>()
            for (n in sec.net.nodes) {
                val same = if (sec === secs[0]) null else near(n.wx, n.wy, n.kind)
                if (same != null) { idMap[n.id] = same.id
                    if (same.name == null && n.name != null) { val k = nodes.indexOf(same); val m = SafePath.Node(same.id, same.wx, same.wy, same.kind, same.src, n.name)
                        nodes[k] = m; grid[key(same.wx, same.wy)]?.let { l -> l[l.indexOf(same)] = m } }
                    continue }
                val id = if (sec === secs[0]) n.id else next++; idMap[n.id] = id
                val m = SafePath.Node(id, n.wx, n.wy, n.kind, n.src, n.name); nodes.add(m); added.add(m)
            }
            for (m in added) grid.getOrPut(key(m.wx, m.wy)) { ArrayList() }.add(m)
            for ((i, e) in sec.net.edges.withIndex()) {
                val a = idMap[e.a] ?: continue; val b = idMap[e.b] ?: continue
                if (!edgeKeys.add("${minOf(a, b)}-${maxOf(a, b)}-${e.kind}")) continue
                edges.add(SafePath.Edge(a, b, e.kind, e.state, e.src, e.danger, e.climb)); ways.add(sec.ways[i])
                val t = sec.wayTagOf(i); wt.add(tags.indexOf(t).takeIf { it >= 0 } ?: tags.size.also { tags.add(t) })
            }
            for ((i, x) in sec.net.exits.withIndex()) { val n = idMap[x.node] ?: continue
                if (!exitKeys.add("$n-${x.toMap}-${x.how}")) continue
                exits.add(SafePath.Exit(n, x.toMap, x.how, x.name))
                val t = sec.exitTagOf(i); et.add(tags.indexOf(t).takeIf { it >= 0 } ?: tags.size.also { tags.add(t) }) }
            for (l in sec.links) if (linkKeys.add("${l.kind}|${l.pts.first().map { Math.round(it) }}|${l.pts.last().map { Math.round(it) }}")) links.add(l)
            for (st in sec.starts) { val n = idMap[st.node] ?: continue; if (startKeys.add("$n-${st.kind}-${st.toMap}")) starts.add(Start(n, st.kind, st.toMap, st.name)) }
            for (c in sec.checkpoints) idMap[c]?.let { checks.add(it) }
            for (st in sec.stitches) { val n = idMap[st.node] ?: continue; if (stitchKeys.add("$n-${st.rm}")) stitches.add(Stitch(n, st.rm, st.flags, st.pts)) }
            for (t in sec.tags) if (t !in tags) tags.add(t)
        }
        val s0 = secs[0]
        val net = SafePath.Net(s0.net.map, s0.net.cont, s0.net.built, nodes, edges, exits, secs.all { it.net.reviewed })
        return Section(s0.faction, secs.maxOf { it.level }, tags.joinToString("+"), net, ways, links, starts, checks.toList(), roadmapDanger,
            tags, wt.toIntArray(), et.toIntArray(), stitches)
    }
}
