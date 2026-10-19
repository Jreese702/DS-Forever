package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * THE SHIPPED NETWORK, format NET1 (the user, 2026-10-19: "Ship our path network"; E:/safepath-work/docs/NET1_FORMAT.md): the
 * harness's own gate-checked network for one zone, one gzip file a map in the APK's assets (`safepath/net/<map>.bin`). OUR OWN
 * computed data only: ways and nodes with their rule flags, the danger along each way per faction, the exits, the cross-zone link
 * starts, the checkpoints and the in-zone links (portals, updrafts, declared swims), stamped with the rules and the game build they came
 * from. NEVER game art, tables, terrain heights, WMO geometry, textures or file ids.
 *
 * The file is gzip(JSON): a header and one [Section] a faction (the draft differs by side: its towns, its foes). A section is a
 * [SafePath.Net] v1 document ([SafePath.Net.toJson]: `nodes`, `edges`, `exits`) with NET1's own fields beside it, so
 * [SafePath.Net.fromJson] reads a section as it is; [Section.net] is that.
 */
object ShippedNet {
    const val FORMAT = "NET1"
    const val VERSION = 1
    /** the assets' folder: `safepath/net/<map>.bin` */
    const val ASSET_DIR = "safepath/net"
    fun assetPath(map: Int) = "$ASSET_DIR/$map.bin"

    // a way's flags (bits of [Way.flags])
    const val F_ROAD = 1; const val F_TRAIL = 2; const val F_SPUR = 4; const val F_CAVE = 8; const val F_LIFT = 16
    const val F_SWIM = 32; const val F_WADE = 64; const val F_BRIDGE = 128; const val F_AVOID = 256; const val F_REWALK = 512
    val FLAG_NAMES = listOf(F_ROAD to "road", F_TRAIL to "trail", F_SPUR to "spur", F_CAVE to "cave", F_LIFT to "lift", F_SWIM to "swim",
        F_WADE to "wade", F_BRIDGE to "bridge", F_AVOID to "avoid", F_REWALK to "rewalk")
    fun flagNames(f: Int) = FLAG_NAMES.filter { f and it.first != 0 }.map { it.second }

    /** a point's mark in a [Link] (Climb's own marks: ground 0, [Climb.IN_CAVE] on a floor, [Climb.LIFT], [Climb.SWIM]) */
    const val M_GROUND = 0

    /** the stamp: [rules] = Climb.RULES_VERSION, [rulesCommit] the harness checkout's commit, [build] the game's Build Key (GameFiles.buildKey,
     *  .build.info's), [exported] ISO time, [map] / [cont] the uiMap and its continent */
    class Header(val map: Int, val cont: Int, val rules: Int, val rulesCommit: String, val build: String, val exported: String,
                 val format: String = FORMAT, val version: Int = VERSION)

    /** a way's NET1 fields beside its [SafePath.Edge] (same index): its [flags], its length [yd] */
    class Way(val flags: Int, val yd: Double)

    /** an in-zone link that is no walk on the ground (Climb.Link): a portal, an updraft, a declared swim, a floor's stair; [pts] x, y,
     *  mark (no heights); [oneWay] from its first point only; [yd] its cost in walking yards; [island] for a swim: x, y, radius of the
     *  island's ground (the swim is offered only to a goal there) */
    class Link(val kind: String, val name: String, val oneWay: Boolean, val yd: Double, val pts: List<DoubleArray>, val island: DoubleArray? = null)

    /** a cross-zone link's start in this zone: node [node] ([kind]: flight, dock, tram, zeppelin, boat, portal, gate...), into [toMap]
     *  (0 = several / by its own data), [name] */
    class Start(val node: Int, val kind: String, val toMap: Int, val name: String)

    /** one faction's network: [faction] TransportRoutes.HORDE / ALLIANCE, [level] the test character's level its danger was weighed for,
     *  [tag] the harness spec it came from; [net] the network (REMOVED ways already left out), [ways] its edges' NET1 fields (same order),
     *  [links], [starts], [checkpoints] (node ids: the named stops a route's pins can name) */
    class Section(val faction: Int, val level: Int, val tag: String, val net: SafePath.Net, val ways: List<Way>,
                  val links: List<Link> = emptyList(), val starts: List<Start> = emptyList(), val checkpoints: List<Int> = emptyList()) {
        init { require(ways.size == net.edges.size) { "ways ${ways.size} != edges ${net.edges.size}" } }
        fun toJson(): JSONObject {
            fun r1(v: Double) = (v * 10).roundToInt() / 10.0
            val o = net.toJson()
            o.put("faction", faction).put("level", level).put("tag", tag)
            o.put("ways", JSONArray(ways.map { w -> JSONArray(listOf(w.flags, r1(w.yd))) }))
            o.put("links", JSONArray(links.map { l -> JSONObject().put("kind", l.kind).put("name", l.name).put("oneWay", l.oneWay).put("yd", r1(l.yd))
                .put("pts", JSONArray(l.pts.map { p -> JSONArray(listOf(r1(p[0]), r1(p[1]), if (p.size > 2) p[2].roundToInt() else M_GROUND)) }))
                .also { j -> l.island?.let { j.put("island", JSONArray(it.map { v -> r1(v) })) } } }))
            o.put("starts", JSONArray(starts.map { s -> JSONArray(listOf(s.node, s.kind, s.toMap, s.name)) }))
            o.put("checkpoints", JSONArray(checkpoints))
            return o
        }
        companion object {
            fun fromJson(o: JSONObject): Section? {
                val net = SafePath.Net.fromJson(o) ?: return null
                val ws = o.optJSONArray("ways") ?: JSONArray()
                val ways = (0 until net.edges.size).map { i -> ws.optJSONArray(i)?.let { Way(it.optInt(0), it.optDouble(1, 0.0)) } ?: Way(0, 0.0) }
                val ls = o.optJSONArray("links") ?: JSONArray()
                val links = (0 until ls.length()).mapNotNull { i -> ls.optJSONObject(i)?.let { l ->
                    val ps = l.optJSONArray("pts") ?: JSONArray()
                    Link(l.optString("kind"), l.optString("name"), l.optBoolean("oneWay"), l.optDouble("yd", 0.0),
                        (0 until ps.length()).mapNotNull { k -> ps.optJSONArray(k)?.let { p -> doubleArrayOf(p.optDouble(0), p.optDouble(1), p.optDouble(2, 0.0)) } },
                        l.optJSONArray("island")?.let { a -> doubleArrayOf(a.optDouble(0), a.optDouble(1), a.optDouble(2)) }) } }
                val ss = o.optJSONArray("starts") ?: JSONArray()
                val starts = (0 until ss.length()).mapNotNull { i -> ss.optJSONArray(i)?.let { Start(it.optInt(0), it.optString(1), it.optInt(2), it.optString(3)) } }
                val cs = o.optJSONArray("checkpoints") ?: JSONArray()
                return Section(o.optInt("faction"), o.optInt("level"), o.optString("tag"), net, ways, links, starts, (0 until cs.length()).map { cs.optInt(it) })
            }
        }
    }

    /** one map's file: its [header] and a [Section] a faction */
    class Pack(val header: Header, val sections: List<Section>) {
        fun section(faction: Int): Section? = sections.firstOrNull { it.faction == faction }
        fun toJson(): JSONObject = JSONObject().put("fmt", header.format).put("v", header.version).put("map", header.map).put("cont", header.cont)
            .put("rules", header.rules).put("rulesCommit", header.rulesCommit).put("build", header.build).put("exported", header.exported)
            .put("sections", JSONArray(sections.map { it.toJson() }))
        companion object {
            /** null when it is not NET1, or of a later version than this reader */
            fun fromJson(o: JSONObject): Pack? {
                if (o.optString("fmt") != FORMAT || o.optInt("v") !in 1..VERSION) return null
                val h = Header(o.optInt("map"), o.optInt("cont"), o.optInt("rules"), o.optString("rulesCommit"), o.optString("build"),
                    o.optString("exported"), o.optString("fmt"), o.optInt("v"))
                val ss = o.optJSONArray("sections") ?: JSONArray()
                return Pack(h, (0 until ss.length()).mapNotNull { i -> ss.optJSONObject(i)?.let { Section.fromJson(it) } })
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

    /** the node kinds a cross-zone link starts at (TravelLinks: flights, rides) and the exits that are rides, not walks */
    val START_NODES = setOf(SafePath.FLIGHT, SafePath.DOCK, SafePath.TRAM)

    /**
     * [net] as a section: its REMOVED ways left out (and the nodes no way, exit or name keeps), each way's flags ([wetOf]: the bits of
     * what the way crosses, from its two ends' points) and length; the starts (flight masters, docks, trams, and the exits that are no
     * walk) and the checkpoints (the named nodes)
     */
    fun section(net: SafePath.Net, faction: Int, level: Int, tag: String, links: List<Link> = emptyList(),
                wetOf: (DoubleArray, DoubleArray) -> Int = { _, _ -> 0 }): Section {
        val byId = net.nodes.associateBy { it.id }
        val edges = net.edges.filter { it.state != SafePath.REMOVED && byId.containsKey(it.a) && byId.containsKey(it.b) }
        val used = HashSet<Int>().also { s -> for (e in edges) { s.add(e.a); s.add(e.b) }; for (x in net.exits) s.add(x.node) }
        val nodes = net.nodes.filter { it.id in used || it.name != null }
        val exits = net.exits.filter { byId.containsKey(it.node) }
        val ways = edges.map { e -> val a = byId[e.a]!!; val b = byId[e.b]!!
            val pa = doubleArrayOf(a.wx, a.wy); val pb = doubleArrayOf(b.wx, b.wy)
            Way(flagsOf(e, if (e.kind == SafePath.CAVE || e.kind == SafePath.LIFT) 0 else wetOf(pa, pb)), hypot(a.wx - b.wx, a.wy - b.wy)) }
        val starts = nodes.filter { it.kind in START_NODES }.map { n -> val x = exits.firstOrNull { it.node == n.id }
            Start(n.id, n.kind, x?.toMap ?: 0, n.name ?: x?.name ?: "") } +
            exits.filter { it.how !in TravelLinks.FOOT && nodes.firstOrNull { n -> n.id == it.node }?.kind !in START_NODES }
                .map { Start(it.node, it.how, it.toMap, it.name) }
        val checkpoints = nodes.filter { it.name != null }.map { it.id }
        return Section(faction, level, tag, SafePath.Net(net.map, net.cont, net.built, nodes, edges, exits, net.reviewed), ways, links, starts, checkpoints)
    }
}
