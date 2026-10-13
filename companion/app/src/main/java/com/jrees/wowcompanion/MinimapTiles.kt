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
    private val pending = HashSet<Long>()
    private val missing = HashSet<Long>()
    private val contOk = HashMap<Int, Boolean>()

    private fun key(cont: Int, col: Int, row: Int, sample: Int): Long =
        (cont.toLong() shl 32) or ((col and 0xFFF).toLong() shl 20) or ((row and 0xFFF).toLong() shl 8) or (sample and 0xFF).toLong()

    private fun file(cont: Int, col: Int, row: Int): File = ArtHarvest.file("minimap/$cont/${col}_$row.webp")

    /** Whether this continent has tiles on the device (harvested or pushed). UI thread. */
    fun has(cont: Int): Boolean = contOk.getOrPut(cont) {
        ArtHarvest.rootDir?.let { File(it, "minimap/$cont").isDirectory } == true || File(ArtHarvest.LEGACY, "minimap/$cont").isDirectory
    }

    /** The harvest made more tiles: look again for the continents and the tiles that were missing. UI thread. */
    fun refresh() {
        contOk.clear()
        missing.clear()
    }

    /**
     * The tile, or null while it loads or when there is none; [sample] 1 = full size, 4 / 8 = a quarter / an eighth. A load in
     * flight calls [ready] on the UI thread when it lands. UI thread only.
     */
    fun get(cont: Int, col: Int, row: Int, sample: Int = 1, ready: () -> Unit): Bitmap? {
        val k = key(cont, col, row, sample)
        val c = if (sample == 1) cache else small
        c[k]?.let { return it }
        if (k in missing || k in pending) return null
        pending.add(k)
        val f = file(cont, col, row)
        exec.execute {
            val bmp = try {
                if (f.canRead()) BitmapFactory.decodeFile(f.path,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inSampleSize = sample })
                else null
            } catch (t: Throwable) { null }
            main.post {
                pending.remove(k)
                if (bmp != null) c[k] = bmp else missing.add(k)
                ready()
            }
        }
        return null
    }
}
