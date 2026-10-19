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
    const val KEEP = 3
    const val MAX_BYTES = 4 * 1024 * 1024

    /** a zone's pack and, when its build is not the game's, why it may be out of date */
    class Loaded(val pack: ShippedNet.Pack, val stale: String?)

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

    fun init(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }

    /** the game's Build Key (.build.info's), read once on the route thread */
    private fun game(ctx: Context): String? {
        if (!buildRead) { gameBuild = runCatching { GameFiles.activeBuild(GameFiles.gameRoot(ctx))?.second }.getOrNull(); buildRead = true }
        return gameBuild
    }

    val files = RouteWork.Files(KEEP) { map ->
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

    /** the zone the map asked for last (the Settings row's) */
    @Volatile var lastMap = 0; private set

    /** Settings > Map's "SafePath network" row and the problem report */
    val line: String get() {
        val m = lastMap
        if (m == 0) return "Shipped network: no zone yet"
        if (m in absent) return "No shipped network for this zone: the older route"
        val s = stale[m]
        return if (s != null) "Shipped network: may be out of date ($s)" else if (files.kept() > 0) "Shipped network for this zone" else "Shipped network: loading"
    }
}
