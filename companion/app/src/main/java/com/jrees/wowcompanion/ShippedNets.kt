package com.jrees.wowcompanion

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * THE SHIPPED NETWORKS ON THE DEVICE (the user, 2026-10-19: "Ship our path network"; DEVICE_INTEGRATION_PLAN.md SHIPPED NETWORK step 2):
 * each zone's NET1 file from the APK's assets (`safepath/net/<map>.bin`, [ShippedNet]) read on the route thread ([RouteWork.Files]:
 * never on the UI thread), at most [KEEP] zones kept in memory and a file no bigger than [MAX_BYTES] read. A file exported for another
 * game build than the player's is STILL USED and marked "may be out of date" - logged once a zone ("WCMAP net <map>: may be out of
 * date (exported for build X, game is Y)") and said in Settings > Map's "SafePath network" row ([line]) and the problem report.
 */
object ShippedNets {
    /**
     * Memory work #56 (D1, the user's yes 2026-11-19): ONE cache of whole zone files for the in-zone route AND the cross-zone legs
     * (ShippedCrossStore kept 4 more of its own: up to 7 zones, 5 - 36 MB each). At most [KEEP]; the zone the player is in and its
     * neighbours ([keepNow]) stay while the legs of a chain read other zones; Android's pressure lets go ([letGo]).
     */
    const val KEEP = 4
    const val MAX_BYTES = 4 * 1024 * 1024

    /** a zone's pack and, when its build is not the game's, why it may be out of date; [bytes] its estimated size in memory ([estimate]) */
    class Loaded(val pack: ShippedNet.Pack, val stale: String?) {
        val bytes: Long = estimate(pack)
        /** the maps this zone's exits lead into (its neighbours) */
        val neighbours: Set<Int> by lazy { pack.sections.flatMap { s -> s.net.exits.map { it.toMap } }.filter { it != pack.header.map && it != 0 }.toSet() }
    }

    /**
     * A PACK'S BYTES IN MEMORY, ESTIMATED from what it holds (the objects the reader makes: each node, edge, way point and the strings
     * the JSON reader gives each; the roadmap's and the walk grid's arrays). Measured on the PC against the heap for the 45 shipped
     * files (ShippedNetsMemoryTest): within a factor of two. For the memory line, not a budget to the byte. Pure.
     */
    fun estimate(p: ShippedNet.Pack): Long {
        var b = 256L
        for (s in p.sections) b += sectionBytes(s)
        p.roadmap?.let { r ->
            b += 16L * 7 + 4L * (r.nodeCell.size + r.nodeGr.size + r.nodeGc.size + r.nodeFlags.size + r.edgeA.size + r.edgeB.size + r.edgeFlags.size)
            b += 16L + 4L * r.edgePts.size + r.edgePts.sumOf { a -> if (a == null) 0L else 16L + 4L * a.size }
        }
        // the walk grid: its classes and faces, and what its decode adds (a deck's heights: a float a vertex, the biggest part)
        p.walk?.let { w -> b += 64L + w.cls.size + (w.faces?.size ?: 0) + (w.faces48?.size ?: 0) + 4L * (w.deckZ?.size ?: 0) +
            (w.deckHigh?.size ?: 0) + (w.dropD?.size ?: 0) }
        return b
    }
    private const val STR = 48L                     // a short string the JSON reader made (its own copy)
    private const val PT = 36L                      // a point: a double[2] and its list slot
    fun sectionBytes(s: ShippedNet.Section): Long {
        var b = 128L
        b += s.net.nodes.size * (48L + 3 * STR)
        b += s.net.edges.size * (48L + 3 * STR)
        b += s.net.exits.size * (32L + 2 * STR)
        for (w in s.ways) b += 40L + (w.pts?.let { 16L + it.size * PT } ?: 0L) + (w.layers?.let { 16L + 4L * it.size } ?: 0L)
        for (l in s.links) b += 64L + 2 * STR + l.pts.size * 44L
        b += s.starts.size * (32L + 2 * STR)
        b += s.checkpoints.size * 20L
        b += (s.roadmapDanger?.size ?: 0) + (s.wayTag?.size ?: 0) * 4L + (s.exitTag?.size ?: 0) * 4L
        for (st in s.stitches) b += 40L + st.pts.size * PT
        return b
    }

    /** [header]'s build against the game's [game] (null / empty: unknown, no mark): the words, or null when they agree */
    fun staleWhy(header: ShippedNet.Header, game: String?): String? {
        if (game.isNullOrEmpty() || header.build.isEmpty()) return null
        return if (header.build.equals(game, ignoreCase = true)) null else "exported for build ${header.build}, game is $game"
    }

    /**
     * zone [map]'s pack from [open] (an asset's path -> its stream, or null when there is none): null = no file, too big, not NET1.
     * [said] keeps the maps already logged stale (the log line once a zone).
     */
    fun load(map: Int, open: (String) -> InputStream?, game: String?, log: (String) -> Unit, said: MutableSet<Int> = HashSet()): Loaded? {
        val bytes = open(ShippedNet.assetPath(map))?.use { s -> readBounded(s, MAX_BYTES) } ?: return null
        if (bytes.isEmpty()) { log("net $map: the file is bigger than ${MAX_BYTES / 1024} KB, not read"); return null }
        val pack = ShippedNet.read(ByteArrayInputStream(bytes)) ?: run { log("net $map: not a NET1 file this reader knows"); return null }
        val stale = staleWhy(pack.header, game)
        if (stale != null && said.add(map)) log("net $map: may be out of date ($stale)")
        return Loaded(pack, stale)
    }

    /** [s]'s bytes, or an empty array when it holds more than [max] */
    fun readBounded(s: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var n = 0
        while (true) { val r = s.read(buf); if (r < 0) break; n += r; if (n > max) return ByteArray(0); out.write(buf, 0, r) }
        return out.toByteArray()
    }

    /** the section for [faction] (TransportRoutes.HORDE / ALLIANCE), else the pack's first (0 = unknown side) */
    fun sectionOf(pack: ShippedNet.Pack, faction: Int): ShippedNet.Section? = pack.section(faction) ?: pack.sections.firstOrNull()

    // ---------------------------------------------------------------- the app's store

    @Volatile private var appCtx: Context? = null
    @Volatile private var gameBuild: String? = null
    @Volatile private var buildRead = false
    private val staleSaid = ConcurrentHashMap.newKeySet<Int>()
    private val absent = ConcurrentHashMap.newKeySet<Int>()
    private val stale = ConcurrentHashMap<Int, String>()

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        watch()
    }
    @Volatile private var watched = false
    /** the memory line and the pressure (once; ShippedCrossStore calls it too) */
    fun watch() {
        if (watched) return
        watched = true
        MemWatch.source("safepath") { bytes() }
        MemWatch.onTrim("safepath") { level -> letGo(level) }
    }

    @Volatile private var buildStamp = 0L

    /**
     * the game's Build Key (.build.info's row the game runs: the beta's, or wowf's after the launch switch), read on the route thread
     * once, and again when .build.info changes (the switch rewrites it while the app runs): a zone's mark then follows the new build
     */
    private fun game(ctx: Context): String? {
        val root = runCatching { GameFiles.gameRoot(ctx) }.getOrNull()
        val stamp = runCatching { java.io.File(root, WowProduct.BUILD_INFO).lastModified() }.getOrDefault(0L)
        if (!buildRead || stamp != buildStamp) {
            val was = gameBuild
            gameBuild = runCatching { root?.let { GameFiles.activeBuild(it)?.second } }.getOrNull()
            buildStamp = stamp
            if (buildRead && was != gameBuild) { staleSaid.clear(); stale.clear(); files.trim() }    // the kept zones' marks were the old build's
            buildRead = true
        }
        return gameBuild
    }

    // Light 8 (LIGHT8_PLAN.md): the player's zone is the last to go when every kept zone is in the keep set, and another zone's
    // network waits while the game loads (LoadHold); every other Thor as test build 1516
    val files = RouteWork.Files(KEEP, keep = { keepNow() }, sizeOf = { it.bytes }, playerFirst = { DeviceTier.light8() },
        holdJob = LoadHold.SAFEPATH, holdIf = { m -> LoadHold.otherZone(m).also { if (it) ShippedCrossStore.holdBump() } }) { map ->
        val ctx = appCtx ?: return@Files null
        load(map, { p -> runCatching { ctx.assets.open(p) }.getOrNull() }, game(ctx), { android.util.Log.i("WCMAP", it) }, staleSaid)
            .also { l -> if (l == null) absent.add(map) else { absent.remove(map); l.stale?.let { stale[map] = it } ?: stale.remove(map) } }
    }

    /** zone [map]'s network for [faction]: in memory, or null (asked for on the route thread; [none] says whether there is no file) */
    fun section(map: Int, faction: Int): Pair<ShippedNet.Section, Loaded>? {
        lastMap = map
        val l = files.get(map) ?: return null
        return sectionOf(l.pack, faction)?.let { it to l }
    }
    /** a load of zone [map] found no shipped network (no file, or unreadable) */
    fun none(map: Int): Boolean = map in absent

    /** the zone the map asked for last (the Settings row's; the cache keeps it and its neighbours) */
    @Volatile var lastMap = 0; private set

    /** the zones the cache keeps while it can: the player's ([lastMap]) first, then the maps its exits lead into (when it is loaded) */
    fun keepNow(): List<Int> {
        val m = lastMap
        if (m == 0) return emptyList()
        return listOf(m) + (files.peek(m)?.neighbours ?: emptySet())
    }

    /**
     * ANDROID'S PRESSURE (memory work #56, D1: MemWatch's trims and its reliefs at Play, the world and every loading screen): at
     * running-low (the relief's level) every zone but the player's goes; at running-critical, onLowMemory or any background level the
     * player's goes too, and the cross-zone graph ([ShippedCrossStore.release], read again from its small cache file when next asked).
     * The bytes let go. Pure in its choice ([keepAt]); any thread.
     */
    fun letGo(level: Int): Long {
        val stay = keepAt(level, lastMap)
        if (stay == null) return 0L
        var b = files.trimTo(stay)
        if (stay.isEmpty()) b += ShippedCrossStore.release()
        return b
    }
    /** the zones that stay at [level]: null = nothing goes (below running-low), [player] alone, or none */
    fun keepAt(level: Int, player: Int): List<Int>? = when {
        level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> null
        severe(level) -> emptyList()
        player != 0 -> listOf(player)
        else -> emptyList()
    }
    /** running-critical, onLowMemory or a background level (the app's screen not shown and Android short) */
    fun severe(level: Int): Boolean = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == MemWatch.LOW_MEMORY ||
        level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE

    /** the memory line's "safepath": the whole zones kept and the cross-zone graph */
    fun bytes(): Long = files.bytes() + ShippedCrossStore.bytes()

    /** Settings > Map's "SafePath network" row and the problem report */
    val line: String get() {
        val m = lastMap
        if (m == 0) return "Shipped network: no zone yet"
        if (m in absent) return "No shipped network for this zone: the older route"
        val s = stale[m]
        return if (s != null) "Shipped network: may be out of date ($s)" else if (files.kept() > 0) "Shipped network for this zone" else "Shipped network: loading"
    }
}
