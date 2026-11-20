package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * WoW's minimap tiles at FULL size, one per ADT (533.333 yd, 512 px = 0.96 px/yd), drawn directly
 * under the markers.
 *
 * WHY: the zone pictures in assets/maps were stitched from these same tiles and then shrunk to 2560
 * px a zone -- 38 of 55 zones ship below the tiles' own sharpness, the Barrens at a quarter of it.
 * That shrink is most of the blur the user sees zoomed in (user 2026-09-29 14:58; measured, AGENT_LOG
 * "MAP-ART SHARPNESS"). The tiles are Blizzard's art, so they are not in the APK: the art harvest makes
 * them on the device (MapHarvestSteps.MINIMAP, in the background, nearest zone first) into the private
 * art folder, minimap/<continentId>/<col>_<row>.webp; a tile not harvested yet is read from the old
 * pushed copy (/sdcard/WowCompanion/minimap) when there is one (ArtHarvest.file, PER TILE: the
 * harvested folder fills while the map is in use).
 *
 * THE ZONE PICTURES ARE GONE (phase 1 of the harvest, the user 2026-10-09: no game art in the APK). In
 * their place a SMALL copy of each tile ([get] with a sample of 4 or 8: 128 or 64 px, decoded scaled)
 * is drawn at any width, and the full tile over it while the view is close.
 *
 * Only the tiles on screen are decoded, one at a time off the UI thread, RGB_565, in caches per size.
 */
object MinimapTiles {
    const val ADT = 533.33333
    private const val CACHE_MAX = 24            // full tiles: 512 KB each
    private const val SMALL_MAX = 320           // sample 4: 32 KB, sample 8: 8 KB

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<Long, Bitmap>(CACHE_MAX + 4, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap>?) = size > CACHE_MAX
    }
    private val small = object : LinkedHashMap<Long, Bitmap>(SMALL_MAX + 4, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap>?) = size > SMALL_MAX
    }
    init {
        // WCMEM: the tiles held (full and small)
        MemWatch.source("minimap") { synchronized(this) { cache.values.sumOf { it.byteCount.toLong() } + small.values.sumOf { it.byteCount.toLong() } +
            standIn.values.sumOf { it.byteCount.toLong() } } }
        // memory work #56 (D2, the user's yes 2026-11-19): the tiles were never trimmed (9 - 14 MB in the reports). Android's pressure and
        // the reliefs (Play, the world, every loading screen) keep only the newest few ([trimKeep]); a tile on screen is decoded again
        MemWatch.onTrim("minimap") { level -> trimmed(level) }
    }
    /** the bytes let go (main thread: the caches are the UI thread's); what stays: LowMemCuts.minimapKept */
    private fun trimmed(level: Int): Long {
        val (kf, ks) = LowMemCuts.minimapKept(level) ?: return 0L
        var b = 0L
        fun cut(c: LinkedHashMap<Long, Bitmap>, keep: Int) {
            val over = c.size - keep
            if (over <= 0) return
            val gone = c.keys.take(over)                               // access order: the least recently drawn first
            for (k in gone) c.remove(k)?.let { b += it.byteCount.toLong() }
        }
        synchronized(this) {
            cut(cache, kf); cut(small, ks)
            if (kf == 0) { b += standIn.values.sumOf { it.byteCount.toLong() }; standIn.clear() }
        }
        return b
    }
    private val pending = HashSet<Long>()
    /**
     * A SMALL tile's art as it is, shown while its water is repainted (review of 1.0.34 test 1485: the repaint - with its eight
     * neighbours decoded - made a close tile land later, and a tile-sized black block stood where it would be for a moment on opening
     * the map or panning). Decoded on its own thread, so the stand-ins of a whole view come at once, ahead of the repaints; dropped
     * when the repainted tile lands (32 KB / 8 KB each, only while loading). The full-size tile needs none: its small one is under it.
     * OCEAN ONLY (2026-11-17): only until the continent's depth field is ready - after that the field's picture under the tiles is the
     * continent itself (its land the art's, its ocean by depth), and a stand-in's raw ocean (Kalimdor's near-black) was a black square
     * for a moment on every zoom (the zoom-sequence test's frames).
     */
    private val quick = Executors.newSingleThreadExecutor()
    private val standIn = HashMap<Long, Bitmap>()
    /** tiles repainted before their continent's depth field was ready (WaterDepth): dropped when it is, so they are made again */
    private val provisional = HashSet<Long>()
    init {
        WaterDepth.listen { cont -> main.post {
            val it = provisional.iterator()
            while (it.hasNext()) { val k = it.next(); if ((k ushr 32).toInt() == cont) { cache.remove(k); small.remove(k); it.remove() } }
        } }
    }
    private val missing = HashSet<Long>()
    private val contOk = HashMap<Int, Boolean>()

    private fun key(cont: Int, col: Int, row: Int, sample: Int): Long =
        (cont.toLong() shl 32) or ((col and 0xFFF).toLong() shl 20) or ((row and 0xFFF).toLong() shl 8) or (sample and 0xFF).toLong()

    private fun file(cont: Int, col: Int, row: Int): File = ArtHarvest.file("minimap/$cont/${col}_$row.webp")

    /** Whether this continent has tiles on the device (harvested or pushed). UI thread. */
    fun has(cont: Int): Boolean = contOk.getOrPut(cont) {
        ArtHarvest.rootDir?.let { File(it, "minimap/$cont").isDirectory } == true || File(ArtHarvest.LEGACY, "minimap/$cont").isDirectory
    }

    /**
     * HOME'S MAP PICTURES STILL TO BE MADE (issues #316 #317 #284, 1.0.32: "the minimap on bottom screen does not display correctly. its
     * black with blue zones", "game art shows its finished but map is still empty"): the minimap step has not written its index yet
     * ([INDEX], the step's last write, once per build it finishes) in the app's own art folder. Those reports' Game art sections:
     * "minimap v2 (background): PENDING; never stamped" with "minimap 0" in every memory line - no tile drawn, so the small map was its
     * dark ground, the faint grid and the quests' blue areas, under a "Game art: ready" that is only Play's art. Home's map says so where
     * the terrain would be ([HomeMapNote]). Cached like [has]; [refresh] (the step's own, every 20 tiles and at its end) looks again. UI
     * thread, so the private folder only (review: no new /sdcard stat in the draw - shared storage can stall the main thread while the
     * game streams its files there, the case these reports were in); no art folder yet: no note.
     */
    fun building(): Boolean {
        val root = ArtHarvest.rootDir ?: return false
        return !(indexed ?: File(root, INDEX).isFile.also { indexed = it })
    }
    private var indexed: Boolean? = null
    /** the minimap step's index (MapHarvestSteps.MINIMAP writes it after its last tile) */
    const val INDEX = "minimap/index.json"

    /**
     * This tile at [sample] was looked for and is not on the device (until the next [refresh]): the minimap step part-way, its
     * continent's folder there but not this tile (review of #316 #317: Home's small map then drew a black field, no grid, no note). UI
     * thread.
     */
    fun absent(cont: Int, col: Int, row: Int, sample: Int = 1): Boolean = key(cont, col, row, sample) in missing

    /** The harvest made more tiles: look again for the continents and the tiles that were missing. UI thread. */
    fun refresh() {
        contOk.clear()
        missing.clear()
        indexed = null
        TerrainOverview.refresh()
        synchronized(this) { standIn.clear() }
    }

    /**
     * The tile, or null while it loads or when there is none; [sample] 1 = full size, 4 / 8 = a quarter / an eighth. A load in
     * flight calls [ready] on the UI thread when it lands. UI thread only.
     */
    fun get(cont: Int, col: Int, row: Int, sample: Int = 1, ready: () -> Unit): Bitmap? {
        val k = key(cont, col, row, sample)
        val c = if (sample == 1) cache else small
        c[k]?.let { return it }
        if (k in missing) return null
        val stand = !WaterDepth.ready(cont)
        if (k in pending) return if (stand) standIn[k] else null
        pending.add(k)
        val f = file(cont, col, row)
        if (stand && sample > 1 && k !in standIn) quick.execute {
            val raw = try {
                if (f.canRead()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = sample })
                else null
            } catch (t: Throwable) { null }
            // (only while the repainted one is still to come)
            if (raw != null) main.post { if (k in pending && c[k] == null) { synchronized(this) { standIn[k] = raw }; ready() } }
        }
        exec.execute {
            val bmp = try {
                if (f.canRead()) BitmapFactory.decodeFile(f.path,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = sample; inMutable = true })
                else null
            } catch (t: Throwable) { null }
            // the water by its depth (WaterDepth), once, here: never per frame
            val final = bmp == null || runCatching { WaterDepth.recolourTile(bmp, cont, col, row) }.getOrDefault(true)
            main.post {
                pending.remove(k)
                // (made provisional, and the field landed meanwhile: not kept, the next frame makes it again)
                if (bmp != null && !final && WaterDepth.ready(cont)) { ready(); return@post }
                if (bmp != null) { c[k] = bmp; if (!final) provisional.add(k) } else missing.add(k)
                synchronized(this) { standIn.remove(k) }
                ready()
            }
        }
        return if (stand) standIn[k] else null
    }
}
